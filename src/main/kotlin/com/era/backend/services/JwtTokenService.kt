package com.era.backend.services

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.era.backend.config.JwtConfig
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * Emisión de tokens JWT HS256 del backend: sesión (Módulo B) y reseteo (Módulo C).
 *
 * Responsabilidad única: **emitir** tokens. Su **validación** difiere por tipo:
 * - Sesión: la hará el plugin `Authentication(JWT)` en los Módulos D/F/G/H.
 * - Reseteo: la hará manualmente el service del Módulo C (ARQUITECTURA_BASE.md §4;
 *   `modulo-c-analisis.md` C-3), pues usa `aud`/`purpose` propios y debe además
 *   verificar `tokens_reseteo` (single-use + doble vínculo con `id_usuario`).
 *
 * Seguridad (auditoría #3):
 * - El secreto de firma llega **exclusivamente** por [JwtConfig.secret], resuelto de
 *   `${JWT_SECRET}` en `application.yaml` vía `AppConfigLoader`. No hay secretos
 *   hardcodeados, ni fallback, ni derivación (ARQUITECTURA_BASE.md §5.4 #1).
 * - `jti` único por emisión; en sesión se genera aquí (`UUID.randomUUID()`) y en
 *   reseteo lo provee el service (debe persistirse en `tokens_reseteo` para el
 *   single-use). Habilita trazabilidad y revocación.
 * - El token no lleva correo, cédula, fecha de nacimiento ni datos del menor (mínimo
 *   privilegio, CLAUDE.md §6).
 * - **Zero logs:** nunca se loguea el token, su contenido ni el secreto.
 */
class JwtTokenService(
    private val jwtConfig: JwtConfig,
) {

    /**
     * Emite un token de sesión HS256 de vida larga (30 días, `sessionExpirationMinutes`).
     *
     * Claims (modulo-b-analisis.md §6):
     * - `sub`: `id_usuario` del menor autenticado.
     * - `iss` / `aud`: `sessionIssuer` (`era-backend`) / `sessionAudience`
     *   (`era-app-session`) desde configuración.
     * - `iat`: now · `exp`: now + `sessionExpirationMinutes` · `jti`: UUID único por emisión.
     *
     * @return el JWT firmado listo para enviar al cliente en `LoginResponseDto.token`.
     */
    fun emitir(idUsuario: Long): String {
        val ahora = Instant.now()
        val expira = ahora.plusMillis(jwtConfig.sessionExpirationMinutes * 60_000L)

        return JWT.create()
            .withSubject(idUsuario.toString())
            .withIssuer(jwtConfig.sessionIssuer)
            .withAudience(jwtConfig.sessionAudience)
            .withIssuedAt(Date.from(ahora))
            .withExpiresAt(Date.from(expira))
            .withJWTId(UUID.randomUUID().toString())
            .sign(Algorithm.HMAC256(jwtConfig.secret))
    }

    /**
     * Emite el token puente de reseteo de contraseña HS256 de corta vida
     * (10 min, `resetTtlMinutes`), single-use (Módulo C, paso 2 → paso 3).
     *
     * Claims (`modulo-c-analisis.md` C-3):
     * - `sub`: `id_usuario` cuya contraseña se va a renovar.
     * - `iss` / `aud`: `resetIssuer` / `resetAudience` desde configuración, distintos de
     *   los de sesión para que un token de reseteo jamás valga como sesión y viceversa.
     * - `purpose`: `resetPurpose` (`PASSWORD_RESET`) — claim explícito de propósito.
     * - `iat`: now · `exp`: now + `resetTtlMinutes` · `jti`: pasado por el service
     *   (el service lo persiste en `tokens_reseteo` para el single-use).
     *
     * @param jti identificador único del token; el service del Módulo C lo genera y lo
     *   persiste en `tokens_reseteo` (doble vínculo con `id_usuario`, C-3).
     * @return el JWT firmado para `PasswordResetVerifyResponseDto.resetToken`.
     */
    fun emitirReseteo(idUsuario: Long, jti: String): String {
        val ahora = Instant.now()
        val expira = ahora.plusMillis(jwtConfig.resetTtlMinutes * 60_000L)

        return JWT.create()
            .withSubject(idUsuario.toString())
            .withIssuer(jwtConfig.resetIssuer)
            .withAudience(jwtConfig.resetAudience)
            .withClaim("purpose", jwtConfig.resetPurpose)
            .withIssuedAt(Date.from(ahora))
            .withExpiresAt(Date.from(expira))
            .withJWTId(jti)
            .sign(Algorithm.HMAC256(jwtConfig.secret))
    }
}
