package com.era.backend.routes

import at.favre.lib.crypto.bcrypt.BCrypt
import com.era.backend.assertExactKeys
import com.era.backend.config.JwtConfig
import com.era.backend.controllers.AvatarController
import com.era.backend.controllers.UsuarioController
import com.era.backend.models.SesionPrincipal
import com.era.backend.models.dto.ActualizarUsuarioRequestDto
import com.era.backend.models.dto.EliminarCuentaRequestDto
import com.era.backend.models.dto.MensajeResponseDto
import com.era.backend.models.dto.UsuarioPerfilDto
import com.era.backend.models.entities.EstadoUsuario
import com.era.backend.models.entities.RegistroPendienteRow
import com.era.backend.models.entities.UsuarioRow
import com.era.backend.plugins.configureAuthentication
import com.era.backend.plugins.configurePlugins
import com.era.backend.repositories.FakeRegistroPendienteRepository
import com.era.backend.repositories.FakeUsuarioRepository
import com.era.backend.repositories.TransactionRunner
import com.era.backend.services.AvatarService
import com.era.backend.services.JwtTokenService
import com.era.backend.services.UsuarioService
import com.era.backend.storage.FakeAvatarStorage
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Tests HTTP de los endpoints de usuario autenticado (Módulos D y E):
 * `GET /api/v1/users/me`, `PATCH /api/v1/users/me` y `DELETE /api/v1/users/me`
 * (`modulo-d-analisis.md` §3 y §4). Cubren la barrera de autenticación (`session-jwt`), los
 * 5 campos del perfil (D-4), el 401 del challenge (D-6), el 403 de cuenta eliminada (D-1),
 * la actualización de username (REQ-FUN-06 CA5) y el soft delete con reverificación
 * (REQ-FUN-05 CA2, D-2). Sin MySQL: fakes en memoria.
 */
class UserRoutesTest {

    private fun app(
        seedUsuario: (FakeUsuarioRepository) -> Unit,
        seedPendientes: (FakeRegistroPendienteRepository) -> Unit = {},
        block: suspend io.ktor.client.HttpClient.() -> Unit,
    ) {
        testApplication {
            application {
                configurePlugins()
                configureAuthentication(JWT_CONFIG_TEST)
                val fake = FakeUsuarioRepository()
                seedUsuario(fake)
                val pendientes = FakeRegistroPendienteRepository()
                seedPendientes(pendientes)
                val usuarioService = UsuarioService(fake, pendientes, TransactionRunner { it() })
                val avatarService = AvatarService(fake, FakeAvatarStorage(), TransactionRunner { it() })
                routing { userRoutes(UsuarioController(usuarioService), AvatarController(avatarService)) }
            }
            block(client)
        }
    }

    private fun usuario(
        id: Long = 1L,
        estado: EstadoUsuario = EstadoUsuario.ACTIVO,
        nombreUsuario: String = "mariacamila",
    ): UsuarioRow =
        UsuarioRow(
            idUsuario = id,
            nombreMenor = "María Camila",
            fechaNacimiento = LocalDate.of(2017, 4, 10),
            correo = "laura.perez@example.com",
            nombreUsuario = nombreUsuario,
            contrasenaHash = HASH_CONTRASENA,
            avatar = "preset:1",
            intentosLoginFallidos = 0,
            bloqueadoHasta = null,
            estado = estado,
            creadoEn = LocalDateTime.now(),
            actualizadoEn = LocalDateTime.now(),
        )

    /** Fila pendiente de prueba para sembrar reservas de username (unicidad del PATCH). */
    private fun pendiente(nombreUsuario: String = "reservado"): RegistroPendienteRow =
        RegistroPendienteRow(
            idRegistro = 1L,
            correo = "pendiente@example.com",
            nombreUsuario = nombreUsuario,
            contrasenaHash = HASH_CONTRASENA,
            nombreMenor = "Menor",
            fechaNacimiento = LocalDate.of(2016, 5, 10),
            nombreAcudiente = "Acudiente",
            cedulaAcudiente = "ABC123456",
            avatar = null,
            codigoHash = "hash",
            intentosFallidos = 0,
            expiraEn = LocalDateTime.now().plusMinutes(10),
            ultimoEnvioEn = LocalDateTime.now(),
            creadoEn = LocalDateTime.now(),
        )

