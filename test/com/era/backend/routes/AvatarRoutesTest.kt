package com.era.backend.routes

import com.era.backend.assertExactKeys
import com.era.backend.config.JwtConfig
import com.era.backend.controllers.AvatarController
import com.era.backend.controllers.UsuarioController
import com.era.backend.models.entities.EstadoUsuario
import com.era.backend.models.entities.UsuarioRow
import com.era.backend.plugins.configureAuthentication
import com.era.backend.plugins.configurePlugins
import com.era.backend.repositories.FakeUsuarioRepository
import com.era.backend.repositories.TransactionRunner
import com.era.backend.services.AvatarService
import com.era.backend.services.JwtTokenService
import com.era.backend.services.UsuarioService
import com.era.backend.storage.FakeAvatarStorage
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headers
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests HTTP de `PUT`/`GET /api/v1/users/me/avatar` (Módulo I, `modulo-i-analisis.md` §3 y §4).
 * Multipart real vía `submitFormWithBinaryData` contra el engine de test: barrera `session-jwt`
 * (401 sin token y con token de reseteo), happy path con persistencia (clave `custom:*` en BD y
 * disco), validaciones de forma del controller (parte ausente y límite de 2 MB con RAM acotada),
 * validaciones de negocio del service (magic bytes + doble validación MIME) y el servido con
 * headers de seguridad (§3.2). Sin MySQL: fakes en memoria.
 *
 * Nota Ktor 3.4.3 (descubierta en la depuración): el engine CIO produce `FileItem` solo cuando la
 * parte trae `filename` en su `Content-Disposition`; una parte sin `filename` se lee entera y se
 * decodifica como texto (`FormItem`), perdiendo los bytes. Por eso el helper [subirAvatar] incluye
 * `filename="avatar.jpg"`: imita una subida de archivo real (lo que también hace el cliente Android).
 */
class AvatarRoutesTest {

    private fun app(
        usuarios: FakeUsuarioRepository,
        storage: FakeAvatarStorage = FakeAvatarStorage(),
        block: suspend io.ktor.client.HttpClient.() -> Unit,
    ) {
        testApplication {
            application {
                configurePlugins()
                configureAuthentication(JWT_CONFIG_TEST)
                val avatarService = AvatarService(usuarios, storage, TransactionRunner { it() })
                val usuarioService = UsuarioService(usuarios, TransactionRunner { it() })
                routing {
                    userRoutes(UsuarioController(usuarioService), AvatarController(avatarService))
                }
            }
            block(client)
        }
    }

    private fun usuarioActivo(avatar: String? = null): UsuarioRow =
        UsuarioRow(
            idUsuario = 1L,
            nombreMenor = "María Camila",
            fechaNacimiento = LocalDate.of(2017, 4, 10),
            correo = "laura.perez@example.com",
            nombreUsuario = "mariacamila",
            contrasenaHash = "hash-de-prueba",
            avatar = avatar,
            intentosLoginFallidos = 0,
            bloqueadoHasta = null,
            estado = EstadoUsuario.ACTIVO,
            creadoEn = LocalDateTime.now(),
            actualizadoEn = LocalDateTime.now(),
        )

    private fun sesionToken(): String = JwtTokenService(JWT_CONFIG_TEST).emitir(1L)

    private suspend fun io.ktor.client.HttpClient.subirAvatar(
        bytes: ByteArray,
        mime: String,
    ): HttpResponse =
        submitFormWithBinaryData(
            url = "/api/v1/users/me/avatar",
            formData = formData {
                append(
                    "avatar",
                    bytes,
                    headers {
                        append(HttpHeaders.ContentType, mime)
                        append(HttpHeaders.ContentDisposition, "filename=\"avatar.jpg\"")
                    },
                )
            },
        ) {
            method = HttpMethod.Put
            header(HttpHeaders.Authorization, "Bearer ${sesionToken()}")
        }

    // ── PUT: autenticación (barrera `session-jwt`, §4.2) ─────────────────────────────

