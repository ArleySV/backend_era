package com.era.backend.routes

import com.era.backend.assertExactKeys
import com.era.backend.config.JwtConfig
import com.era.backend.controllers.AuthController
import com.era.backend.models.dto.RegisterRequestDto
import com.era.backend.models.entities.EstadoUsuario
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
 * Tests HTTP de `POST /api/v1/auth/register` (REQ-FUN-01, CU-01). Cubren la primera
 * barrera del controller (validación de forma → 400 `VALIDATION_ERROR` con `details`),
 * el mapeo de errores de negocio (409) y el flujo feliz (201). Sin MySQL: el service se
 * construye con fakes.
 */
class AuthControllerTest {

    private val dtoValido =
        RegisterRequestDto(
            nombreMenor = "María Camila",
            fechaNacimiento = "2017-04-10",
            nombreAcudiente = "Laura Pérez",
            cedulaAcudiente = "1032456789",
            correo = "laura.perez@example.com",
            nombreUsuario = "mariacamila",
            avatar = "preset:1",
            contrasena = "Trivia#2025",
            confirmarContrasena = "Trivia#2025",
        )

    private fun controller(
        seedUsuario: (FakeUsuarioRepository) -> Unit = {},
        seedPendiente: (FakeRegistroPendienteRepository) -> Unit = {},
    ): AuthController {
        val fakeUsuario = FakeUsuarioRepository()
        val fakeRegistro = FakeRegistroPendienteRepository()
        seedUsuario(fakeUsuario)
        seedPendiente(fakeRegistro)
        val otpService = OtpService(FakeOtpNotifier())
        val service =
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
        return AuthController(service, verificationService, loginService, passwordResetService, LogoutService())
    }

    private fun usuarioActivo(correo: String = "laura.perez@example.com"): UsuarioRow =
        UsuarioRow(
            idUsuario = 1L,
            nombreMenor = "María Camila",
            fechaNacimiento = LocalDate.of(2017, 4, 10),
            correo = correo,
            nombreUsuario = "mariacamila",
            contrasenaHash = "hash-irrelevante",
            avatar = null,
            intentosLoginFallidos = 0,
            bloqueadoHasta = null,
            estado = EstadoUsuario.ACTIVO,
            creadoEn = LocalDateTime.now(),
            actualizadoEn = LocalDateTime.now(),
        )

    private fun registrar(
        body: String,
        seedUsuario: (FakeUsuarioRepository) -> Unit = {},
    ): Pair<HttpStatusCode, String> {
        var status: HttpStatusCode? = null
        var texto: String? = null
        testApplication {
            application {
                configurePlugins()
                configureAuthentication(JWT_CONFIG_TEST)
                routing { authRoutes(controller(seedUsuario = seedUsuario)) }
            }
            val response =
                client.post("/api/v1/auth/register") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            status = response.status
            texto = response.bodyAsText()
        }
        return checkNotNull(status) to checkNotNull(texto)
    }

    @Test
    fun `registro valido responde 201 con mensaje de exito`() {
        val (status, body) = registrar(Json.encodeToString(dtoValido))
        assertEquals(HttpStatusCode.Created, status)
        assertTrue(body.contains("\"message\""))
        assertTrue(body.contains("verificación"))
        assertExactKeys(body, "" to setOf("message"))
    }

    // ── Primera barrera: validación de forma (400 VALIDATION_ERROR con details) ─────

    @Test
    fun `correo malformado responde 400 con details de campo`() {
        val dto = dtoValido.copy(correo = "correo-malformado")
        val (status, body) = registrar(Json.encodeToString(dto))
        assertEquals(HttpStatusCode.BadRequest, status)
        assertTrue(body.contains("\"error\":\"VALIDATION_ERROR\""))
        assertTrue(body.contains("\"field\":\"correo\""))
    }

