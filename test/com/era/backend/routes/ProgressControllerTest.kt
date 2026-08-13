package com.era.backend.routes

import com.era.backend.assertExactKeys
import com.era.backend.config.JwtConfig
import com.era.backend.controllers.ProgressController
import com.era.backend.models.dto.ProgresoSyncItemDto
import com.era.backend.models.dto.ProgresoSyncRequestDto
import com.era.backend.models.dto.ProgresoSyncResponseDto
import com.era.backend.models.entities.EstadoNivel
import com.era.backend.models.entities.EstadoUsuario
import com.era.backend.models.entities.ProgresoUsuarioRow
import com.era.backend.models.entities.UsuarioRow
import com.era.backend.plugins.configureAuthentication
import com.era.backend.plugins.configurePlugins
import com.era.backend.repositories.FakeNivelRepository
import com.era.backend.repositories.FakeProgresoRepository
import com.era.backend.repositories.FakeUsuarioRepository
import com.era.backend.repositories.TransactionRunner
import com.era.backend.services.JwtTokenService
import com.era.backend.services.ProgressSyncService
import io.ktor.client.request.get
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
 * Tests HTTP de los endpoints de sincronización de progreso (Módulo G):
 * `GET`/`POST /api/v1/progress/sync` (`modulo-g-analisis.md` §4). Cubren la barrera de
 * autenticación (`session-jwt`), el 200 con snapshot/resumen, el 400 de forma (§5.1), el 400
 * de integridad contra el catálogo (§5.2) y el 403 de cuenta eliminada (§8). Sin MySQL:
 * fakes en memoria.
 */
class ProgressControllerTest {

