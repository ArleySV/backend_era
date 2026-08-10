package com.era.backend.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.era.backend.config.JwtConfig
import com.era.backend.exceptions.ErrorDto
import com.era.backend.models.SesionPrincipal
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.request.path
import io.ktor.server.response.respond
import java.time.Instant

/**
 * Instala el proveedor JWT de **sesión** `session-jwt` (Módulos D/E/F/G/H,
 * `modulo-d-analisis.md` §2.2). Es la única pieza que valida los tokens de sesión que
 * emite `JwtTokenService.emitir` (Módulo B).
 *
 * Barreras, en orden:
 * 1. **`verifier`** — firma HS256 + audiencia `sessionAudience` (`era-app-session`). Un
 *    token de reseteo (Módulo C) usa `era-app-reset` y falla aquí con
 *    `AudienceClaimException` **antes** de llegar a `validate` (401).
 * 2. **`validate`** — defensa en profundidad: rechaza (`null`) si el payload contiene el
 *    claim `purpose` (los de sesión nunca lo llevan; los de reseteo llevan
 *    `purpose = PASSWORD_RESET`). Construye [SesionPrincipal] desde `sub.toLongOrNull()`;
 *    `sub` nulo o no numérico → `null` (rechazo).
 * 3. **`challenge`** — responde 401 `UNAUTHORIZED` con el `ErrorDto` estándar (D-6). Sin
 *    él, el 401 del verifier saldría sin cuerpo y rompería el contrato §5.2 de
 *    ARQUITECTURA_BASE (el verifier falla antes de que `StatusPages` traduzca
 *    `DomainException`).
 *
 * Debe instalarse en `Application.module()` **antes** de `routing {}`: un `authenticate`
 * sobre un proveedor no instalado lanza en arranque.
 *
 * Seguridad (CLAUDE.md §6): el secreto llega por [JwtConfig.secret] (env var
 * `${JWT_SECRET}`); nunca se loguea token, claims ni secreto.
 */
fun Application.configureAuthentication(jwtConfig: JwtConfig) {
    install(Authentication) {
        jwt("session-jwt") {
            verifier(
                JWT.require(Algorithm.HMAC256(jwtConfig.secret))
                    .withAudience(jwtConfig.sessionAudience)
                    .build(),
            )
            validate { credential ->
                if (credential.payload.claims.containsKey("purpose")) {
                    null
                } else {
                    credential.payload.subject?.toLongOrNull()?.let { SesionPrincipal(it) }
                }
            }
            challenge { _, _ ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorDto(
                        timestamp = Instant.now().toString(),
                        status = HttpStatusCode.Unauthorized.value,
                        error = "UNAUTHORIZED",
                        message = "Autenticación requerida o token inválido.",
                        path = call.request.path(),
                    ),
                )
            }
        }
    }
}
