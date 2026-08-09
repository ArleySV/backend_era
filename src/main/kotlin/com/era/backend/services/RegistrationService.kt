package com.era.backend.services

import com.era.backend.exceptions.ConflictException
import com.era.backend.exceptions.EmailAlreadyRegisteredException
import com.era.backend.exceptions.EmailLockedException
import com.era.backend.exceptions.FieldError
import com.era.backend.exceptions.ValidationException
import com.era.backend.models.dto.RegisterRequestDto
import com.era.backend.models.dto.RegisterResponseDto
import com.era.backend.models.entities.EstadoUsuario
import com.era.backend.models.entities.RegistroPendienteRow
import com.era.backend.repositories.RegistroPendienteRepository
import com.era.backend.repositories.TransactionRunner
import com.era.backend.repositories.UsuarioRepository
import com.era.backend.utils.Validators
import java.time.LocalDateTime

/**
 * Reglas de negocio del registro (REQ-FUN-01, CU-01, HU-01, HU-15).
 * Puro de Ktor y de SQL: recibe DTOs, lanza excepciones de dominio y delega el acceso a
 * datos en los repositorios (ARQUITECTURA_BASE.md §2.3).
 */
class RegistrationService(
    private val registroRepository: RegistroPendienteRepository,
    private val usuarioRepository: UsuarioRepository,
    private val otpService: OtpService,
    private val transactionRunner: TransactionRunner,
) {

    companion object {
        private const val MENSAJE_EXITO = "Código de verificación enviado al correo."
        private const val VIGENCIA_OTP_MINUTOS = 10L
    }

    /**
     * Registra al menor con los datos aportados por el acudiente (CU-01, HU-15): valida
     * reglas de negocio y persiste `registro_pendiente`, enviando el OTP al correo.
     *
     * Reglas de negocio (§4 service):
     * - Unicidad de correo contra `usuario` (activo → `EmailAlreadyRegisteredException`;
     *   eliminado → `EmailLockedException`) y `registro_pendiente` no expirado →
     *   `EmailAlreadyRegisteredException`, con limpieza lazy de filas expiradas (V2).
     * - Unicidad de `nombreUsuario` contra `usuario` y `registro_pendiente` →
     *   `ConflictException` (V1).
     * - Fuerza de contraseña (REQ-FUN-01 CA2, V3): ≥8, may/min/núm/símbolo; ≠ username;
     *   sin datos personales → `ValidationException` con `details`.
     * - Hash bcrypt de contraseña y de OTP; `expira_en = now + 10 min` (REQ-FUN-01 CA4).
     * - Delega la generación/hash/envío del OTP en [OtpService].
     *
     * **Atomicidad (obligatorio):** la operación de BD — limpieza lazy de expirados +
     * checks de unicidad + inserción — se ejecuta en una **única transacción** vía
     * [TransactionRunner]. Sin ella, dos registros paralelos podrían reservar el mismo
     * correo/usuario (anti-TOCTOU); las constraints `UNIQUE` de MySQL son el respaldo
     * final. La política de contraseña (cálculo puro) y el envío SMTP quedan FUERA de la
     * transacción: el primero no toca la BD y el segundo no debe mantener la transacción
     * ni la conexión abiertas durante la latencia del correo. Seguridad (CLAUDE.md §6):
     * nunca loguear correo, cédula, hashes ni el OTP.
     *
     * @return [RegisterResponseDto] con solo el mensaje de éxito (§3.2, mínimo privilegio).
     */
    fun register(request: RegisterRequestDto): RegisterResponseDto {
        // Regla de negocio pura: se resuelve antes de abrir cualquier transacción.
        validarPoliticaContrasena(request)

        val code = otpService.generate()

        transactionRunner.run {
            // Limpieza lazy de expirados (V2): libera correo/username para un nuevo registro.
            registroRepository.deleteExpiredByEmail(request.correo)
            registroRepository.deleteExpiredByUsername(request.nombreUsuario)

            // Unicidad de correo: cuenta activa, cuenta en soft delete y pendiente no expirado.
            usuarioRepository.findByEmail(request.correo)?.let { existente ->
                when (existente.estado) {
                    EstadoUsuario.ACTIVO ->
                        throw EmailAlreadyRegisteredException("El correo ya está registrado.")
                    EstadoUsuario.ELIMINADO ->
                        throw EmailLockedException("El correo no está disponible.")
                }
            }
            registroRepository.findByEmail(request.correo)?.let {
                throw EmailAlreadyRegisteredException("El correo ya está registrado.")
            }

            // Unicidad de username (V1): también cuenta si la fila de `usuario` está en soft delete.
            if (usuarioRepository.existsByUsername(request.nombreUsuario)) {
                throw ConflictException("El nombre de usuario ya está en uso.")
            }
            registroRepository.findByUsername(request.nombreUsuario)?.let {
                throw ConflictException("El nombre de usuario ya está en uso.")
            }

            val ahora = LocalDateTime.now()
            val fila = RegistroPendienteRow(
                idRegistro = 0L, // lo asigna la BD (auto-increment)
                correo = request.correo,
                nombreUsuario = request.nombreUsuario,
                contrasenaHash = otpService.hash(request.contrasena),
                nombreMenor = request.nombreMenor,
                fechaNacimiento = Validators.parseFechaNacimiento(request.fechaNacimiento)
                    ?: throw ValidationException("Datos de registro inválidos."),
                nombreAcudiente = request.nombreAcudiente,
                cedulaAcudiente = request.cedulaAcudiente,
                avatar = request.avatar,
                codigoHash = otpService.hash(code),
                intentosFallidos = 0,
                expiraEn = ahora.plusMinutes(VIGENCIA_OTP_MINUTOS),
                // P2/P3: el envío del alta también se registra, para que el throttle de
                // reenvío (60 s) sea efectivo desde el primer código.
                ultimoEnvioEn = ahora,
                creadoEn = ahora, // la BD sobreescribe con su CURRENT_TIMESTAMP
            )
            registroRepository.insert(fila)
        }

        // Envío FUERA de la transacción: no se mantiene la conexión/transacción durante el SMTP.
        otpService.send(request.correo, code)
        return RegisterResponseDto(MENSAJE_EXITO)
    }

    /**
     * Política de contraseña (REQ-FUN-01 CA2, V3): ≥8 caracteres y ≤72 (tope técnico de
     * bcrypt para evitar truncamiento silencioso), mayúscula, minúscula, número y símbolo;
     * ≠ `nombreUsuario`; no debe contener el `nombreMenor` ni los tokens del nombre
     * (case-insensitive). Falla → [ValidationException] con `details` por regla.
     *
     * Regla de negocio del service (NO del controller): no se decide en la capa de forma.
     */
    private fun validarPoliticaContrasena(request: RegisterRequestDto) {
        val contrasena = request.contrasena
        val errores = mutableListOf<FieldError>()

        if (contrasena.length < 8) {
            errores += FieldError("contrasena", "Debe tener al menos 8 caracteres.")
        }
        if (contrasena.length > 72) {
            errores += FieldError("contrasena", "Máximo 72 caracteres.")
        }
        if (!contrasena.any { it.isUpperCase() }) {
            errores += FieldError("contrasena", "Debe incluir al menos una mayúscula.")
        }
        if (!contrasena.any { it.isLowerCase() }) {
            errores += FieldError("contrasena", "Debe incluir al menos una minúscula.")
        }
        if (!contrasena.any { it.isDigit() }) {
            errores += FieldError("contrasena", "Debe incluir al menos un número.")
        }
        if (!contrasena.any { !it.isLetterOrDigit() && !it.isWhitespace() }) {
            errores += FieldError("contrasena", "Debe incluir al menos un símbolo.")
        }
        if (contrasena.equals(request.nombreUsuario, ignoreCase = true)) {
            errores += FieldError("contrasena", "No puede ser igual al nombre de usuario.")
        }

        // V3 (interpretación mínima): el nombre del menor o cualquiera de sus tokens no debe
        // aparecer en la contraseña. Se filtran tokens < 3 caracteres para no sobrerestringir
        // contra conectores ("de", "y", "a") ni letras sueltas.
        val tokensDelNombre = request.nombreMenor
            .trim()
            .split(Regex("\\s+"))
            .filter { it.length >= 3 }
        if (tokensDelNombre.isNotEmpty() && tokensDelNombre.any { contrasena.contains(it, ignoreCase = true) }) {
            errores += FieldError("contrasena", "No puede contener datos personales.")
        }

        if (errores.isNotEmpty()) {
            throw ValidationException("La contraseña no cumple la política de seguridad.", errores)
        }
    }
}