    @Test
    fun `PUT sin token responde 401 UNAUTHORIZED`() {
        app(FakeUsuarioRepository().apply { seed(usuarioActivo()) }) {
            val response =
                submitFormWithBinaryData(
                    url = "/api/v1/users/me/avatar",
                    formData = formData {
                        append(
                            "avatar",
                            jpeg(),
                            headers {
                                append(HttpHeaders.ContentType, "image/jpeg")
                                append(HttpHeaders.ContentDisposition, "filename=\"avatar.jpg\"")
                            },
                        )
                    },
                ) {
                    method = HttpMethod.Put
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("\"error\":\"UNAUTHORIZED\""))
        }
    }

    @Test
    fun `PUT con token de reseteo responde 401 UNAUTHORIZED`() {
        app(FakeUsuarioRepository().apply { seed(usuarioActivo()) }) {
            val resetToken = JwtTokenService(JWT_CONFIG_TEST).emitirReseteo(1L, jti = "jti-reset")
            val response =
                submitFormWithBinaryData(
                    url = "/api/v1/users/me/avatar",
                    formData = formData {
                        append(
                            "avatar",
                            jpeg(),
                            headers {
                                append(HttpHeaders.ContentType, "image/jpeg")
                                append(HttpHeaders.ContentDisposition, "filename=\"avatar.jpg\"")
                            },
                        )
                    },
                ) {
                    method = HttpMethod.Put
                    header(HttpHeaders.Authorization, "Bearer $resetToken")
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("\"error\":\"UNAUTHORIZED\""))
        }
    }

    // ── PUT: happy path ──────────────────────────────────────────────────────────────

