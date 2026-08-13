package com.era.backend.routes

import com.era.backend.assertExactKeys
import com.era.backend.config.JwtConfig
import com.era.backend.controllers.FeedbackController
import com.era.backend.models.entities.EstadoUsuario
import com.era.backend.models.entities.UsuarioRow
import com.era.backend.plugins.configureAuthentication
import com.era.backend.plugins.configurePlugins
import com.era.backend.repositories.FakeComentarioRepository
import com.era.backend.repositories.FakeUsuarioRepository
import com.era.backend.repositories.TransactionRunner
import com.era.backend.services.ComentarioService
import com.era.backend.services.JwtTokenService
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
 * Tests HTTP de `POST /api/v1/feedback/comments` (Módulo H, `modulo-h-analisis.md` §4).
 * Cubren la barrera `session-jwt` (401 sin token y con token de reseteo), el 200 con
 * confirmación, las validaciones de forma §3.1 (400 campo `contenido`), el rechazo de
 * claves desconocidas en el body y el 403 de cuenta en soft delete. Sin MySQL: fakes.
 */
class FeedbackControllerTest {

    private fun app(
        usuarios: FakeUsuarioRepository,
        comentarios: FakeComentarioRepository = FakeComentarioRepository(),
        block: suspend io.ktor.client.HttpClient.() -> Unit,
    ) {
        testApplication {
            application {
                configurePlugins()
                configureAuthentication(JWT_CONFIG_TEST)
                val service = ComentarioService(usuarios, comentarios, TransactionRunner { it() })
                routing { feedbackRoutes(FeedbackController(service)) }
            }
            block(client)
        }
    }

    private fun usuarioActivo(): UsuarioRow =
        UsuarioRow(
            idUsuario = 1L,
            nombreMenor = "María Camila",
            fechaNacimiento = LocalDate.of(2017, 4, 10),
            correo = "laura.perez@example.com",
            nombreUsuario = "mariacamila",
            contrasenaHash = "hash-de-prueba",
            avatar = null,
            intentosLoginFallidos = 0,
            bloqueadoHasta = null,
            estado = EstadoUsuario.ACTIVO,
            creadoEn = LocalDateTime.now(),
            actualizadoEn = LocalDateTime.now(),
        )

    private fun cuerpo(contenido: String): String =
        """{"contenido":${Json.encodeToString(contenido)}}"""

    private fun sesionToken(): String = JwtTokenService(JWT_CONFIG_TEST).emitir(1L)

    // ── Autenticación (barrera `session-jwt`, §4.2) ───────────────────────────────────

    @Test
    fun `POST sin token responde 401 UNAUTHORIZED`() {
        app(FakeUsuarioRepository()) {
            val response =
                post("/api/v1/feedback/comments") {
                    contentType(ContentType.Application.Json)
                    setBody(cuerpo("Hola"))
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("\"error\":\"UNAUTHORIZED\""))
        }
    }

    @Test
    fun `POST con token de reseteo responde 401 UNAUTHORIZED`() {
        app(FakeUsuarioRepository().apply { seed(usuarioActivo()) }) {
            val resetToken = JwtTokenService(JWT_CONFIG_TEST).emitirReseteo(1L, jti = "jti-reset")
            val response =
                post("/api/v1/feedback/comments") {
                    header(HttpHeaders.Authorization, "Bearer $resetToken")
                    contentType(ContentType.Application.Json)
                    setBody(cuerpo("Hola"))
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("\"error\":\"UNAUTHORIZED\""))
        }
    }

    // ── Happy path ────────────────────────────────────────────────────────────────────

