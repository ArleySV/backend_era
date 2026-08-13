package com.era.backend.routes

import com.era.backend.config.JwtConfig
import com.era.backend.controllers.AuthController
import com.era.backend.models.dto.ResendOtpRequestDto
import com.era.backend.models.dto.VerifyEmailRequestDto
import com.era.backend.models.entities.EstadoUsuario
import com.era.backend.models.entities.RegistroPendienteRow
import com.era.backend.models.entities.UsuarioRow
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
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Tests HTTP de `POST /api/v1/auth/verify-email` y `POST /api/v1/auth/resend-otp`
 * (Módulo A.1, REQ-FUN-01 paso 3, CU-11). Cubren la validación de forma del controller
 * (400), el mapeo de errores de negocio (401/409/429) y los flujos felices (200).
 * Sin MySQL: el service se construye con fakes.
 */
class AuthControllerVerificationTest {

    private val otpService = OtpService(FakeOtpNotifier())

    private fun controller(
        seedUsuario: (FakeUsuarioRepository) -> Unit = {},
        seedPendiente: (FakeRegistroPendienteRepository) -> Unit = {},
    ): AuthController {
        val fakeUsuario = FakeUsuarioRepository()
        val fakeRegistro = FakeRegistroPendienteRepository()
        seedUsuario(fakeUsuario)
        seedPendiente(fakeRegistro)
        val otpService = OtpService(FakeOtpNotifier())
        val registrationService =
            RegistrationService(
                fakeRegistro,
                fakeUsuario,
                otpService,
                TransactionRunner { it() },
            )
        val verificationService =
            VerificationService(
                fakeRegistro,
                fakeUsuario,
                FakeAcudienteRepository(),
                FakeConfiguracionRepository(),
                otpService,
                TransactionRunner { it() },
            )
        val loginService =
            LoginService(
                fakeUsuario,
                TransactionRunner { it() },
                JwtTokenService(JWT_CONFIG_TEST),
            )
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
        return AuthController(registrationService, verificationService, loginService, passwordResetService, LogoutService())
    }

    private fun pendiente(
        codigo: String = "123456",
        expiraEn: LocalDateTime = LocalDateTime.now().plusMinutes(5),
        ultimoEnvioEn: LocalDateTime? = LocalDateTime.now().minusMinutes(1),
    ): RegistroPendienteRow =
        RegistroPendienteRow(
            idRegistro = 1L,
            correo = "laura.perez@example.com",
            nombreUsuario = "mariacamila",
            contrasenaHash = "hash-irrelevante",
            nombreMenor = "María Camila",
            fechaNacimiento = LocalDate.of(2017, 4, 10),
            nombreAcudiente = "Laura Pérez",
            cedulaAcudiente = "1032456789",
            avatar = null,
            codigoHash = otpService.hash(codigo),
            intentosFallidos = 0,
            expiraEn = expiraEn,
            ultimoEnvioEn = ultimoEnvioEn,
            creadoEn = LocalDateTime.now().minusHours(1),
        )

    private fun usuarioActivo(): UsuarioRow =
        UsuarioRow(
            idUsuario = 1L,
            nombreMenor = "María Camila",
            fechaNacimiento = LocalDate.of(2017, 4, 10),
            correo = "laura.perez@example.com",
            nombreUsuario = "mariacamila",
            contrasenaHash = "hash-irrelevante",
            avatar = null,
            intentosLoginFallidos = 0,
            bloqueadoHasta = null,
            estado = EstadoUsuario.ACTIVO,
            creadoEn = LocalDateTime.now(),
            actualizadoEn = LocalDateTime.now(),
        )

    private fun postJson(
        path: String,
        body: String,
        seedUsuario: (FakeUsuarioRepository) -> Unit = {},
        seedPendiente: (FakeRegistroPendienteRepository) -> Unit = {},
    ): Pair<HttpStatusCode, String> {
        var status: HttpStatusCode? = null
        var texto: String? = null
        testApplication {
            application {
                configurePlugins()
                configureAuthentication(JWT_CONFIG_TEST)
                routing { authRoutes(controller(seedUsuario = seedUsuario, seedPendiente = seedPendiente)) }
            }
            val response =
                client.post(path) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            status = response.status
            texto = response.bodyAsText()
        }
        return checkNotNull(status) to checkNotNull(texto)
    }

    private fun verificar(
        body: String,
        seedUsuario: (FakeUsuarioRepository) -> Unit = {},
        seedPendiente: (FakeRegistroPendienteRepository) -> Unit = {},
    ) = postJson("/api/v1/auth/verify-email", body, seedUsuario, seedPendiente)