    // ── GET /api/v1/users/me ─────────────────────────────────────────────────────────

    @Test
    fun `GET me sin token responde 401 UNAUTHORIZED`() {
        app({}) {
            val response = get("/api/v1/users/me")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"error\":\"UNAUTHORIZED\""), "el challenge debe usar el ErrorDto")
            assertTrue(body.contains("\"status\":401"))
        }
    }

    @Test
    fun `GET me con token de reseteo responde 401 UNAUTHORIZED`() {
        app({ it.seed(usuario()) }) {
            val resetToken =
                JwtTokenService(JWT_CONFIG_TEST)
                    .emitirReseteo(1L, jti = "jti-de-reseteo")
            val response =
                get("/api/v1/users/me") {
                    header(HttpHeaders.Authorization, "Bearer $resetToken")
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("\"error\":\"UNAUTHORIZED\""))
        }
    }

    @Test
    fun `GET me con token de sesion devuelve los 5 campos del perfil`() {
        app({ it.seed(usuario()) }) {
            val sesionToken = JwtTokenService(JWT_CONFIG_TEST).emitir(1L)
            val response =
                get("/api/v1/users/me") {
                    header(HttpHeaders.Authorization, "Bearer $sesionToken")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val perfil = Json.decodeFromString<UsuarioPerfilDto>(response.bodyAsText())
            assertEquals("María Camila", perfil.nombreMenor)
            assertEquals("2017-04-10", perfil.fechaNacimiento)
            assertEquals("laura.perez@example.com", perfil.correo)
            assertEquals("mariacamila", perfil.nombreUsuario)
            assertEquals("preset:1", perfil.avatar)
            assertExactKeys(
                response.bodyAsText(),
                "" to setOf("nombreMenor", "fechaNacimiento", "correo", "nombreUsuario", "avatar"),
            )
        }
    }

    @Test
    fun `GET me de cuenta eliminada responde 403 ACCOUNT_INACTIVE`() {
        app({ it.seed(usuario(estado = EstadoUsuario.ELIMINADO)) }) {
            val sesionToken = JwtTokenService(JWT_CONFIG_TEST).emitir(1L)
            val response =
                get("/api/v1/users/me") {
                    header(HttpHeaders.Authorization, "Bearer $sesionToken")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("\"error\":\"ACCOUNT_INACTIVE\""))
        }
    }

    @Test
    fun `GET me nunca expone hash ni cedula en el body`() {
        app({ it.seed(usuario()) }) {
            val sesionToken = JwtTokenService(JWT_CONFIG_TEST).emitir(1L)
            val response =
                get("/api/v1/users/me") {
                    header(HttpHeaders.Authorization, "Bearer $sesionToken")
                }
            val body = response.bodyAsText()
            assertFalse(body.contains(HASH_CONTRASENA), "el hash no debe aparecer")
            assertFalse(body.contains("contrasena"), "no debe haber campo de contraseña")
            assertExactKeys(
                body,
                "" to setOf("nombreMenor", "fechaNacimiento", "correo", "nombreUsuario", "avatar"),
            )
            assertFalse(body.contains("cedulaAcudiente"), "la cédula no debe aparecer")
        }
    }

    // ── PATCH /api/v1/users/me ───────────────────────────────────────────────────────

    @Test
    fun `PATCH me sin token responde 401 UNAUTHORIZED`() {
        app({}) {
            val response =
                patch("/api/v1/users/me") {
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(ActualizarUsuarioRequestDto("nuevoNick")))
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("\"error\":\"UNAUTHORIZED\""))
        }
    }

    @Test
    fun `PATCH me con token de reseteo responde 401 UNAUTHORIZED`() {
        app({ it.seed(usuario()) }) {
            val resetToken =
                JwtTokenService(JWT_CONFIG_TEST)
                    .emitirReseteo(1L, jti = "jti-de-reseteo")
            val response =
                patch("/api/v1/users/me") {
                    header(HttpHeaders.Authorization, "Bearer $resetToken")
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(ActualizarUsuarioRequestDto("nuevoNick")))
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("\"error\":\"UNAUTHORIZED\""))
        }
    }

    @Test
    fun `PATCH me valido responde 200 con el perfil actualizado`() {
        app({ it.seed(usuario()) }) {
            val sesionToken = JwtTokenService(JWT_CONFIG_TEST).emitir(1L)
            val response =
                patch("/api/v1/users/me") {
                    header(HttpHeaders.Authorization, "Bearer $sesionToken")
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(ActualizarUsuarioRequestDto("nuevoNick")))
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val perfil = Json.decodeFromString<UsuarioPerfilDto>(response.bodyAsText())
            assertEquals("nuevoNick", perfil.nombreUsuario)
            assertEquals("María Camila", perfil.nombreMenor)
            assertEquals("2017-04-10", perfil.fechaNacimiento)
            assertEquals("laura.perez@example.com", perfil.correo)
            assertEquals("preset:1", perfil.avatar)
            assertExactKeys(
                response.bodyAsText(),
                "" to setOf("nombreMenor", "fechaNacimiento", "correo", "nombreUsuario", "avatar"),
            )
        }
    }

    @Test
    fun `PATCH me con username con espacios responde 400 VALIDATION_ERROR con details`() {
        app({ it.seed(usuario()) }) {
            val sesionToken = JwtTokenService(JWT_CONFIG_TEST).emitir(1L)
            val response =
                patch("/api/v1/users/me") {
                    header(HttpHeaders.Authorization, "Bearer $sesionToken")
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(ActualizarUsuarioRequestDto("maria camila")))
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"error\":\"VALIDATION_ERROR\""))
            assertTrue(body.contains("\"field\":\"nombreUsuario\""))
        }
    }

    @Test
    fun `PATCH me con username corto responde 400 VALIDATION_ERROR`() {
        app({ it.seed(usuario()) }) {
            val sesionToken = JwtTokenService(JWT_CONFIG_TEST).emitir(1L)
            val response =
                patch("/api/v1/users/me") {
                    header(HttpHeaders.Authorization, "Bearer $sesionToken")
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(ActualizarUsuarioRequestDto("ab")))
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("\"error\":\"VALIDATION_ERROR\""))
        }
    }

    @Test
    fun `PATCH me con clave desconocida responde 400 INVALID_REQUEST`() {
        app({ it.seed(usuario()) }) {
            val sesionToken = JwtTokenService(JWT_CONFIG_TEST).emitir(1L)
            val response =
                patch("/api/v1/users/me") {
                    header(HttpHeaders.Authorization, "Bearer $sesionToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"nombreUsuario":"nuevoNick","idUsuario":999}""")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("\"error\":\"INVALID_REQUEST\""))
        }
    }

    @Test
    fun `PATCH me con username ocupado por otra cuenta responde 409 CONFLICT`() {
        app({ it.seed(usuario(id = 1L)); it.seed(usuario(id = 2L, nombreUsuario = "pedrito").copy(correo = "pedro@example.com")) }) {
            val sesionToken = JwtTokenService(JWT_CONFIG_TEST).emitir(1L)
            val response =
                patch("/api/v1/users/me") {
                    header(HttpHeaders.Authorization, "Bearer $sesionToken")
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(ActualizarUsuarioRequestDto("pedrito")))
                }
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue(response.bodyAsText().contains("\"error\":\"CONFLICT\""))
        }
    }

    @Test
    fun `PATCH me con username reservado en registro pendiente responde 409 CONFLICT`() {
        app(
            { it.seed(usuario()) },
            seedPendientes = { it.seed(pendiente()) },
        ) {
            val sesionToken = JwtTokenService(JWT_CONFIG_TEST).emitir(1L)
            val response =
                patch("/api/v1/users/me") {
                    header(HttpHeaders.Authorization, "Bearer $sesionToken")
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(ActualizarUsuarioRequestDto("reservado")))
                }
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue(response.bodyAsText().contains("\"error\":\"CONFLICT\""))
        }
    }

    @Test
    fun `PATCH me de cuenta eliminada responde 403 ACCOUNT_INACTIVE`() {
        app({ it.seed(usuario(estado = EstadoUsuario.ELIMINADO)) }) {
            val sesionToken = JwtTokenService(JWT_CONFIG_TEST).emitir(1L)
            val response =
                patch("/api/v1/users/me") {
                    header(HttpHeaders.Authorization, "Bearer $sesionToken")
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(ActualizarUsuarioRequestDto("nuevoNick")))
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("\"error\":\"ACCOUNT_INACTIVE\""))
        }
    }

    // ── DELETE /api/v1/users/me ──────────────────────────────────────────────────────

    @Test
    fun `DELETE me sin token responde 401 UNAUTHORIZED`() {
        app({}) {
            val response =
                delete("/api/v1/users/me") {
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(EliminarCuentaRequestDto(CONTRASENA)))
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("\"error\":\"UNAUTHORIZED\""))
        }
    }

    @Test
    fun `DELETE me con contrasena correcta responde 200 y deja la cuenta eliminada`() {
        app({ it.seed(usuario()) }) {
            val sesionToken = JwtTokenService(JWT_CONFIG_TEST).emitir(1L)
            val response =
                delete("/api/v1/users/me") {
                    header(HttpHeaders.Authorization, "Bearer $sesionToken")
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(EliminarCuentaRequestDto(CONTRASENA)))
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val mensaje = Json.decodeFromString<MensajeResponseDto>(response.bodyAsText())
            assertEquals("Cuenta eliminada. Tus datos se conservan.", mensaje.message)
            assertExactKeys(response.bodyAsText(), "" to setOf("message"))
        }
    }

    @Test
    fun `DELETE me con contrasena incorrecta responde 401 INVALID_CREDENTIALS`() {
        app({ it.seed(usuario()) }) {
            val sesionToken = JwtTokenService(JWT_CONFIG_TEST).emitir(1L)
            val response =
                delete("/api/v1/users/me") {
                    header(HttpHeaders.Authorization, "Bearer $sesionToken")
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(EliminarCuentaRequestDto("Clave-Erronea#1")))
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("\"error\":\"INVALID_CREDENTIALS\""))
        }
    }

    @Test
    fun `DELETE me con contrasena vacia responde 400 VALIDATION_ERROR con details`() {
        app({ it.seed(usuario()) }) {
            val sesionToken = JwtTokenService(JWT_CONFIG_TEST).emitir(1L)
            val response =
                delete("/api/v1/users/me") {
                    header(HttpHeaders.Authorization, "Bearer $sesionToken")
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(EliminarCuentaRequestDto("")))
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"error\":\"VALIDATION_ERROR\""))
            assertTrue(body.contains("\"field\":\"contrasena\""))
        }
    }

    @Test
    fun `DELETE me de cuenta ya eliminada responde 403 ACCOUNT_INACTIVE`() {
        app({ it.seed(usuario(estado = EstadoUsuario.ELIMINADO)) }) {
            val sesionToken = JwtTokenService(JWT_CONFIG_TEST).emitir(1L)
            val response =
                delete("/api/v1/users/me") {
                    header(HttpHeaders.Authorization, "Bearer $sesionToken")
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(EliminarCuentaRequestDto(CONTRASENA)))
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("\"error\":\"ACCOUNT_INACTIVE\""))
        }
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
