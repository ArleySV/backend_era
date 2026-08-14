package com.era.backend.services

import at.favre.lib.crypto.bcrypt.BCrypt
import com.era.backend.exceptions.AccountInactiveException
import com.era.backend.exceptions.AccountLockedException
import com.era.backend.exceptions.InvalidCredentialsException
import com.era.backend.models.dto.LoginRequestDto
import com.era.backend.models.dto.LoginResponseDto
import com.era.backend.models.entities.EstadoUsuario
import com.era.backend.models.entities.UsuarioRow
import com.era.backend.repositories.TransactionRunner
import com.era.backend.repositories.UsuarioRepository
import java.time.LocalDateTime

/**
 * Reglas de negocio del login (REQ-FUN-02, REQ-NF-02, CU-04, HU-02) — `modulo-b-analisis.md`
 * §5. Puro de Ktor y de SQL: recibe el DTO, lanza excepciones de dominio y delega el acceso
 * a datos en [UsuarioRepository] y la emisión del token en [JwtTokenService]
 * (ARQUITECTURA_BASE.md §2.3).
 *
 * Seguridad:
 * - Error de credenciales **genérico** (nunca revela qué campo falló ni si la cuenta existe).
 * - Anti-enumeración por timing (B-4): con identificador inexistente se verifica la
 *   contraseña contra [HASH_DUMMY] (constante pre-calculada, coste 11) para normalizar el
 *   tiempo de respuesta.
 * - Estado de soft delete evaluado **solo** tras contraseña correcta (B-5): una cuenta
 *   `eliminado` con credenciales erróneas responde el mismo 401 genérico que cualquiera.
 * - **Zero logs:** nunca se loguea el identificador, la contraseña, el hash ni el token.
 */
