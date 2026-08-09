package com.era.backend.services

import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.era.backend.config.JwtConfig
import com.era.backend.exceptions.OtpInvalidException
import com.era.backend.exceptions.OtpResendThrottledException
import com.era.backend.exceptions.PasswordReuseException
import com.era.backend.exceptions.ResetTokenInvalidException
import com.era.backend.models.dto.PasswordResetConfirmRequestDto
import com.era.backend.models.dto.PasswordResetRequestDto
import com.era.backend.models.dto.PasswordResetResponseDto
import com.era.backend.models.dto.PasswordResetVerifyRequestDto
import com.era.backend.models.dto.PasswordResetVerifyResponseDto
import com.era.backend.models.entities.CodigoVerificacionRow
import com.era.backend.models.entities.EstadoUsuario
import com.era.backend.models.entities.TokensReseteoRow
import com.era.backend.models.entities.UsuarioRow
import com.era.backend.repositories.CodigoVerificacionRepository
import com.era.backend.repositories.TokensReseteoRepository
import com.era.backend.repositories.TransactionRunner
import com.era.backend.repositories.UsuarioRepository
import com.era.backend.utils.PasswordPolicy
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

/**
 * Reglas de negocio del Módulo C — recuperación de contraseña en 3 pasos
 * (REQ-FUN-07, CU-03, CU-11, HU-07). Puro de Ktor y de SQL: recibe DTOs, lanza
 * excepciones de dominio y delega el acceso a datos en los repositorios
 * (ARQUITECTURA_BASE.md §2.3).
 *
 * Flujo (`modulo-c-analisis.md` C-1…C-6):
 * 1. [solicitarReseteo] — valida existencia activa de la cuenta (anti-enumeración),
 *    aplica throttle de 60 s (P2) y emite un OTP de 6 dígitos con vigencia de 10 min.
 * 2. [verificarReseteo] — valida el OTP (P1: máx. 3 intentos, hash bcrypt) y, si es
 *    correcto, emite un token puente JWT de 10 min single-use (C-3) registrado en
 *    `tokens_reseteo`.
 * 3. [confirmarReseteo] — valida el token puente (firma, aud, purpose, exp, doble
 *    vínculo `jti`+`sub`, single-use), exige política compartida (`PasswordPolicy`,
 *    C-6) y veto a repetir la contraseña anterior (REQ-FUN-07 CA5), y actualiza el hash.
 *
 * **Fronteras de seguridad:**
 * - El envío SMTP ocurre SIEMPRE FUERA de la transacción (misma regla que Módulo A): no
 *   se mantiene la conexión abierta durante la latencia del correo.
 * - Anti-enumeración (C-1): respuestas genéricas idénticas y [HASH_DUMMY] para igualar
 *   timing; nunca se confirma si el correo existe.
 * - Zero logs: nunca se loguea correo, OTP, hash, jti ni token (CLAUDE.md §6).
 */
