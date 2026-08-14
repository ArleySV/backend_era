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
 *
 * **Modo determinista (solo dev, V10/V11):** cuando [otpDeterminista] es `true`, `generate()`
 * devuelve siempre `"123456"`. El flag lo activa `Application.module()` únicamente cuando
 * `APP_DEV_MODE=true` (`AppConfig.devMode`, default `false`), así que la producción y los
 * tests siguen usando `SecureRandom`.
 *
 * ⚠ **VITAL para la Base de Trazabilidad de Calidad (V11):** la lógica de APP_DEV_MODE
 * sostiene el smoke test E2E (`scripts/smoke_test.ps1`, OTP fijo `"123456"`). NO eliminar
 * ni renombrar en refactorizaciones futuras.
 */
class OtpService(
    private val notifier: OtpNotifier,
    private val otpDeterminista: Boolean = false,
) {

    private val secureRandom = SecureRandom()

    companion object {
        /**
         * Coste de bcrypt para el hash de los **códigos OTP** (hash de un solo sentido,
         * HU-15 CA3). Se mantiene en 12: el OTP es un secreto de baja entropía (6 dígitos) y
         * estos caminos están throttled (P1/P2), no son vector de DoS.
         */
        private const val COSTE_BCRYPT = 12

        /**
         * Coste de bcrypt para las **contraseñas** de usuario (registro y reset, REQ-FUN-01/07).
         * Bajado de 12 a 11 (2026-08-13, decisión del propietario): mitiga el DoS por
         * saturación del pool/event-loop del login manteniendo el rango OWASP >=10-12. Los
         * hashes legacy coste 12 siguen verificando (bcrypt embebe el coste en el propio hash).
         */
        const val COSTE_BCRYPT_PASSWORD = 11

        /** Política P1: máx. 3 intentos fallidos consecutivos; al superarlos, el código queda invalidado. */
        const val MAX_INTENTOS_FALLIDOS = 3

        /** Mensaje genérico: no revela si el código era incorrecto, vencido o agotado (ARQUITECTURA_BASE.md §5.3). */
        private const val MENSAJE_INVALIDO = "El código de verificación es inválido o ha expirado."
    }

    /**
     * Genera un OTP de 6 dígitos numéricos con `SecureRandom` (REQ-FUN-01 CA4). En modo
     * determinista (dev, V10) devuelve siempre `"123456"` para que el smoke test de humo
     * no dependa de leer el correo; nunca se usa en producción.
     */
    fun generate(): String {
        if (otpDeterminista) return "123456"
        return "%06d".format(secureRandom.nextInt(1_000_000))
    }

    /**
     * Hashea el código con bcrypt (hash de un solo sentido, HU-15 CA3). El código nunca
     * se persiste ni se loguea en texto plano (CLAUDE.md §6).
     */
    fun hash(code: String): String = BCrypt.withDefaults().hashToString(COSTE_BCRYPT, code.toCharArray())

    /**
     * Hashea una **contraseña** de usuario (registro y reset) con [COSTE_BCRYPT_PASSWORD] (11),
     * distinto del coste del OTP (12): el coste de la contraseña es el que paga cada login y
     * por eso es el que se ajusta para mitigar el DoS. Nunca se loguea la contraseña.
     */
    fun hashContrasena(contrasena: String): String =
        BCrypt.withDefaults().hashToString(COSTE_BCRYPT_PASSWORD, contrasena.toCharArray())

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