    private fun app(
        seedUsuario: (FakeUsuarioRepository) -> Unit,
        seedNiveles: (FakeNivelRepository) -> Unit = { it.seedCatalogoCompleto() },
        seedProgreso: (FakeProgresoRepository) -> Unit = {},
        block: suspend io.ktor.client.HttpClient.() -> Unit,
    ) {
        testApplication {
            application {
                configurePlugins()
                configureAuthentication(JWT_CONFIG_TEST)
                val usuarios = FakeUsuarioRepository()
                seedUsuario(usuarios)
                val niveles = FakeNivelRepository()
                seedNiveles(niveles)
                val progreso = FakeProgresoRepository()
                seedProgreso(progreso)
                val service = ProgressSyncService(usuarios, niveles, progreso, TransactionRunner { it() })
                routing { progressRoutes(ProgressController(service)) }
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

    private fun cuerpo(
        vararg items: ProgresoSyncItemDto,
    ): String = Json.encodeToString(ProgresoSyncRequestDto(progreso = items.toList()))

    private fun item(
        orden: Int,
        estado: EstadoNivel = EstadoNivel.COMPLETADO,
        intentosTotales: Int = 0,
        intentosFallidosConsecutivos: Int = 0,
    ): ProgresoSyncItemDto =
        ProgresoSyncItemDto(
            orden = orden,
            estadoNivel = estado.valor,
            intentosTotales = intentosTotales,
            intentosFallidosConsecutivos = intentosFallidosConsecutivos,
        )

    /** Fila de progreso sembrada: `idNivel` y `idProgreso` = `orden` (catálogo secuencial). */
    private fun rowProgreso(
        orden: Int,
        estado: EstadoNivel,
        intentosTotales: Int,
        completadoEn: LocalDateTime?,
        ultimaInteraccion: LocalDateTime,
    ): ProgresoUsuarioRow =
        ProgresoUsuarioRow(
            idProgreso = orden.toLong(),
            idUsuario = 1L,
            idNivel = orden.toLong(),
            estadoNivel = estado,
            intentosTotales = intentosTotales,
            intentosFallidosConsecutivos = 0,
            pausaActiva = false,
            pausaHasta = null,
            completadoEn = completadoEn,
            ultimaInteraccion = ultimaInteraccion,
        )

    // ── GET /api/v1/progress/sync ────────────────────────────────────────────────────

    @Test
    fun `GET sin token responde 401 UNAUTHORIZED`() {
        app({}) {
            val response = get("/api/v1/progress/sync")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("\"error\":\"UNAUTHORIZED\""))
        }
    }

    @Test
    fun `GET con token de reseteo responde 401 UNAUTHORIZED`() {
        app({ it.seed(usuarioActivo()) }) {
            val resetToken = JwtTokenService(JWT_CONFIG_TEST).emitirReseteo(1L, jti = "jti-reset")
            val response =
                get("/api/v1/progress/sync") {
                    header(HttpHeaders.Authorization, "Bearer $resetToken")
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("\"error\":\"UNAUTHORIZED\""))
        }
    }

    @Test
    fun `GET de usuario nuevo responde 200 con progreso vacio y resumen 0 20 0`() {
        app({ it.seed(usuarioActivo()) }) {
            val sesionToken = JwtTokenService(JWT_CONFIG_TEST).emitir(1L)
            val response =
                get("/api/v1/progress/sync") {
                    header(HttpHeaders.Authorization, "Bearer $sesionToken")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val snapshot = Json.decodeFromString<ProgresoSyncResponseDto>(response.bodyAsText())
            assertTrue(snapshot.progreso.isEmpty())
            assertEquals(0, snapshot.resumen.nivelesCompletados)
            assertEquals(20, snapshot.resumen.totalNiveles)
            assertEquals(0, snapshot.resumen.totalReintentos)
            assertExactKeys(
                response.bodyAsText(),
                "" to setOf("progreso", "resumen"),
                "resumen" to setOf("nivelesCompletados", "totalNiveles", "totalReintentos"),
            )
        }
    }

    @Test
    fun `GET con snapshot no vacio responde los 5 campos de NivelProgresoDto con datos reales`() {
        app(
            seedUsuario = { it.seed(usuarioActivo()) },
            seedProgreso = {
                it.seed(
                    rowProgreso(
                        orden = 1,
                        estado = EstadoNivel.COMPLETADO,
                        intentosTotales = 7,
                        completadoEn = LocalDateTime.of(2026, 8, 10, 10, 15),
                        ultimaInteraccion = LocalDateTime.of(2026, 8, 10, 10, 30),
                    ),
                )
                it.seed(
                    rowProgreso(
                        orden = 2,
                        estado = EstadoNivel.DISPONIBLE,
                        intentosTotales = 3,
                        completadoEn = null,
                        ultimaInteraccion = LocalDateTime.of(2026, 8, 10, 9, 45),
                    ),
                )
                it.seed(
                    rowProgreso(
                        orden = 5,
                        estado = EstadoNivel.BLOQUEADO,
                        intentosTotales = 0,
                        completadoEn = null,
                        ultimaInteraccion = LocalDateTime.of(2026, 8, 10, 8, 0),
                    ),
                )
            },
        ) {
            val sesionToken = JwtTokenService(JWT_CONFIG_TEST).emitir(1L)
            val response =
                get("/api/v1/progress/sync") {
                    header(HttpHeaders.Authorization, "Bearer $sesionToken")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val snapshot = Json.decodeFromString<ProgresoSyncResponseDto>(response.bodyAsText())
            assertEquals(3, snapshot.progreso.size)
            assertEquals(listOf(1, 2, 5), snapshot.progreso.map { it.orden }.sorted())
            val nivel1 = snapshot.progreso.single { it.orden == 1 }
            val nivel2 = snapshot.progreso.single { it.orden == 2 }
            val nivel5 = snapshot.progreso.single { it.orden == 5 }
            assertEquals("completado", nivel1.estadoNivel)
            assertEquals(7, nivel1.intentosTotales)
            assertEquals("2026-08-10T10:15", nivel1.completadoEn)
            assertEquals("2026-08-10T10:30", nivel1.ultimaInteraccion)
            assertEquals("disponible", nivel2.estadoNivel)
            assertEquals(3, nivel2.intentosTotales)
            assertEquals(null, nivel2.completadoEn)
            assertEquals("bloqueado", nivel5.estadoNivel)
            assertEquals(0, nivel5.intentosTotales)
            assertEquals(null, nivel5.completadoEn)
            assertEquals(1, snapshot.resumen.nivelesCompletados)
            assertEquals(20, snapshot.resumen.totalNiveles)
            assertEquals(10, snapshot.resumen.totalReintentos)
            assertExactKeys(
                response.bodyAsText(),
                "" to setOf("progreso", "resumen"),
                "resumen" to setOf("nivelesCompletados", "totalNiveles", "totalReintentos"),
                "progreso[*]" to setOf("orden", "estadoNivel", "intentosTotales", "completadoEn", "ultimaInteraccion"),
            )
        }
    }

    @Test
    fun `GET de cuenta eliminada responde 403 ACCOUNT_INACTIVE`() {
        app({ it.seed(usuarioActivo().copy(estado = EstadoUsuario.ELIMINADO)) }) {
            val sesionToken = JwtTokenService(JWT_CONFIG_TEST).emitir(1L)
            val response =
                get("/api/v1/progress/sync") {
                    header(HttpHeaders.Authorization, "Bearer $sesionToken")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("\"error\":\"ACCOUNT_INACTIVE\""))
        }
    }

    // ── POST /api/v1/progress/sync ───────────────────────────────────────────────────

    @Test
    fun `POST sin token responde 401 UNAUTHORIZED`() {
        app({}) {
            val response =
                post("/api/v1/progress/sync") {
                    contentType(ContentType.Application.Json)
                    setBody(cuerpo(item(1)))
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("\"error\":\"UNAUTHORIZED\""))
        }
    }

    @Test
    fun `POST valido responde 200 con snapshot mergeado y resumen`() {
        app({ it.seed(usuarioActivo()) }) {
            val sesionToken = JwtTokenService(JWT_CONFIG_TEST).emitir(1L)
            val response =
                post("/api/v1/progress/sync") {
                    header(HttpHeaders.Authorization, "Bearer $sesionToken")
                    contentType(ContentType.Application.Json)
                    setBody(cuerpo(item(1, intentosTotales = 3), item(2, EstadoNivel.DISPONIBLE, intentosTotales = 1)))
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val snapshot = Json.decodeFromString<ProgresoSyncResponseDto>(response.bodyAsText())
            assertEquals(listOf(1, 2), snapshot.progreso.map { it.orden }.sorted())
            assertEquals(1, snapshot.resumen.nivelesCompletados)
            assertEquals(4, snapshot.resumen.totalReintentos)
            val completado = snapshot.progreso.single { it.orden == 1 }
            val disponible = snapshot.progreso.single { it.orden == 2 }
            assertTrue(completado.completadoEn != null, "completadoEn lo fija el servidor")
            assertTrue(disponible.completadoEn == null, "el nivel disponible no lleva marca")
            assertExactKeys(
                response.bodyAsText(),
                "" to setOf("progreso", "resumen"),
                "resumen" to setOf("nivelesCompletados", "totalNiveles", "totalReintentos"),
                "progreso[*]" to setOf("orden", "estadoNivel", "intentosTotales", "completadoEn", "ultimaInteraccion"),
            )
        }
    }

    @Test
    fun `POST con filas existentes responde items que reflejan el merge hacia adelante`() {
        app(
            seedUsuario = { it.seed(usuarioActivo()) },
            seedProgreso = {
                it.seed(
                    rowProgreso(
                        orden = 1,
                        estado = EstadoNivel.COMPLETADO,
                        intentosTotales = 10,
                        completadoEn = LocalDateTime.of(2026, 8, 10, 10, 15),
                        ultimaInteraccion = LocalDateTime.of(2026, 8, 10, 10, 30),
                    ),
                )
                it.seed(
                    rowProgreso(
                        orden = 2,
                        estado = EstadoNivel.DISPONIBLE,
                        intentosTotales = 2,
                        completadoEn = null,
                        ultimaInteraccion = LocalDateTime.of(2026, 8, 10, 9, 45),
                    ),
                )
            },
        ) {
            val sesionToken = JwtTokenService(JWT_CONFIG_TEST).emitir(1L)
            val response =
                post("/api/v1/progress/sync") {
                    header(HttpHeaders.Authorization, "Bearer $sesionToken")
                    contentType(ContentType.Application.Json)
                    setBody(
                        cuerpo(
                            item(1, EstadoNivel.DISPONIBLE, intentosTotales = 3),
                            item(2, EstadoNivel.COMPLETADO, intentosTotales = 4),
                            item(3, EstadoNivel.COMPLETADO, intentosTotales = 1),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val snapshot = Json.decodeFromString<ProgresoSyncResponseDto>(response.bodyAsText())
            assertEquals(listOf(1, 2, 3), snapshot.progreso.map { it.orden }.sorted())
            val nivel1 = snapshot.progreso.single { it.orden == 1 }
            val nivel2 = snapshot.progreso.single { it.orden == 2 }
            val nivel3 = snapshot.progreso.single { it.orden == 3 }
            assertEquals("completado", nivel1.estadoNivel)
            assertEquals(10, nivel1.intentosTotales)
            assertEquals("2026-08-10T10:15", nivel1.completadoEn)
            assertEquals("completado", nivel2.estadoNivel)
            assertEquals(4, nivel2.intentosTotales)
            assertTrue(nivel2.completadoEn != null, "completadoEn lo fija el servidor en la transicion")
            assertEquals("completado", nivel3.estadoNivel)
            assertEquals(1, nivel3.intentosTotales)
            assertTrue(nivel3.completadoEn != null, "completadoEn lo fija el servidor en la transicion")
            for (itemRespuesta in snapshot.progreso) {
                assertTrue(itemRespuesta.ultimaInteraccion.isNotBlank(), "ultimaInteraccion siempre presente")
                LocalDateTime.parse(itemRespuesta.ultimaInteraccion)
            }
            assertEquals(3, snapshot.resumen.nivelesCompletados)
            assertEquals(20, snapshot.resumen.totalNiveles)
            assertEquals(15, snapshot.resumen.totalReintentos)
            assertExactKeys(
                response.bodyAsText(),
                "" to setOf("progreso", "resumen"),
                "resumen" to setOf("nivelesCompletados", "totalNiveles", "totalReintentos"),
                "progreso[*]" to setOf("orden", "estadoNivel", "intentosTotales", "completadoEn", "ultimaInteraccion"),
            )
        }
    }

    @Test
    fun `POST sin campo progreso responde 400 VALIDATION_ERROR`() {
        app({ it.seed(usuarioActivo()) }) {
            val sesionToken = JwtTokenService(JWT_CONFIG_TEST).emitir(1L)
            val response =
                post("/api/v1/progress/sync") {
                    header(HttpHeaders.Authorization, "Bearer $sesionToken")
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"error\":\"VALIDATION_ERROR\""))
            assertTrue(body.contains("\"field\":\"progreso\""))
        }
    }

    @Test
    fun `POST con orden fuera de rango responde 400 con details`() {
        app({ it.seed(usuarioActivo()) }) {
            val sesionToken = JwtTokenService(JWT_CONFIG_TEST).emitir(1L)
            val response =
                post("/api/v1/progress/sync") {
                    header(HttpHeaders.Authorization, "Bearer $sesionToken")
                    contentType(ContentType.Application.Json)
                    setBody(cuerpo(item(0)))
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("\"error\":\"VALIDATION_ERROR\""))
            assertTrue(response.bodyAsText().contains("\"field\":\"progreso[0].orden\""))
        }
    }

    @Test
    fun `POST con estado invalido responde 400`() {
        app({ it.seed(usuarioActivo()) }) {
            val sesionToken = JwtTokenService(JWT_CONFIG_TEST).emitir(1L)
            val response =
                post("/api/v1/progress/sync") {
                    header(HttpHeaders.Authorization, "Bearer $sesionToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"progreso":[{"orden":1,"estadoNivel":"legendario"}]}""")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("\"error\":\"VALIDATION_ERROR\""))
            assertTrue(response.bodyAsText().contains("\"field\":\"progreso[0].estadoNivel\""))
        }
    }

    @Test
    fun `POST con intentosTotales negativo responde 400`() {
        app({ it.seed(usuarioActivo()) }) {
            val sesionToken = JwtTokenService(JWT_CONFIG_TEST).emitir(1L)
            val response =
                post("/api/v1/progress/sync") {
                    header(HttpHeaders.Authorization, "Bearer $sesionToken")
                    contentType(ContentType.Application.Json)
                    setBody(cuerpo(item(1, intentosTotales = -1)))
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("\"error\":\"VALIDATION_ERROR\""))
            assertTrue(response.bodyAsText().contains("\"field\":\"progreso[0].intentosTotales\""))
        }
    }

    @Test
    fun `POST con orden duplicado responde 400`() {
        app({ it.seed(usuarioActivo()) }) {
            val sesionToken = JwtTokenService(JWT_CONFIG_TEST).emitir(1L)
            val response =
                post("/api/v1/progress/sync") {
                    header(HttpHeaders.Authorization, "Bearer $sesionToken")
                    contentType(ContentType.Application.Json)
                    setBody(cuerpo(item(1), item(1, EstadoNivel.DISPONIBLE)))
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("\"error\":\"VALIDATION_ERROR\""))
        }
    }

    @Test
    fun `POST con orden inexistente en catalogo responde 400 y no persiste`() {
        // El orden 5 está DENTRO de rango (1..20) pero el catálogo lo omite: la validación de
        // forma pasa y la de integridad §5.2 responde 400 con `progreso.orden`.
        app(
            seedUsuario = { it.seed(usuarioActivo()) },
            seedNiveles = { it.seedCatalogoCompleto(sinOrden = 5) },
        ) {
            val sesionToken = JwtTokenService(JWT_CONFIG_TEST).emitir(1L)
            val response =
                post("/api/v1/progress/sync") {
                    header(HttpHeaders.Authorization, "Bearer $sesionToken")
                    contentType(ContentType.Application.Json)
                    setBody(cuerpo(item(5)))
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"error\":\"VALIDATION_ERROR\""))
            assertTrue(body.contains("\"field\":\"progreso.orden\""))
        }
    }

    @Test
    fun `POST de cuenta eliminada responde 403 ACCOUNT_INACTIVE`() {
        app({ it.seed(usuarioActivo().copy(estado = EstadoUsuario.ELIMINADO)) }) {
            val sesionToken = JwtTokenService(JWT_CONFIG_TEST).emitir(1L)
            val response =
                post("/api/v1/progress/sync") {
                    header(HttpHeaders.Authorization, "Bearer $sesionToken")
                    contentType(ContentType.Application.Json)
                    setBody(cuerpo(item(1)))
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
    }
}