    private fun reenviar(
        body: String,
        seedPendiente: (FakeRegistroPendienteRepository) -> Unit = {},
    ) = postJson("/api/v1/auth/resend-otp", body, seedPendiente = seedPendiente)

    // ── verify-email ─────────────────────────────────────────────────────────────────

    @Test
    fun `verify-email valido responde 200 con mensaje de exito`() {
        val (status, body) =
            verificar(
                Json.encodeToString(VerifyEmailRequestDto("laura.perez@example.com", "123456")),
                seedPendiente = { it.seed(pendiente(codigo = "123456")) },
            )
        assertEquals(HttpStatusCode.OK, status)
        assertTrue(body.contains("\"message\""))
    }

    @Test
    fun `verify-email con codigo incorrecto responde 401 OTP_INVALID_OR_EXPIRED`() {
        val (status, body) =
            verificar(
                Json.encodeToString(VerifyEmailRequestDto("laura.perez@example.com", "000000")),
                seedPendiente = { it.seed(pendiente(codigo = "123456")) },
            )
        assertEquals(HttpStatusCode.Unauthorized, status)
        assertTrue(body.contains("\"error\":\"OTP_INVALID_OR_EXPIRED\""))
    }

    @Test
    fun `verify-email sin pendiente pero con usuario activo responde 409 EMAIL_ALREADY_VERIFIED`() {
        val (status, body) =
            verificar(
                Json.encodeToString(VerifyEmailRequestDto("laura.perez@example.com", "123456")),
                seedUsuario = { it.seed(usuarioActivo()) },
            )
        assertEquals(HttpStatusCode.Conflict, status)
        assertTrue(body.contains("\"error\":\"EMAIL_ALREADY_VERIFIED\""))
    }

    @Test
    fun `verify-email sin pendiente ni usuario responde 404 NOT_FOUND`() {
        val (status, body) =
            verificar(Json.encodeToString(VerifyEmailRequestDto("laura.perez@example.com", "123456")))
        assertEquals(HttpStatusCode.NotFound, status)
        assertTrue(body.contains("\"error\":\"NOT_FOUND\""))
    }

    @Test
    fun `verify-email con codigo malformado responde 400 con details de campo`() {
        val (status, body) =
            verificar(Json.encodeToString(VerifyEmailRequestDto("laura.perez@example.com", "12ab")))
        assertEquals(HttpStatusCode.BadRequest, status)
        assertTrue(body.contains("\"error\":\"VALIDATION_ERROR\""))
        assertTrue(body.contains("\"field\":\"codigo\""))
    }

    @Test
    fun `verify-email con correo malformado responde 400 con details de campo`() {
        val (status, body) =
            verificar(Json.encodeToString(VerifyEmailRequestDto("correo-malformado", "123456")))
        assertEquals(HttpStatusCode.BadRequest, status)
        assertTrue(body.contains("\"error\":\"VALIDATION_ERROR\""))
        assertTrue(body.contains("\"field\":\"correo\""))
    }

    // ── resend-otp ───────────────────────────────────────────────────────────────────

    @Test
    fun `resend-otp permitido responde 200 con mensaje de exito`() {
        val (status, body) =
            reenviar(
                Json.encodeToString(ResendOtpRequestDto("laura.perez@example.com")),
                seedPendiente = { it.seed(pendiente(ultimoEnvioEn = LocalDateTime.now().minusMinutes(2))) },
            )
        assertEquals(HttpStatusCode.OK, status)
        assertTrue(body.contains("\"message\""))
    }

    @Test
    fun `resend-otp antes de 60 s responde 429 OTP_RESEND_THROTTLED`() {
        val (status, body) =
            reenviar(
                Json.encodeToString(ResendOtpRequestDto("laura.perez@example.com")),
                seedPendiente = { it.seed(pendiente(ultimoEnvioEn = LocalDateTime.now().minusSeconds(10))) },
            )
        assertEquals(HttpStatusCode.TooManyRequests, status)
        assertTrue(body.contains("\"error\":\"OTP_RESEND_THROTTLED\""))
    }

    @Test
    fun `resend-otp sin pendiente responde 200 generico (anti-enumeracion)`() {
        val (status, body) =
            reenviar(Json.encodeToString(ResendOtpRequestDto("laura.perez@example.com")))
        assertEquals(HttpStatusCode.OK, status)
        assertTrue(body.contains("\"message\""))
    }

    @Test
    fun `resend-otp con correo malformado responde 400 con details de campo`() {
        val (status, body) =
            reenviar(Json.encodeToString(ResendOtpRequestDto("correo-malformado")))
        assertEquals(HttpStatusCode.BadRequest, status)
        assertTrue(body.contains("\"error\":\"VALIDATION_ERROR\""))
        assertTrue(body.contains("\"field\":\"correo\""))
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