    @Test
    fun `PUT valido responde 200 y persiste la clave custom en BD y disco`() {
        val usuarios = FakeUsuarioRepository().apply { seed(usuarioActivo()) }
        val storage = FakeAvatarStorage()
        app(usuarios, storage) {
            val response = subirAvatar(jpeg(), "image/jpeg")
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"message\":\"Avatar actualizado con éxito.\""))
            assertExactKeys(response.bodyAsText(), "" to setOf("message"))
        }
        val clave = usuarios.findById(1L)!!.avatar!!
        assertTrue(clave.startsWith("custom:"))
        assertTrue(clave.endsWith(".jpg"))
        assertEquals(setOf(clave), storage.claves())
    }

    // ── PUT: validaciones de forma del controller (§4.1) ─────────────────────────────

    @Test
    fun `PUT sin parte avatar responde 400 con el campo avatar`() {
        val usuarios = FakeUsuarioRepository().apply { seed(usuarioActivo()) }
        val storage = FakeAvatarStorage()
        app(usuarios, storage) {
            val response =
                submitFormWithBinaryData(
                    url = "/api/v1/users/me/avatar",
                    formData = formData { append("otroCampo", "texto") },
                ) {
                    method = HttpMethod.Put
                    header(HttpHeaders.Authorization, "Bearer ${sesionToken()}")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"error\":\"VALIDATION_ERROR\""))
            assertTrue(body.contains("\"field\":\"avatar\""))
            assertTrue(body.contains("\"message\":\"Se requiere un archivo.\""))
        }
        assertNull(usuarios.findById(1L)!!.avatar)
        assertTrue(storage.claves().isEmpty())
    }

    @Test
    fun `PUT con mas de 2 MB responde 400 con el mensaje de maximo`() {
        val usuarios = FakeUsuarioRepository().apply { seed(usuarioActivo()) }
        val storage = FakeAvatarStorage()
        app(usuarios, storage) {
            val response = subirAvatar(ByteArray(2 * 1024 * 1024 + 1), "image/jpeg")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"error\":\"VALIDATION_ERROR\""))
            assertTrue(body.contains("\"field\":\"avatar\""))
            assertTrue(body.contains("\"message\":\"Máximo 2 MB.\""))
        }
        assertNull(usuarios.findById(1L)!!.avatar)
        assertTrue(storage.claves().isEmpty())
    }

    // ── PUT: validaciones de negocio del service (§2.1) ──────────────────────────────

    @Test
    fun `PUT con contenido que no es imagen responde 400`() {
        val usuarios = FakeUsuarioRepository().apply { seed(usuarioActivo()) }
        val storage = FakeAvatarStorage()
        app(usuarios, storage) {
            val response = subirAvatar(byteArrayOf(1, 2, 3, 4, 5), "image/png")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("\"error\":\"VALIDATION_ERROR\""))
        }
        assertNull(usuarios.findById(1L)!!.avatar)
        assertTrue(storage.claves().isEmpty())
    }

    @Test
    fun `PUT con magic bytes JPEG y MIME declarado PNG responde 400`() {
        val usuarios = FakeUsuarioRepository().apply { seed(usuarioActivo()) }
        app(usuarios, FakeAvatarStorage()) {
            val response = subirAvatar(jpeg(), "image/png")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("\"error\":\"VALIDATION_ERROR\""))
        }
        assertNull(usuarios.findById(1L)!!.avatar)
    }

    // ── PUT: seguridad (anonimización §2.3 y aislamiento entre usuarios) ──────────────

    @Test
    fun `PUT con filename hostil se anonimiza y la clave nunca deriva del nombre`() {
        val usuarios = FakeUsuarioRepository().apply { seed(usuarioActivo()) }
        val storage = FakeAvatarStorage()
        app(usuarios, storage) {
            val response =
                submitFormWithBinaryData(
                    url = "/api/v1/users/me/avatar",
                    formData = formData {
                        append(
                            "avatar",
                            jpeg(),
                            headers {
                                append(HttpHeaders.ContentType, "image/jpeg")
                                append(HttpHeaders.ContentDisposition, "filename=\"../../evil.php\"")
                            },
                        )
                    },
                ) {
                    method = HttpMethod.Put
                    header(HttpHeaders.Authorization, "Bearer ${sesionToken()}")
                }
            assertEquals(HttpStatusCode.OK, response.status)
        }
        val clave = usuarios.findById(1L)!!.avatar!!
        assertTrue(clave.startsWith("custom:"), "clave opaca generada por el servidor (§2.3)")
        assertTrue(clave.endsWith(".jpg"), "la extensión es la canónica del formato real (jpeg)")
        assertTrue(
            !clave.contains("..") && !clave.contains('/') && !clave.contains('\\') && !clave.contains("evil"),
            "el filename del cliente jamás entra en la clave",
        )
        assertEquals(setOf(clave), storage.claves(), "una sola clave custom:*, sin path traversal")
    }

    @Test
    fun `PUT y GET de un usuario no tocan el avatar de otro (aislamiento A-B)`() {
        val usuarios =
            FakeUsuarioRepository().apply {
                seed(usuarioActivo(avatar = "custom:foto.png"))
                seed(
                    usuarioActivo(avatar = null).copy(
                        idUsuario = 2L,
                        correo = "otro.acudiente@example.com",
                        nombreUsuario = "otrousuario",
                    ),
                )
            }
        val storage = FakeAvatarStorage().apply { guardar("custom:foto.png", png(), "image/png") }
        app(usuarios, storage) {
            val tokenUsuario2 = JwtTokenService(JWT_CONFIG_TEST).emitir(2L)
            val get =
                get("/api/v1/users/me/avatar") {
                    header(HttpHeaders.Authorization, "Bearer $tokenUsuario2")
                }
            assertEquals(HttpStatusCode.NotFound, get.status, "el token de 2 no ve la foto de 1")
            val put =
                submitFormWithBinaryData(
                    url = "/api/v1/users/me/avatar",
                    formData = formData {
                        append(
                            "avatar",
                            jpeg(),
                            headers {
                                append(HttpHeaders.ContentType, "image/jpeg")
                                append(HttpHeaders.ContentDisposition, "filename=\"avatar.jpg\"")
                            },
                        )
                    },
                ) {
                    method = HttpMethod.Put
                    header(HttpHeaders.Authorization, "Bearer $tokenUsuario2")
                }
            assertEquals(HttpStatusCode.OK, put.status)
        }
        assertEquals("custom:foto.png", usuarios.findById(1L)!!.avatar, "el avatar de 1 no se toca")
        assertTrue(storage.contiene("custom:foto.png"), "el archivo de 1 no se borra ni se reemplaza")
        val claveDe2 = usuarios.findById(2L)!!.avatar!!
        assertTrue(claveDe2.startsWith("custom:") && claveDe2 != "custom:foto.png")
        assertTrue(storage.contiene(claveDe2), "el avatar de 2 se persiste en su propia clave")
    }

    // ── PUT: cuenta en soft delete (§4.1) ────────────────────────────────────────────

    @Test
    fun `PUT de cuenta eliminada responde 403 ACCOUNT_INACTIVE sin escribir archivo`() {
        val usuarios =
            FakeUsuarioRepository().apply {
                seed(usuarioActivo().copy(estado = EstadoUsuario.ELIMINADO))
            }
        val storage = FakeAvatarStorage()
        app(usuarios, storage) {
            val response = subirAvatar(jpeg(), "image/jpeg")
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("\"error\":\"ACCOUNT_INACTIVE\""))
        }
        assertTrue(storage.claves().isEmpty(), "nada se persiste de una cuenta inactiva")
    }

    // ── GET: autenticación y servido (§3.2) ──────────────────────────────────────────

    @Test
    fun `GET sin token responde 401 UNAUTHORIZED`() {
        app(FakeUsuarioRepository().apply { seed(usuarioActivo()) }) {
            val response = get("/api/v1/users/me/avatar")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("\"error\":\"UNAUTHORIZED\""))
        }
    }

    @Test
    fun `GET valido sirve el binario con headers de seguridad`() {
        val usuarios =
            FakeUsuarioRepository().apply { seed(usuarioActivo(avatar = "custom:foto.png")) }
        val storage = FakeAvatarStorage().apply {
            guardar("custom:foto.png", png(), "image/png")
        }
        app(usuarios, storage) {
            val response =
                get("/api/v1/users/me/avatar") {
                    header(HttpHeaders.Authorization, "Bearer ${sesionToken()}")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsBytes().contentEquals(png()))
            assertEquals(ContentType.Image.PNG, response.contentType())
            assertEquals("nosniff", response.headers["X-Content-Type-Options"])
            assertEquals("private, no-store", response.headers[HttpHeaders.CacheControl])
            assertEquals(
                "inline; filename=\"avatar.png\"",
                response.headers[HttpHeaders.ContentDisposition],
            )
        }
    }

    // ── GET: 404 y 403 ───────────────────────────────────────────────────────────────

    @Test
    fun `GET sin avatar personalizado responde 404 NOT_FOUND`() {
        app(FakeUsuarioRepository().apply { seed(usuarioActivo(avatar = null)) }) {
            val response =
                get("/api/v1/users/me/avatar") {
                    header(HttpHeaders.Authorization, "Bearer ${sesionToken()}")
                }
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains("\"error\":\"NOT_FOUND\""))
        }
    }

    @Test
    fun `GET con avatar preset responde 404 NOT_FOUND`() {
        app(FakeUsuarioRepository().apply { seed(usuarioActivo(avatar = "preset:1")) }) {
            val response =
                get("/api/v1/users/me/avatar") {
                    header(HttpHeaders.Authorization, "Bearer ${sesionToken()}")
                }
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains("\"error\":\"NOT_FOUND\""))
        }
    }

    @Test
    fun `GET con clave custom pero archivo ausente responde 404 defensivo`() {
        app(FakeUsuarioRepository().apply { seed(usuarioActivo(avatar = "custom:fantasma.jpg")) }) {
            val response =
                get("/api/v1/users/me/avatar") {
                    header(HttpHeaders.Authorization, "Bearer ${sesionToken()}")
                }
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains("\"error\":\"NOT_FOUND\""))
        }
    }

    @Test
    fun `GET de cuenta eliminada responde 403 ACCOUNT_INACTIVE`() {
        app(
            FakeUsuarioRepository().apply {
                seed(usuarioActivo(avatar = "custom:foto.png").copy(estado = EstadoUsuario.ELIMINADO))
            },
        ) {
            val response =
                get("/api/v1/users/me/avatar") {
                    header(HttpHeaders.Authorization, "Bearer ${sesionToken()}")
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

        private fun jpeg(): ByteArray =
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01, 0x02)

        private fun png(): ByteArray =
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00)
    }
}
