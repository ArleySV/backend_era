package com.era.backend.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.era.backend.assertExactKeys
import com.era.backend.config.JwtConfig
import com.era.backend.controllers.AuthController
import com.era.backend.models.dto.MensajeResponseDto
import com.era.backend.plugins.configureAuthentication
import com.era.backend.plugins.configurePlugins
import com.era.backend.repositories.FakeAcudienteRepository
import com.era.backend.repositories.FakeCodigoVerificacionRepository
import com.era.backend.repositories.FakeConfiguracionRepository
import com.era.backend.repositories.FakeRegistroPendienteRepository
import com.era.backend.repositories.FakeTokensReseteoRepository
import com.era.backend.repositories.FakeUsuarioRepository
import com.era.backend.repositories.TransactionRunner
import com.era.backend.services.FakeOtpNotifier
import com.era.backend.services.JwtTokenService
import com.era.backend.services.LoginService
import com.era.backend.services.LogoutService
import com.era.backend.services.OtpService
import com.era.backend.services.PasswordResetService
import com.era.backend.services.RegistrationService
import com.era.backend.services.VerificationService
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * Tests HTTP de `POST /api/v1/auth/logout` (REQ-FUN-04, CU-05, HU-04;
 * `ARQUITECTURA_BASE.md` §5.4 Decisión 2). Cubren la barrera `session-jwt` (el único
 * endpoint de `auth` que la requiere: 401 del challenge y rechazo de tokens de
 * recuperación/purpose), el flujo feliz (200 con `MensajeResponseDto`) y la idempotencia.
 * Sin MySQL: los services se construyen con fakes.
 */
class AuthControllerLogoutTest {

    private fun controller(): AuthController {
        val fakeUsuario = FakeUsuarioRepository()
        val otpService = OtpService(FakeOtpNotifier())
        val registrationService =
            RegistrationService(
                FakeRegistroPendienteRepository(),
                fakeUsuario,
                otpService,
                TransactionRunner { it() },
            )
        val verificationService =
            VerificationService(
                FakeRegistroPendienteRepository(),
                fakeUsuario,
                FakeAcudienteRepository(),
                FakeConfiguracionRepository(),
                otpService,
                TransactionRunner { it() },
            )
        val loginService =
            LoginService(fakeUsuario, TransactionRunner { it() }, JwtTokenService(JWT_CONFIG_TEST))
        val passwordResetService =
            PasswordResetService(
                fakeUsuario,
                FakeCodigoVerificacionRepository(),
                FakeTokensReseteoRepository(),
                otpService,
                JwtTokenService(JWT_CONFIG_TEST),
                JWT_CONFIG_TEST,
                TransactionRunner { it() },
            )
        return AuthController(
            registrationService,
            verificationService,
            loginService,
            passwordResetService,
            LogoutService(),
        )
    }

    private fun logout(
        token: String? = null,
    ): Pair<HttpStatusCode, String> {
        var status: HttpStatusCode? = null
        var texto: String? = null
        testApplication {
            application {
                configurePlugins()
                configureAuthentication(JWT_CONFIG_TEST)
                routing { authRoutes(controller()) }
            }
            val request =
                client.post("/api/v1/auth/logout") {
                    if (token != null) {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                }
            status = request.status
            texto = request.bodyAsText()
        }
        return checkNotNull(status) to checkNotNull(texto)
    }

    // ── Flujo feliz ───────────────────────────────────────────────────────────────────

    @Test
    fun `logout con token de sesion responde 200 con la confirmacion`() {
        val token = JwtTokenService(JWT_CONFIG_TEST).emitir(1L)
        val (status, body) = logout(token)
        assertEquals(HttpStatusCode.OK, status)
        val mensaje = Json.decodeFromString<MensajeResponseDto>(body)
        assertEquals("Sesión cerrada.", mensaje.message)
        assertExactKeys(body, "" to setOf("message"))
    }

    @Test
    fun `logout no requiere body y no expone datos en la respuesta`() {
        val token = JwtTokenService(JWT_CONFIG_TEST).emitir(1L)
        val (status, body) = logout(token)
        assertEquals(HttpStatusCode.OK, status)
        assertFalse(body.contains("idUsuario"), "el id del usuario no se expone")
        assertFalse(body.contains("\"token\""), "el token no se expone en la respuesta")
        assertExactKeys(body, "" to setOf("message"))
    }

    @Test
    fun `logout es idempotente`() {
        val token = JwtTokenService(JWT_CONFIG_TEST).emitir(7L)
        val (status1, body1) = logout(token)
        val (status2, body2) = logout(token)
        assertEquals(HttpStatusCode.OK, status1)
        assertEquals(HttpStatusCode.OK, status2)
        assertEquals(body1, body2)
    }

    // ── Barrera de autenticación (session-jwt) ────────────────────────────────────────

    @Test
    fun `logout sin token responde 401 UNAUTHORIZED`() {
        val (status, body) = logout()
        assertEquals(HttpStatusCode.Unauthorized, status)
        assertTrue(body.contains("\"error\":\"UNAUTHORIZED\""), "el challenge debe usar el ErrorDto")
        assertTrue(body.contains("\"status\":401"))
    }

    @Test
    fun `logout con token de reseteo responde 401 UNAUTHORIZED`() {
        val resetToken =
            JwtTokenService(JWT_CONFIG_TEST)
                .emitirReseteo(1L, jti = "jti-de-reseteo")
        val (status, body) = logout(resetToken)
        assertEquals(HttpStatusCode.Unauthorized, status)
        assertTrue(body.contains("\"error\":\"UNAUTHORIZED\""))
    }

    @Test
    fun `logout con token de sesion con claim purpose responde 401 UNAUTHORIZED`() {
        // Defensa en profundidad (validate de `session-jwt`): un token con audiencia de
        // sesión pero claim `purpose` (los de reseteo lo llevan) debe rechazarse.
        val tokenConPurpose =
            JWT.create()
                .withIssuer("era-backend")
                .withSubject("1")
                .withAudience(JWT_CONFIG_TEST.sessionAudience)
                .withClaim("purpose", JWT_CONFIG_TEST.resetPurpose)
                .withJWTId("jti-con-purpose")
                .withIssuedAt(Date.from(Instant.now()))
                .withExpiresAt(Date.from(Instant.now().plusSeconds(3600)))
                .sign(Algorithm.HMAC256(JWT_CONFIG_TEST.secret))
        val (status, body) = logout(tokenConPurpose)
        assertEquals(HttpStatusCode.Unauthorized, status)
        assertTrue(body.contains("\"error\":\"UNAUTHORIZED\""))
    }

    companion object {
        /** Config JWT de test: secreto dummy, solo para firmar tokens en los tests HTTP. */
        val JWT_CONFIG_TEST =
            JwtConfig(
                secret = "test-secret",
                sessionIssuer = "era-backend",
                sessionAudience = "era-app-session",
                sessionExpirationMinutes = 43200,
                resetIssuer = "era-backend",
                resetAudience = "era-app-reset",
                resetTtlMinutes = 10,
                resetPurpose = "PASSWORD_RESET",
            )
    }
}
