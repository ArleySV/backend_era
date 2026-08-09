package com.era.backend.routes

import at.favre.lib.crypto.bcrypt.BCrypt
import com.era.backend.config.JwtConfig
import com.era.backend.controllers.AuthController
import com.era.backend.models.dto.LoginRequestDto
import com.era.backend.models.dto.LoginResponseDto
import com.era.backend.models.dto.PasswordResetConfirmRequestDto
import com.era.backend.models.dto.PasswordResetRequestDto
import com.era.backend.models.dto.PasswordResetVerifyRequestDto
import com.era.backend.models.dto.PasswordResetVerifyResponseDto
import com.era.backend.models.entities.CodigoVerificacionRow
import com.era.backend.models.entities.EstadoUsuario
import com.era.backend.models.entities.TokensReseteoRow
import com.era.backend.models.entities.UsuarioRow
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
import com.era.backend.services.OtpService
import com.era.backend.services.PasswordResetService
import com.era.backend.services.RegistrationService
import com.era.backend.services.VerificationService
import io.ktor.client.HttpClient
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
 * Tests HTTP de los endpoints `POST /api/v1/auth/password-reset/request|verify|confirm`
 * (Módulo C, REQ-FUN-07, CU-03, HU-07). Cubren la primera barrera del controller
 * (400 `VALIDATION_ERROR` con `details`), el mapeo de los errores de negocio
 * (401 genérico, 409, 429), el single-use del token puente (C-3), el veto a repetir la
 * contraseña anterior (CA5) y el flujo feliz E2E que termina en `/login` (Módulo B).
 * Sin MySQL: los services se construyen con fakes en memoria.
 */
class AuthControllerPasswordResetTest {