    @Test
    fun `fecha de nacimiento futura responde 400 con details de campo`() {
        val dto = dtoValido.copy(fechaNacimiento = "2100-01-01")
        val (status, body) = registrar(Json.encodeToString(dto))
        assertEquals(HttpStatusCode.BadRequest, status)
        assertTrue(body.contains("\"error\":\"VALIDATION_ERROR\""))
        assertTrue(body.contains("\"field\":\"fechaNacimiento\""))
    }

    @Test
    fun `cedula corta responde 400 con details de campo`() {
        val dto = dtoValido.copy(cedulaAcudiente = "123")
        val (status, body) = registrar(Json.encodeToString(dto))
        assertEquals(HttpStatusCode.BadRequest, status)
        assertTrue(body.contains("\"error\":\"VALIDATION_ERROR\""))
        assertTrue(body.contains("\"field\":\"cedulaAcudiente\""))
    }

    @Test
    fun `avatar invalido responde 400 con details de campo`() {
        val dto = dtoValido.copy(avatar = "preset:99")
        val (status, body) = registrar(Json.encodeToString(dto))
        assertEquals(HttpStatusCode.BadRequest, status)
        assertTrue(body.contains("\"error\":\"VALIDATION_ERROR\""))
        assertTrue(body.contains("\"field\":\"avatar\""))
    }

    @Test
    fun `confirmacion distinta responde 400 con details de campo`() {
        val dto = dtoValido.copy(confirmarContrasena = "Otra#2025")
        val (status, body) = registrar(Json.encodeToString(dto))
        assertEquals(HttpStatusCode.BadRequest, status)
        assertTrue(body.contains("\"error\":\"VALIDATION_ERROR\""))
        assertTrue(body.contains("\"field\":\"confirmarContrasena\""))
    }

    @Test
    fun `username con espacios responde 400 con details de campo`() {
        val dto = dtoValido.copy(nombreUsuario = "maria camila")
        val (status, body) = registrar(Json.encodeToString(dto))
        assertEquals(HttpStatusCode.BadRequest, status)
        assertTrue(body.contains("\"error\":\"VALIDATION_ERROR\""))
        assertTrue(body.contains("\"field\":\"nombreUsuario\""))
    }

    @Test
    fun `cuerpo sin campo obligatorio responde 400 INVALID_REQUEST`() {
        val json = """{"nombreMenor":"María Camila","fechaNacimiento":"2017-04-10"}"""
        val (status, body) = registrar(json)
        assertEquals(HttpStatusCode.BadRequest, status)
        assertTrue(body.contains("\"error\":\"INVALID_REQUEST\""))
    }

    // ── Reglas de negocio a través del HTTP ─────────────────────────────────────────

    @Test
    fun `correo ya registrado responde 409 EMAIL_ALREADY_REGISTERED`() {
        val (status, body) =
            registrar(Json.encodeToString(dtoValido), seedUsuario = { it.seed(usuarioActivo()) })
        assertEquals(HttpStatusCode.Conflict, status)
        assertTrue(body.contains("\"error\":\"EMAIL_ALREADY_REGISTERED\""))
    }

    @Test
    fun `correo de cuenta eliminada responde 409 EMAIL_LOCKED`() {
        val (status, body) =
            registrar(
                Json.encodeToString(dtoValido),
                seedUsuario = { it.seed(usuarioActivo().copy(estado = EstadoUsuario.ELIMINADO)) },
            )
        assertEquals(HttpStatusCode.Conflict, status)
        assertTrue(body.contains("\"error\":\"EMAIL_LOCKED\""))
    }

    @Test
    fun `username en uso responde 409 CONFLICT`() {
        val (status, body) =
            registrar(
                Json.encodeToString(dtoValido),
                seedUsuario = { it.seed(usuarioActivo(correo = "otra.persona@example.com")) },
            )
        assertEquals(HttpStatusCode.Conflict, status)
        assertTrue(body.contains("\"error\":\"CONFLICT\""))
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