class PasswordResetService(
    private val usuarioRepository: UsuarioRepository,
    private val codigoRepository: CodigoVerificacionRepository,
    private val tokenRepository: TokensReseteoRepository,
    private val otpService: OtpService,
    private val jwtTokenService: JwtTokenService,
    private val jwtConfig: JwtConfig,
    private val transactionRunner: TransactionRunner,
) {

    companion object {
        private const val VIGENCIA_OTP_MINUTOS = 10L
        private const val THROTTLE_REENVIO_SEGUNDOS = 60L

        private const val MENSAJE_REQUEST = "Si el correo está registrado, recibirás un código de verificación."
        private const val MENSAJE_CONFIRMADO = "Contraseña actualizada. Ya puedes iniciar sesión."
        private const val MENSAJE_INVALIDO = "El código de verificación es inválido o ha expirado."
        private const val MENSAJE_TOKEN_INVALIDO = "El enlace de recuperación es inválido o ha expirado."

        /**
         * Hash bcrypt **pre-calculado** de un placeholder (decisión C-1). Se verifica contra
         * él cuando el correo no corresponde a una cuenta activa para **igualar el timing**
         * del camino real (anti-enumeración, REQ-FUN-07 CA4): un atacante no puede distinguir
         * por tiempo de cómputo si el correo existe. Nunca coincide con un código real y jamás
         * se loguea.
         */
        private const val HASH_DUMMY = "\$2a\$10\$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
    }

    private enum class ResultadoVerificacion {
        VERIFICADO,
        OTP_INVALIDO,
        SIN_CODIGO,
    }

    private enum class ResultadoConfirmacion {
        CAMBIADA,
        TOKEN_INVALIDO,
        REUTILIZADA,
    }

    /**
     * Paso 1: solicita el OTP de recuperación (REQ-FUN-07).
     *
     * **Anti-enumeración (C-1):** si el correo no corresponde a una cuenta activa, se
     * calcula un hash bcrypt dummy (igualar timing del camino real) y se responde 200 con
     * el mensaje genérico, sin insertar ni enviar nada. Una cuenta `ELIMINADO` se trata
     * como inexistente (REQ-FUN-05): no puede recuperar su contraseña.
     *
     * **Throttle (P2, C-2):** si el último envío del usuario fue hace menos de 60 s,
     * responde 429 `OTP_RESEND_THROTTLED`. Solo aplica cuando hay un código previo (espejo
     * de `resend-otp`, decisión aprobada).
     *
     * **Transacción:** el insert/actualización del código usa `findUltimoPorUsuarioForUpdate`
     * (`FOR UPDATE`, C-3) para serializar solicitudes concurrentes del mismo usuario. El
     * envío SMTP ocurre FUERA de la transacción.
     */
    fun solicitarReseteo(request: PasswordResetRequestDto): PasswordResetResponseDto {
        // Exposed exige contexto transaccional incluso para el SELECT (sin él:
        // "No transaction in context"). La lectura se captura en `var` y se consume
        // fuera del bloque; el SMTP queda SIEMPRE fuera de cualquier transacción.
        var usuario: UsuarioRow? = null
        transactionRunner.run {
            usuario = usuarioRepository.findByEmail(request.correo)
        }
        val activo = usuario
        if (activo == null || activo.estado != EstadoUsuario.ACTIVO) {
            // Iguala el coste bcrypt del camino real sin insertar ni enviar nada.
            otpService.hash(otpService.generate())
            return PasswordResetResponseDto(MENSAJE_REQUEST)
        }

        val code = otpService.generate()
        transactionRunner.run {
            val ultimo = codigoRepository.findUltimoPorUsuarioForUpdate(activo.idUsuario)
            val ahora = LocalDateTime.now()
            val ultimoEnvio = ultimo?.ultimoEnvioEn
            if (ultimoEnvio != null &&
                Duration.between(ultimoEnvio, ahora).seconds < THROTTLE_REENVIO_SEGUNDOS
            ) {
                // Sin escrituras previas en la transacción: el throw no pierde nada.
                throw OtpResendThrottledException("Reintenta el envío en unos segundos.")
            }

            val codigoHash = otpService.hash(code)
            val expiraEn = ahora.plusMinutes(VIGENCIA_OTP_MINUTOS)
            if (ultimo != null) {
                // Reenvío: nuevo hash, reinicia intentos (P1) y registra el envío (P2).
                codigoRepository.actualizarEnvio(ultimo.idCodigo, codigoHash, expiraEn, ahora)
            } else {
                codigoRepository.insert(
                    CodigoVerificacionRow(
                        idCodigo = 0L, // lo asigna la BD
                        idUsuario = activo.idUsuario,
                        codigoHash = codigoHash,
                        intentosFallidos = 0,
                        expiraEn = expiraEn,
                        ultimoEnvioEn = ahora,
                        usado = false,
                        creadoEn = ahora,
                    ),
                )
            }
        }

        // SMTP FUERA de la transacción: no se mantiene la conexión durante la latencia.
        otpService.send(request.correo, code)
        return PasswordResetResponseDto(MENSAJE_REQUEST)
    }

    /**
     * Paso 2: verifica el OTP y emite el token puente de reseteo (CU-11, C-3).
     *
     * **Anti-enumeración (C-1):** si el correo no tiene una cuenta activa, se verifica el
     * código contra [HASH_DUMMY] → mismo 401 `OTP_INVALID_OR_EXPIRED` y mismo timing que
     * un código incorrecto.
     *
     * **P1:** al fallar el código se persiste el incremento de `intentos_fallidos` DENTRO
     * de la transacción y la excepción se lanza FUERA, para que el contador commitee. Al
     * llegar a 3, [OtpService.verificar] invalida el código permanentemente hasta un nuevo
     * envío (P2).
     *
     * **Single-use (C-3):** un OTP ya usado ([CodigoVerificacionRow.usado]) se rechaza con
     * el mismo 401 genérico. Al verificar con éxito se inserta el `jti` en `tokens_reseteo`
     * dentro de la misma transacción que marca el OTP como usado; la emisión del JWT
     * ocurre FUERA (cálculo puro, no mantiene la transacción).
     */
    fun verificarReseteo(request: PasswordResetVerifyRequestDto): PasswordResetVerifyResponseDto {
        // La lectura del usuario también requiere contexto transaccional de Exposed
        // ("No transaction in context"); el throw del 401 genérico ocurre FUERA del bloque.
        var usuario: UsuarioRow? = null
        transactionRunner.run {
            usuario = usuarioRepository.findByEmail(request.correo)
        }
        val activo = usuario
        if (activo == null || activo.estado != EstadoUsuario.ACTIVO) {
            // Iguala el timing de verificación del camino real; siempre lanza 401 genérico.
            otpService.verificar(request.codigo, HASH_DUMMY, 0, LocalDateTime.now().plusMinutes(VIGENCIA_OTP_MINUTOS))
            throw OtpInvalidException(MENSAJE_INVALIDO)
        }

        var resultado = ResultadoVerificacion.SIN_CODIGO
        var jti: String? = null
        var idUsuario: Long? = null

        transactionRunner.run {
            val codigo = codigoRepository.findUltimoPorUsuarioForUpdate(activo.idUsuario)
            if (codigo == null || codigo.usado) {
                resultado = ResultadoVerificacion.OTP_INVALIDO
                return@run
            }

            try {
                otpService.verificar(request.codigo, codigo.codigoHash, codigo.intentosFallidos, codigo.expiraEn)
            } catch (e: OtpInvalidException) {
                // Se persiste el fallo (P1); la excepción genérica se lanza tras el bloque.
                codigoRepository.actualizarIntentosFallidos(
                    codigo.idCodigo,
                    minOf(codigo.intentosFallidos + 1, OtpService.MAX_INTENTOS_FALLIDOS),
                )
                resultado = ResultadoVerificacion.OTP_INVALIDO
                return@run
            }

            codigoRepository.marcarUsado(codigo.idCodigo)
            val nuevoJti = UUID.randomUUID().toString()
            tokenRepository.insert(
                TokensReseteoRow(
                    idToken = 0L, // lo asigna la BD
                    jti = nuevoJti,
                    idUsuario = activo.idUsuario,
                    expiraEn = LocalDateTime.now().plusMinutes(VIGENCIA_OTP_MINUTOS),
                    consumido = false,
                    creadoEn = LocalDateTime.now(),
                ),
            )
            jti = nuevoJti
            idUsuario = activo.idUsuario
            resultado = ResultadoVerificacion.VERIFICADO
        }

        when (resultado) {
            ResultadoVerificacion.VERIFICADO ->
                return PasswordResetVerifyResponseDto(jwtTokenService.emitirReseteo(idUsuario!!, jti!!))
            ResultadoVerificacion.OTP_INVALIDO -> throw OtpInvalidException(MENSAJE_INVALIDO)
            ResultadoVerificacion.SIN_CODIGO -> throw OtpInvalidException(MENSAJE_INVALIDO)
        }
    }

    /**
     * Paso 3: cambia la contraseña consumiendo el token puente (REQ-FUN-07 CA5).
     *
     * Validaciones en orden:
     * 1. **Token (fuera de transacción):** firma HS256, `iss`, `aud`, claim `purpose` y
     *    vigencia, vía `JWT.require` manual (ARQUITECTURA_BASE.md §2.3, C-3). Cualquier
     *    fallo → 401 `RESET_TOKEN_INVALID` genérico.
     * 2. **Single-use + doble vínculo (C-3, dentro de transacción):** `findByJtiForUpdate`
     *    (`FOR UPDATE`) valida que el token exista, no esté consumido, no esté vencido y
     *    que su `id_usuario` coincida con el `sub` del JWT.
     * 3. **Política de contraseña (C-6):** `PasswordPolicy.validar` contra los datos reales
     *    del usuario → 400 `VALIDATION_ERROR`.
     * 4. **Veto a repetir la anterior (REQ-FUN-07 CA5):** `BCrypt.verify` contra el hash
     *    actual → 409 `PASSWORD_REUSED`.
     *
     * El cambio de hash y la marcación de consumido son atómicos (una sola transacción).
     * El hash bcrypt nuevo se calcula ANTES de la transacción para no retener la conexión.
     */
    fun confirmarReseteo(request: PasswordResetConfirmRequestDto): PasswordResetResponseDto {
        val (idUsuario, jti) = validarTokenPuente(request.resetToken)

        val nuevoHash = otpService.hash(request.nuevaContrasena)
        var resultado = ResultadoConfirmacion.TOKEN_INVALIDO

        transactionRunner.run {
            val token = tokenRepository.findByJtiForUpdate(jti)
            if (token == null ||
                token.consumido ||
                token.expiraEn.isBefore(LocalDateTime.now()) ||
                token.idUsuario != idUsuario
            ) {
                resultado = ResultadoConfirmacion.TOKEN_INVALIDO
                return@run
            }

            val usuario = usuarioRepository.findByIdForUpdate(idUsuario)
            if (usuario == null) {
                resultado = ResultadoConfirmacion.TOKEN_INVALIDO
                return@run
            }

            PasswordPolicy.validar(request.nuevaContrasena, usuario.nombreUsuario, usuario.nombreMenor)

            if (BCrypt.verifyer().verify(request.nuevaContrasena.toCharArray(), usuario.contrasenaHash).verified) {
                resultado = ResultadoConfirmacion.REUTILIZADA
                return@run
            }

            usuarioRepository.actualizarContrasena(usuario.idUsuario, nuevoHash)
            tokenRepository.marcarConsumido(token.idToken)
            resultado = ResultadoConfirmacion.CAMBIADA
        }

        return when (resultado) {
            ResultadoConfirmacion.CAMBIADA -> PasswordResetResponseDto(MENSAJE_CONFIRMADO)
            ResultadoConfirmacion.TOKEN_INVALIDO -> throw ResetTokenInvalidException(MENSAJE_TOKEN_INVALIDO)
            ResultadoConfirmacion.REUTILIZADA ->
                throw PasswordReuseException("La nueva contraseña no puede ser igual a la anterior.")
        }
    }

    /**
     * Valida el token puente de reseteo con la misma configuración con la que se emitió
     * (`JwtConfig` de reseteo). Devuelve el par `(idUsuario, jti)` para el single-use.
     * Cualquier desviación (firma, iss, aud, purpose, exp, malformado) → 401 genérico.
     */
    private fun validarTokenPuente(token: String): Pair<Long, String> {
        val decoded =
            try {
                JWT.require(Algorithm.HMAC256(jwtConfig.secret))
                    .withIssuer(jwtConfig.resetIssuer)
                    .withAudience(jwtConfig.resetAudience)
                    .withClaim("purpose", jwtConfig.resetPurpose)
                    .build()
                    .verify(token)
            } catch (e: JWTVerificationException) {
                throw ResetTokenInvalidException(MENSAJE_TOKEN_INVALIDO)
            }

        val idUsuario = decoded.subject?.toLongOrNull()
        val jti = decoded.id
        if (idUsuario == null || jti == null) {
            throw ResetTokenInvalidException(MENSAJE_TOKEN_INVALIDO)
        }
        return idUsuario to jti
    }
}