    private fun controller(
        seedUsuario: (FakeUsuarioRepository) -> Unit = {},
        seedCodigos: (FakeCodigoVerificacionRepository) -> Unit = {},
        seedTokens: (FakeTokensReseteoRepository) -> Unit = {},
    ): AuthController {
        val fakeUsuario = FakeUsuarioRepository()
        seedUsuario(fakeUsuario)
        val fakeCodigos = FakeCodigoVerificacionRepository()
        seedCodigos(fakeCodigos)
        val fakeTokens = FakeTokensReseteoRepository()
        seedTokens(fakeTokens)
        val otpService = OtpService(FakeOtpNotifier(), otpDeterminista = true)
        val registrationService =
            RegistrationService(FakeRegistroPendienteRepository(), fakeUsuario, otpService, TransactionRunner { it() })
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
                fakeCodigos,
                fakeTokens,
                otpService,
                JwtTokenService(JWT_CONFIG_TEST),
                JWT_CONFIG_TEST,
                TransactionRunner { it() },
            )
        return AuthController(registrationService, verificationService, loginService, passwordResetService)
    }

    private fun usuario(
        id: Long = 1L,
        correo: String = "laura.perez@example.com",
        hash: String = HASH_CONTRASENA,
        estado: EstadoUsuario = EstadoUsuario.ACTIVO,
    ): UsuarioRow =
        UsuarioRow(
            idUsuario = id,
            nombreMenor = "María Camila",
            fechaNacimiento = LocalDate.of(2017, 4, 10),
            correo = correo,
            nombreUsuario = "mariacamila",
            contrasenaHash = hash,
            avatar = null,
            intentosLoginFallidos = 0,
            bloqueadoHasta = null,
            estado = estado,
            creadoEn = LocalDateTime.now().minusDays(10),
            actualizadoEn = LocalDateTime.now().minusDays(10),
        )

    private fun codigo(
        id: Long = 1L,
        idUsuario: Long = 1L,
        hash: String = HASH_CODIGO,
        intentosFallidos: Int = 0,
        expiraEn: LocalDateTime = LocalDateTime.now().plusMinutes(10),
        ultimoEnvioEn: LocalDateTime? = LocalDateTime.now().minusMinutes(1),
        usado: Boolean = false,
    ): CodigoVerificacionRow =
        CodigoVerificacionRow(
            idCodigo = id,
            idUsuario = idUsuario,
            codigoHash = hash,
            intentosFallidos = intentosFallidos,
            expiraEn = expiraEn,
            ultimoEnvioEn = ultimoEnvioEn,
            usado = usado,
            creadoEn = LocalDateTime.now().minusMinutes(5),
        )

    private fun token(
        jti: String = "jti-test",
        idUsuario: Long = 1L,
        consumido: Boolean = false,
    ): TokensReseteoRow =
        TokensReseteoRow(
            idToken = 1L,
            jti = jti,
            idUsuario = idUsuario,
            expiraEn = LocalDateTime.now().plusMinutes(10),
            consumido = consumido,
            creadoEn = LocalDateTime.now().minusMinutes(1),
        )

    /** Token puente real firmado con el secreto de test; su `jti` debe estar sembrado en el fake. */
    private fun tokenPuente(jti: String = "jti-test"): String =
        JwtTokenService(JWT_CONFIG_TEST).emitirReseteo(1L, jti)

    private fun enAplicacion(
        seedUsuario: (FakeUsuarioRepository) -> Unit = {},
        seedCodigos: (FakeCodigoVerificacionRepository) -> Unit = {},
        seedTokens: (FakeTokensReseteoRepository) -> Unit = {},
        bloque: suspend (HttpClient) -> Unit,
    ) {
        testApplication {
            application {
                configurePlugins()
                routing {
                    authRoutes(controller(seedUsuario = seedUsuario, seedCodigos = seedCodigos, seedTokens = seedTokens))
                }
            }
            bloque(client)
        }
    }

    private suspend fun HttpClient.postJson(path: String, body: String): Pair<HttpStatusCode, String> {
        val response =
            post(path) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        return response.status to response.bodyAsText()
    }

    private fun solicitar(
        body: String,
        seedUsuario: (FakeUsuarioRepository) -> Unit = {},
        seedCodigos: (FakeCodigoVerificacionRepository) -> Unit = {},
    ): Pair<HttpStatusCode, String> {
        var status: HttpStatusCode? = null
        var texto: String? = null
        enAplicacion(seedUsuario = seedUsuario, seedCodigos = seedCodigos) { client ->
            val r = client.postJson("/api/v1/auth/password-reset/request", body)
            status = r.first
            texto = r.second
        }
        return checkNotNull(status) to checkNotNull(texto)
    }

    private fun verificar(
        body: String,
        seedUsuario: (FakeUsuarioRepository) -> Unit = {},
        seedCodigos: (FakeCodigoVerificacionRepository) -> Unit = {},
    ): Pair<HttpStatusCode, String> {
        var status: HttpStatusCode? = null
        var texto: String? = null
        enAplicacion(seedUsuario = seedUsuario, seedCodigos = seedCodigos) { client ->
            val r = client.postJson("/api/v1/auth/password-reset/verify", body)
            status = r.first
            texto = r.second
        }
        return checkNotNull(status) to checkNotNull(texto)
    }

    private fun confirmar(
        body: String,
        seedUsuario: (FakeUsuarioRepository) -> Unit = {},
        seedTokens: (FakeTokensReseteoRepository) -> Unit = {},
    ): Pair<HttpStatusCode, String> {
        var status: HttpStatusCode? = null
        var texto: String? = null
        enAplicacion(seedUsuario = seedUsuario, seedTokens = seedTokens) { client ->
            val r = client.postJson("/api/v1/auth/password-reset/confirm", body)
            status = r.first
            texto = r.second
        }
        return checkNotNull(status) to checkNotNull(texto)
    }

    // ── request (paso 1) ──────────────────────────────────────────────────────────────

    @Test
    fun `request con correo registrado responde 200 con mensaje generico`() {
        val (status, body) =
            solicitar(
                Json.encodeToString(PasswordResetRequestDto("laura.perez@example.com")),
                seedUsuario = { it.seed(usuario()) },
            )
        assertEquals(HttpStatusCode.OK, status)
        assertTrue(body.contains("\"message\""))
        assertTrue(!body.contains("laura.perez"), "no debe repetir el correo en la respuesta")
    }

    @Test
    fun `request con correo inexistente responde 200 generico identico (anti-enumeracion)`() {
        val (status, body) = solicitar(Json.encodeToString(PasswordResetRequestDto("no.existe@example.com")))
        assertEquals(HttpStatusCode.OK, status)
        assertTrue(body.contains("\"message\""))
    }

    @Test
    fun `request con correo malformado responde 400 con details de campo`() {
        val (status, body) = solicitar(Json.encodeToString(PasswordResetRequestDto("correo-malformado")))
        assertEquals(HttpStatusCode.BadRequest, status)
        assertTrue(body.contains("\"error\":\"VALIDATION_ERROR\""))
        assertTrue(body.contains("\"field\":\"correo\""))
    }

    @Test
    fun `request con correo vacio responde 400 con details de campo`() {
        val (status, body) = solicitar(Json.encodeToString(PasswordResetRequestDto("")))
        assertEquals(HttpStatusCode.BadRequest, status)
        assertTrue(body.contains("\"field\":\"correo\""))
    }

    @Test
    fun `request antes de 60 s responde 429 OTP_RESEND_THROTTLED`() {
        val (status, body) =
            solicitar(
                Json.encodeToString(PasswordResetRequestDto("laura.perez@example.com")),
                seedUsuario = { it.seed(usuario()) },
                seedCodigos = { it.seed(codigo(ultimoEnvioEn = LocalDateTime.now())) },
            )
        assertEquals(HttpStatusCode.TooManyRequests, status)
        assertTrue(body.contains("\"error\":\"OTP_RESEND_THROTTLED\""))
    }

    // ── verify (paso 2) ───────────────────────────────────────────────────────────────

    @Test
    fun `verify con codigo correcto responde 200 con token puente`() {
        val (status, body) =
            verificar(
                Json.encodeToString(PasswordResetVerifyRequestDto("laura.perez@example.com", "123456")),
                seedUsuario = { it.seed(usuario()) },
                seedCodigos = { it.seed(codigo()) },
            )
        assertEquals(HttpStatusCode.OK, status)
        assertTrue(body.contains("\"resetToken\""))
    }

    @Test
    fun `verify con codigo incorrecto responde 401 OTP_INVALID_OR_EXPIRED`() {
        val (status, body) =
            verificar(
                Json.encodeToString(PasswordResetVerifyRequestDto("laura.perez@example.com", "999999")),
                seedUsuario = { it.seed(usuario()) },
                seedCodigos = { it.seed(codigo()) },
            )
        assertEquals(HttpStatusCode.Unauthorized, status)
        assertTrue(body.contains("\"error\":\"OTP_INVALID_OR_EXPIRED\""))
    }

    @Test
    fun `verify con correo inexistente responde 401 generico (anti-enumeracion)`() {
        val (status, body) =
            verificar(Json.encodeToString(PasswordResetVerifyRequestDto("no.existe@example.com", "123456")))
        assertEquals(HttpStatusCode.Unauthorized, status)
        assertTrue(body.contains("\"error\":\"OTP_INVALID_OR_EXPIRED\""))
        assertTrue(!body.contains("resetToken"), "no debe emitirse token para un correo inexistente")
    }

    @Test
    fun `verify con codigo malformado responde 400 con details de campo`() {
        val (status, body) =
            verificar(Json.encodeToString(PasswordResetVerifyRequestDto("laura.perez@example.com", "12ab")))
        assertEquals(HttpStatusCode.BadRequest, status)
        assertTrue(body.contains("\"field\":\"codigo\""))
    }

    // ── confirm (paso 3) ──────────────────────────────────────────────────────────────

    @Test
    fun `confirm con token valido responde 200 y cambia la contrasena`() {
        val (status, body) =
            confirmar(
                Json.encodeToString(
                    PasswordResetConfirmRequestDto(tokenPuente(), NUEVA_CONTRASENA, NUEVA_CONTRASENA),
                ),
                seedUsuario = { it.seed(usuario()) },
                seedTokens = { it.seed(token()) },
            )
        assertEquals(HttpStatusCode.OK, status)
        assertTrue(body.contains("\"message\""))
    }

    @Test
    fun `confirm con token invalido responde 401 RESET_TOKEN_INVALID`() {
        val (status, body) =
            confirmar(
                Json.encodeToString(
                    PasswordResetConfirmRequestDto("token-falsificado", NUEVA_CONTRASENA, NUEVA_CONTRASENA),
                ),
                seedUsuario = { it.seed(usuario()) },
                seedTokens = { it.seed(token()) },
            )
        assertEquals(HttpStatusCode.Unauthorized, status)
        assertTrue(body.contains("\"error\":\"RESET_TOKEN_INVALID\""))
    }

    @Test
    fun `reutilizar el mismo token responde 401 en el segundo uso (single-use C-3)`() {
        var primerStatus: HttpStatusCode? = null
        var segundoStatus: HttpStatusCode? = null
        val body = Json.encodeToString(PasswordResetConfirmRequestDto(tokenPuente(), NUEVA_CONTRASENA, NUEVA_CONTRASENA))
        enAplicacion(
            seedUsuario = { it.seed(usuario()) },
            seedTokens = { it.seed(token()) },
        ) { client ->
            primerStatus = client.postJson("/api/v1/auth/password-reset/confirm", body).first
            segundoStatus = client.postJson("/api/v1/auth/password-reset/confirm", body).first
        }
        assertEquals(HttpStatusCode.OK, primerStatus)
        assertEquals(HttpStatusCode.Unauthorized, segundoStatus)
    }

    @Test
    fun `confirm con contrasena igual a la anterior responde 409 PASSWORD_REUSED (CA5)`() {
        val (status, body) =
            confirmar(
                Json.encodeToString(PasswordResetConfirmRequestDto(tokenPuente(), CONTRASENA, CONTRASENA)),
                seedUsuario = { it.seed(usuario()) },
                seedTokens = { it.seed(token()) },
            )
        assertEquals(HttpStatusCode.Conflict, status)
        assertTrue(body.contains("\"error\":\"PASSWORD_REUSED\""))
    }

    @Test
    fun `confirm con contrasena debil responde 400 con details de campo (C-6)`() {
        val (status, body) =
            confirmar(
                Json.encodeToString(PasswordResetConfirmRequestDto(tokenPuente(), "corta", "corta")),
                seedUsuario = { it.seed(usuario()) },
                seedTokens = { it.seed(token()) },
            )
        assertEquals(HttpStatusCode.BadRequest, status)
        assertTrue(body.contains("\"field\":\"contrasena\""))
    }

    @Test
    fun `confirm con confirmacion distinta responde 400 con details de campo`() {
        val (status, body) =
            confirmar(
                Json.encodeToString(PasswordResetConfirmRequestDto(tokenPuente(), NUEVA_CONTRASENA, "Otra#2026")),
                seedUsuario = { it.seed(usuario()) },
                seedTokens = { it.seed(token()) },
            )
        assertEquals(HttpStatusCode.BadRequest, status)
        assertTrue(body.contains("\"field\":\"confirmarContrasena\""))
    }

    @Test
    fun `confirm con resetToken vacio responde 400 con details de campo`() {
        val (status, body) =
            confirmar(
                Json.encodeToString(PasswordResetConfirmRequestDto("", NUEVA_CONTRASENA, NUEVA_CONTRASENA)),
                seedUsuario = { it.seed(usuario()) },
                seedTokens = { it.seed(token()) },
            )
        assertEquals(HttpStatusCode.BadRequest, status)
        assertTrue(body.contains("\"field\":\"resetToken\""))
    }

    // ── E2E: request → verify → confirm → login (Módulo B) ────────────────────────────

    @Test
    fun `flujo completo de recuperacion termina con login de la nueva contrasena`() {
        var statusRequest: HttpStatusCode? = null
        var statusConfirm: HttpStatusCode? = null
        var statusLogin: HttpStatusCode? = null
        var tokenLogin = ""
        enAplicacion(
            seedUsuario = { it.seed(usuario()) },
            seedCodigos = { it.seed(codigo()) },
            seedTokens = { it.seed(token()) },
        ) { client ->
            statusRequest =
                client.postJson(
                    "/api/v1/auth/password-reset/request",
                    Json.encodeToString(PasswordResetRequestDto("laura.perez@example.com")),
                ).first
            val (statusVerify, bodyVerify) =
                client.postJson(
                    "/api/v1/auth/password-reset/verify",
                    Json.encodeToString(PasswordResetVerifyRequestDto("laura.perez@example.com", "123456")),
                )
            val puente = Json.decodeFromString<PasswordResetVerifyResponseDto>(bodyVerify).resetToken
            val (confirmStatus, _) =
                client.postJson(
                    "/api/v1/auth/password-reset/confirm",
                    Json.encodeToString(PasswordResetConfirmRequestDto(puente, NUEVA_CONTRASENA, NUEVA_CONTRASENA)),
                )
            val (loginStatus, bodyLogin) =
                client.postJson(
                    "/api/v1/auth/login",
                    Json.encodeToString(LoginRequestDto("laura.perez@example.com", NUEVA_CONTRASENA)),
                )
            statusConfirm = confirmStatus
            tokenLogin = Json.decodeFromString<LoginResponseDto>(bodyLogin).token
            statusLogin = loginStatus
        }
        assertEquals(HttpStatusCode.OK, statusRequest)
        assertEquals(HttpStatusCode.OK, statusConfirm)
        assertEquals(HttpStatusCode.OK, statusLogin)
        assertTrue(tokenLogin.isNotBlank(), "el flujo de recuperación debe habilitar el login con la nueva contraseña")
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

        val CONTRASENA = "Trivia#2025"
        val NUEVA_CONTRASENA = "Nueva#2026"

        /** Hash bcrypt real de la contraseña sembrada (coste 8, solo test). */
        val HASH_CONTRASENA: String = BCrypt.withDefaults().hashToString(8, CONTRASENA.toCharArray())

        /** Hash bcrypt real del OTP determinista "123456" (coste 12, igual que OtpService). */
        val HASH_CODIGO: String = BCrypt.withDefaults().hashToString(12, "123456".toCharArray())
    }
}
