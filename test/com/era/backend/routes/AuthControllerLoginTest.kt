package com.era.backend.routes

import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.era.backend.config.JwtConfig
import com.era.backend.controllers.AuthController
import com.era.backend.models.dto.LoginRequestDto
import com.era.backend.models.dto.LoginResponseDto
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Tests HTTP de `POST /api/v1/auth/login` (REQ-FUN-02, CU-04, HU-02). Cubren la primera
 * barrera del controller (400 `VALIDATION_ERROR` con `details`), el mapeo de los errores
 * de negocio (401 genérico, 403, 423) y el flujo feliz (200 con token). Sin MySQL: los
 * services se construyen con fakes.
 */
class AuthControllerLoginTest {

    private fun controller(
        seedUsuario: (FakeUsuarioRepository) -> Unit = {},
    ): AuthController {
        val fakeUsuario = FakeUsuarioRepository()
        seedUsuario(fakeUsuario)
        val fakeRegistro = FakeRegistroPendienteRepository()
        val otpService = OtpService(FakeOtpNotifier())
        val registrationService =
            RegistrationService(fakeRegistro, fakeUsuario, otpService, TransactionRunner { it() })
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
        return AuthController(registrationService, verificationService, loginService, passwordResetService, LogoutService())
    }

    private fun usuario(
        id: Long = 1L,
        correo: String = "laura.perez@example.com",
        username: String = "mariacamila",
        intentos: Int = 0,
        bloqueadoHasta: LocalDateTime? = null,
        estado: EstadoUsuario = EstadoUsuario.ACTIVO,
    ): UsuarioRow =
        UsuarioRow(
            idUsuario = id,
            nombreMenor = "María Camila",
            fechaNacimiento = LocalDate.of(2017, 4, 10),
            correo = correo,
            nombreUsuario = username,
            contrasenaHash = HASH_CONTRASENA,
            avatar = null,
            intentosLoginFallidos = intentos,
            bloqueadoHasta = bloqueadoHasta,
            estado = estado,
            creadoEn = LocalDateTime.now(),
            actualizadoEn = LocalDateTime.now(),
        )

    private fun login(
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
                client.post("/api/v1/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            status = response.status
            texto = response.bodyAsText()
        }
        return checkNotNull(status) to checkNotNull(texto)
    }

    // ── Flujo feliz ───────────────────────────────────────────────────────────────────

    @Test
    fun `login valido responde 200 con token firmado`() {
        val (status, body) =
            login(
                Json.encodeToString(LoginRequestDto("laura.perez@example.com", CONTRASENA)),
                seedUsuario = { it.seed(usuario()) },
            )
        assertEquals(HttpStatusCode.OK, status)
        val respuesta = Json.decodeFromString<LoginResponseDto>(body)
        assertTrue(respuesta.token.isNotBlank())
        val decoded =
            JWT.require(Algorithm.HMAC256(JWT_CONFIG_TEST.secret))
                .withIssuer("era-backend")
                .withAudience("era-app-session")
                .build()
                .verify(respuesta.token)
        assertEquals("1", decoded.subject)
        assertEquals(30 * 24 * 60L, (decoded.expiresAt.time - decoded.issuedAt.time) / 60_000L)
        assertNotNull(decoded.id, "el token debe llevar un jti único")
    }

    @Test
    fun `login por username valido responde 200`() {
        val (status, body) =
            login(
                Json.encodeToString(LoginRequestDto("mariacamila", CONTRASENA)),
                seedUsuario = { it.seed(usuario()) },
            )
        assertEquals(HttpStatusCode.OK, status)
        assertTrue(body.contains("\"token\""))
    }

    // ── Primera barrera: validación de forma (400 VALIDATION_ERROR con details) ───────

    @Test
    fun `identificador vacio responde 400 con details de campo`() {
        val (status, body) =
            login(Json.encodeToString(LoginRequestDto("", CONTRASENA)))
        assertEquals(HttpStatusCode.BadRequest, status)
        assertTrue(body.contains("\"error\":\"VALIDATION_ERROR\""))
        assertTrue(body.contains("\"field\":\"usuarioOCorreo\""))
    }

    @Test
    fun `contrasena vacia responde 400 con details de campo`() {
        val (status, body) =
            login(Json.encodeToString(LoginRequestDto("mariacamila", "")))
        assertEquals(HttpStatusCode.BadRequest, status)
        assertTrue(body.contains("\"error\":\"VALIDATION_ERROR\""))
        assertTrue(body.contains("\"field\":\"contrasena\""))
    }

    @Test
    fun `identificador mayor a 255 responde 400 con details de campo`() {
        val (status, body) =
            login(Json.encodeToString(LoginRequestDto("x".repeat(256), CONTRASENA)))
        assertEquals(HttpStatusCode.BadRequest, status)
        assertTrue(body.contains("\"field\":\"usuarioOCorreo\""))
    }

    @Test
    fun `contrasena mayor a 72 responde 400 con details de campo`() {
        val (status, body) =
            login(Json.encodeToString(LoginRequestDto("mariacamila", "x".repeat(73))))
        assertEquals(HttpStatusCode.BadRequest, status)
        assertTrue(body.contains("\"field\":\"contrasena\""))
    }

    @Test
    fun `cuerpo sin campos obligatorios responde 400 INVALID_REQUEST`() {
        val (status, body) = login("{}")
        assertEquals(HttpStatusCode.BadRequest, status)
        assertTrue(body.contains("\"error\":\"INVALID_REQUEST\""))
    }

    // ── Reglas de negocio a través del HTTP ─────────────────────────────────────────

    @Test
    fun `credenciales incorrectas responde 401 generico sin revelar datos`() {
        val (status, body) =
            login(
                Json.encodeToString(LoginRequestDto("mariacamila", "Clave-Erronea#1")),
                seedUsuario = { it.seed(usuario()) },
            )
        assertEquals(HttpStatusCode.Unauthorized, status)
        assertTrue(body.contains("\"error\":\"INVALID_CREDENTIALS\""))
        assertFalse(body.contains("mariacamila"), "no debe revelar el identificador")
        assertFalse(body.contains("\"field\""), "no debe indicar qué campo falló")
    }

    @Test
    fun `cuenta eliminada con contrasena correcta responde 403 ACCOUNT_INACTIVE`() {
        val (status, body) =
            login(
                Json.encodeToString(LoginRequestDto("laura.perez@example.com", CONTRASENA)),
                seedUsuario = { it.seed(usuario(estado = EstadoUsuario.ELIMINADO)) },
            )
        assertEquals(HttpStatusCode.Forbidden, status)
        assertTrue(body.contains("\"error\":\"ACCOUNT_INACTIVE\""))
    }

    @Test
    fun `cuenta bloqueada responde 423 ACCOUNT_LOCKED`() {
        val (status, body) =
            login(
                Json.encodeToString(LoginRequestDto("mariacamila", CONTRASENA)),
                seedUsuario = { it.seed(usuario(bloqueadoHasta = LocalDateTime.now().plusMinutes(1))) },
            )
        assertEquals(HttpStatusCode.Locked, status)
        assertTrue(body.contains("\"error\":\"ACCOUNT_LOCKED\""))
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

        /** Contraseña de prueba para el hash bcrypt sembrado en el fake. */
        val CONTRASENA = "Trivia#2025"

        /** Hash bcrypt real (coste 8, solo test) para sembrar usuarios activos. */
        val HASH_CONTRASENA: String = BCrypt.withDefaults().hashToString(8, CONTRASENA.toCharArray())
    }
}