    @Test
    fun `POST valido responde 200 con confirmacion y persiste el contenido trimeado`() {
        val usuarios = FakeUsuarioRepository().apply { seed(usuarioActivo()) }
        val comentarios = FakeComentarioRepository()
        app(usuarios, comentarios) {
            val response =
                post("/api/v1/feedback/comments") {
                    header(HttpHeaders.Authorization, "Bearer ${sesionToken()}")
                    contentType(ContentType.Application.Json)
                    setBody(cuerpo("   ¡Me encantó el nivel 3!   "))
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"message\":\"Comentario enviado con éxito.\""))
            assertExactKeys(response.bodyAsText(), "" to setOf("message"))
        }
        assertEquals(1, comentarios.size())
        assertEquals("¡Me encantó el nivel 3!", comentarios.todos().single().contenido)
        assertEquals(1L, comentarios.todos().single().idUsuario, "el id_usuario viene de la sesión")
    }

    @Test
    fun `POST con 2000 caracteres exactos responde 200`() {
        app(FakeUsuarioRepository().apply { seed(usuarioActivo()) }) {
            val response =
                post("/api/v1/feedback/comments") {
                    header(HttpHeaders.Authorization, "Bearer ${sesionToken()}")
                    contentType(ContentType.Application.Json)
                    setBody(cuerpo("a".repeat(2000)))
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertExactKeys(response.bodyAsText(), "" to setOf("message"))
        }
    }

    // ── Validación de forma (§3.1) ────────────────────────────────────────────────────

    @Test
    fun `POST con contenido vacio responde 400 campo contenido y no persiste`() {
        val usuarios = FakeUsuarioRepository().apply { seed(usuarioActivo()) }
        val comentarios = FakeComentarioRepository()
        app(usuarios, comentarios) {
            val response =
                post("/api/v1/feedback/comments") {
                    header(HttpHeaders.Authorization, "Bearer ${sesionToken()}")
                    contentType(ContentType.Application.Json)
                    setBody(cuerpo(""))
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"error\":\"VALIDATION_ERROR\""))
            assertTrue(body.contains("\"field\":\"contenido\""))
            assertTrue(body.contains("\"message\":\"Es obligatorio.\""))
        }
        assertEquals(0, comentarios.size())
    }

    @Test
    fun `POST con solo espacios responde 400 campo contenido`() {
        app(FakeUsuarioRepository().apply { seed(usuarioActivo()) }) {
            val response =
                post("/api/v1/feedback/comments") {
                    header(HttpHeaders.Authorization, "Bearer ${sesionToken()}")
                    contentType(ContentType.Application.Json)
                    setBody(cuerpo("   "))
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"error\":\"VALIDATION_ERROR\""))
            assertTrue(body.contains("\"field\":\"contenido\""))
            assertTrue(body.contains("\"message\":\"Es obligatorio.\""))
        }
    }

    @Test
    fun `POST con 2001 caracteres responde 400 con mensaje de maximo y no persiste`() {
        val usuarios = FakeUsuarioRepository().apply { seed(usuarioActivo()) }
        val comentarios = FakeComentarioRepository()
        app(usuarios, comentarios) {
            val response =
                post("/api/v1/feedback/comments") {
                    header(HttpHeaders.Authorization, "Bearer ${sesionToken()}")
                    contentType(ContentType.Application.Json)
                    setBody(cuerpo("a".repeat(2001)))
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"error\":\"VALIDATION_ERROR\""))
            assertTrue(body.contains("\"field\":\"contenido\""))
            assertTrue(body.contains("\"message\":\"Máximo 2000 caracteres.\""))
        }
        assertEquals(0, comentarios.size())
    }

    @Test
    fun `POST con clave idUsuario en el body responde 400 INVALID_REQUEST`() {
        app(FakeUsuarioRepository().apply { seed(usuarioActivo()) }) {
            val response =
                post("/api/v1/feedback/comments") {
                    header(HttpHeaders.Authorization, "Bearer ${sesionToken()}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"contenido":"hola","idUsuario":7}""")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("\"error\":\"INVALID_REQUEST\""))
        }
    }

    // ── Cuenta en soft delete (§3.2) ──────────────────────────────────────────────────

    @Test
    fun `POST de cuenta eliminada responde 403 ACCOUNT_INACTIVE y no persiste`() {
        val usuarios =
            FakeUsuarioRepository().apply { seed(usuarioActivo().copy(estado = EstadoUsuario.ELIMINADO)) }
        val comentarios = FakeComentarioRepository()
        app(usuarios, comentarios) {
            val response =
                post("/api/v1/feedback/comments") {
                    header(HttpHeaders.Authorization, "Bearer ${sesionToken()}")
                    contentType(ContentType.Application.Json)
                    setBody(cuerpo("Hola"))
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("\"error\":\"ACCOUNT_INACTIVE\""))
        }
        assertEquals(0, comentarios.size())
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
