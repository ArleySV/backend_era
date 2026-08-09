package com.era.backend.services

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.era.backend.config.JwtConfig
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * Emisión del token de sesión JWT del Módulo B (Login) — `modulo-b-analisis.md` §6.
 *
 * Responsabilidad única: **emitir** el token tras un login exitoso. Su **validación** la
 * hará el plugin `Authentication(JWT)` en los Módulos D/F/G/H, no esta clase.
 *
 * Seguridad (auditoría #3):
 * - El secreto de firma llega **exclusivamente** por [JwtConfig.secret], resuelto de
 *   `${JWT_SECRET}` en `application.yaml` vía `AppConfigLoader`. No hay secretos
 *   hardcodeados, ni fallback, ni derivación (ARQUITECTURA_BASE.md §5.4 #1).
 * - `jti` único por emisión (`UUID.randomUUID()`), habilita trazabilidad/single-use futuro.
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
}
