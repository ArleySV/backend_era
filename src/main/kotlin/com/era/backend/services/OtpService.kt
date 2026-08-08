package com.era.backend.services

import at.favre.lib.crypto.bcrypt.BCrypt
import com.era.backend.exceptions.OtpInvalidException
import java.security.SecureRandom
import java.time.LocalDateTime

/**
 * Gestión del código OTP de 6 dígitos, reutilizable entre el Módulo A (registro, A.1
 * `verify-email`/`resend-otp`) y el C (recuperación de contraseña) (`modulo-a-analisis.md`
 * §5). Genera, hashea, envía y verifica el código. La política de intentos fallidos (P1,
 * ARQUITECTURA_BASE.md §5.4 #3) vive aquí, no en el repositorio.
 */
class OtpService(private val notifier: OtpNotifier) {

    private val secureRandom = SecureRandom()

    companion object {
        /** Coste de bcrypt para el hash del código y de la contraseña (hash de un solo sentido, HU-15 CA3). */
        private const val COSTE_BCRYPT = 12

        /** Política P1: máx. 3 intentos fallidos consecutivos; al superarlos, el código queda invalidado. */
        private const val MAX_INTENTOS_FALLIDOS = 3

        /** Mensaje genérico: no revela si el código era incorrecto, vencido o agotado (ARQUITECTURA_BASE.md §5.3). */
        private const val MENSAJE_INVALIDO = "El código de verificación es inválido o ha expirado."
    }

    /**
     * Genera un OTP de 6 dígitos numéricos con `SecureRandom` (REQ-FUN-01 CA4).
     */
    fun generate(): String = "%06d".format(secureRandom.nextInt(1_000_000))

    /**
     * Hashea el código con bcrypt (hash de un solo sentido, HU-15 CA3). El código nunca
     * se persiste ni se loguea en texto plano (CLAUDE.md §6).
     */
    fun hash(code: String): String = BCrypt.withDefaults().hashToString(COSTE_BCRYPT, code.toCharArray())

    /**
     * Delega el envío del código en [OtpNotifier] (CU-11). Sin loguear código ni correo.
     */
    fun send(correo: String, code: String) {
        notifier.send(correo, code)
    }

    /**
     * Verifica el código contra su hash bcrypt [codeHash]: valida coincidencia, vigencia de
     * 10 minutos (`expiraEn`) y contador de intentos fallidos (máx. 3 consecutivos, P1).
     * Toda falla lanza [OtpInvalidException] con mensaje genérico, sin revelar la causa
     * (código incorrecto, vencido o límite superado) — ARQUITECTURA_BASE.md §5.3.
     *
     * @param code código ingresado por el usuario.
     * @param codeHash hash bcrypt persistido del OTP emitido.
     * @param intentosFallidos intentos fallidos acumulados antes de este intento.
     * @param expiraEn vigencia del código (`now + 10 min`).
     */
    fun verificar(code: String, codeHash: String, intentosFallidos: Int, expiraEn: LocalDateTime) {
        if (expiraEn.isBefore(LocalDateTime.now())) {
            throw OtpInvalidException(MENSAJE_INVALIDO)
        }
        if (intentosFallidos >= MAX_INTENTOS_FALLIDOS) {
            throw OtpInvalidException(MENSAJE_INVALIDO)
        }
        if (!BCrypt.verifyer().verify(code.toCharArray(), codeHash).verified) {
            throw OtpInvalidException(MENSAJE_INVALIDO)
        }
    }
}