class LoginService(
    private val usuarioRepository: UsuarioRepository,
    private val transactionRunner: TransactionRunner,
    private val jwtTokenService: JwtTokenService,
) {

    companion object {
        /** Límite de intentos fallidos consecutivos antes del bloqueo (REQ-FUN-02 CA3). */
        private const val MAX_INTENTOS_LOGIN = 5

        /** Ventana de bloqueo tras superar el límite (2 min, REQ-NF-02). */
        private const val BLOQUEO_MINUTOS = 2L

        /**
         * Hash bcrypt pre-calculado (coste 11, alineado con [OtpService.COSTE_BCRYPT_PASSWORD])
         * para el anti-enumeración por timing (B-4, auditoría #1). Generado una sola vez; el
         * `verify` contra esta constante cuesta lo mismo que un verify real de una contraseña
         * nueva (coste 11). No desperdicia CPU por request ni usa `SecureRandom`. Nunca se loguea.
         *
         * Bajado de 12 a 11 (2026-08-13, decisión del propietario): si el dummy quedara en 12,
         * la variante de usernames inexistentes seguiría costando ~500 ms por request y el DoS
         * no quedaría mitigado para la variante que nunca dispara el bloqueo B-2/B-3. Los
         * usuarios legacy (hash coste 12) quedan ~2x más lentos que el dummy (asimetría de
         * timing acotada y auto-curable: se homogeneiza al re-registrar/resetear o re-hashear).
         */
        private const val HASH_DUMMY = "\$2a\$11\$EiUgkVIbOAAPuwiHhpq1AuPoFqx9MDrzJmZfefd6Ax4WK4ml9MONS"

        /** Mensaje genérico: no revela qué campo falló ni si la cuenta existe (REQ-FUN-02). */
        private const val MENSAJE_GENERICO = "Credenciales incorrectas."

        /** Mensaje de bloqueo temporal (REQ-FUN-02 CA3). */
        private const val MENSAJE_BLOQUEADO =
            "Demasiados intentos fallidos. Cuenta bloqueada temporalmente."
    }

    private enum class ResultadoLogin {
        AUTENTICADO,
        NO_ENCONTRADO,
        CREDENCIALES_INVALIDAS,
        BLOQUEADO,
        CUENTA_INACTIVA,
    }

    /**
     * Autentica al usuario con su identificador (username o correo, B-1) y contraseña.
     *
     * **Atomicidad (auditoría #2):** la lectura con `FOR UPDATE`, la verificación bcrypt y
     * la escritura del estado de login (incremento por fallo, apertura de ventana, limpieza
     * lazy o reset tras éxito) ocurren en la **misma** transacción. El `throw` de la
     * excepción de dominio se hace FUERA del bloque para que la escritura se commitee
     * (mismo patrón que `VerificationService` con P1). Un login exitoso nunca deja un
     * contador ni una ventana residuales.
     *
     * Respuestas: 200 con token · 401 genérico · 403 `ACCOUNT_INACTIVE` (B-5) · 423
     * `ACCOUNT_LOCKED` (B-2/B-3).
     *
     * @return [LoginResponseDto] con el token de sesión (30 días, mínimo privilegio).
     */
    fun login(request: LoginRequestDto): LoginResponseDto {
        val identificador = request.usuarioOCorreo.trim()
        var usuarioAutenticado: UsuarioRow? = null
        var resultado = ResultadoLogin.CREDENCIALES_INVALIDAS

        transactionRunner.run {
            // B-1: si contiene '@' se interpreta como correo (lowercase, V5) con fallback a
            // username; si no, como username (case-insensitive por collation, B-6). FOR UPDATE.
            val usuario =
                if (identificador.contains('@')) {
                    usuarioRepository.findByEmailForUpdate(identificador.lowercase())
                        ?: usuarioRepository.findByUsernameForUpdate(identificador)
                } else {
                    usuarioRepository.findByUsernameForUpdate(identificador)
                }
            // B-4: identificador inexistente → NO_ENCONTRADO (auditoría 2026-08-13). Sin esta
            // asignación la rama del `when` quedaba inalcanzable y la igualación de timing con
            // HASH_DUMMY nunca se ejecutaba (oráculo de enumeración por tiempo, REQ-NF-02).
            if (usuario == null) {
                resultado = ResultadoLogin.NO_ENCONTRADO
                return@run
            }

            val ahora = LocalDateTime.now()

            // B-2: ventana activa → bloqueado, sin tocar bcrypt ni incrementar el contador.
            if (usuario.bloqueadoHasta != null && usuario.bloqueadoHasta.isAfter(ahora)) {
                resultado = ResultadoLogin.BLOQUEADO
                return@run
            }
            // B-2: ventana expirada → limpieza lazy: contador a 0 y ventana a NULL.
            if (usuario.bloqueadoHasta != null) {
                usuarioRepository.actualizarEstadoLogin(usuario.idUsuario, 0, null)
            }

            if (!BCrypt.verifyer().verify(request.contrasena.toCharArray(), usuario.contrasenaHash).verified) {
                val nuevosIntentos = usuario.intentosLoginFallidos + 1
                if (nuevosIntentos >= MAX_INTENTOS_LOGIN) {
                    // B-3: el 5.º fallo abre la ventana y resetea el contador (parte de 0 al expirar).
                    usuarioRepository.actualizarEstadoLogin(
                        usuario.idUsuario,
                        0,
                        ahora.plusMinutes(BLOQUEO_MINUTOS),
                    )
                    resultado = ResultadoLogin.BLOQUEADO
                } else {
                    usuarioRepository.actualizarEstadoLogin(usuario.idUsuario, nuevosIntentos, null)
                    resultado = ResultadoLogin.CREDENCIALES_INVALIDAS
                }
                return@run
            }

            // Éxito: reset atómico del contador y la ventana, en la misma transacción.
            usuarioRepository.actualizarEstadoLogin(usuario.idUsuario, 0, null)

            // B-5: el estado de soft delete se evalúa solo tras contraseña correcta.
            resultado =
                if (usuario.estado == EstadoUsuario.ELIMINADO) {
                    ResultadoLogin.CUENTA_INACTIVA
                } else {
                    usuarioAutenticado = usuario
                    ResultadoLogin.AUTENTICADO
                }
        }

        return when (resultado) {
            // B-4: normaliza el tiempo de respuesta entre usuario inexistente y contraseña
            // incorrecta; después el error es idéntico en ambos casos.
            ResultadoLogin.NO_ENCONTRADO -> {
                BCrypt.verifyer().verify(request.contrasena.toCharArray(), HASH_DUMMY)
                throw InvalidCredentialsException(MENSAJE_GENERICO)
            }
            ResultadoLogin.CREDENCIALES_INVALIDAS -> throw InvalidCredentialsException(MENSAJE_GENERICO)
            ResultadoLogin.BLOQUEADO -> throw AccountLockedException(MENSAJE_BLOQUEADO)
            ResultadoLogin.CUENTA_INACTIVA -> throw AccountInactiveException("La cuenta no está activa.")
            ResultadoLogin.AUTENTICADO ->
                LoginResponseDto(jwtTokenService.emitir(checkNotNull(usuarioAutenticado).idUsuario))
        }
    }
}
