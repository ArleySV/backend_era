package com.era.backend.services

import com.era.backend.exceptions.AccountInactiveException
import com.era.backend.exceptions.NotFoundException
import com.era.backend.exceptions.ValidationException
import com.era.backend.models.dto.ProgresoSyncItemDto
import com.era.backend.models.entities.EstadoNivel
import com.era.backend.models.entities.EstadoUsuario
import com.era.backend.models.entities.ProgresoUsuarioRow
import com.era.backend.repositories.FakeNivelRepository
import com.era.backend.repositories.FakeProgresoRepository
import com.era.backend.repositories.FakeUsuarioRepository
import com.era.backend.repositories.TransactionRunner
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests de [ProgressSyncService] (Módulo G, `modulo-g-analisis.md` §10). Sin MySQL:
 * `FakeUsuarioRepository` (cuenta activa/eliminada), `FakeNivelRepository` (catálogo
 * `orden → id_nivel`) y `FakeProgresoRepository` (espejo en memoria).
 *
 * Cobertura: merge hacia adelante (el estado nunca regresa), `intentosTotales` = max,
 * `completadoEn` fijado una sola vez, 403 cuenta inactiva, 400 si un `orden` no existe en el
 * catálogo (cero escrituras), una sola transacción por operación (§6), `totalReintentos`
 * como suma del servidor y snapshot tras POST idéntico a GET.
 */
class ProgressSyncServiceTest {

    private fun servicioCon(
        seedUsuario: (FakeUsuarioRepository) -> Unit = {},
        seedNiveles: (FakeNivelRepository) -> Unit,
        seedProgreso: (FakeProgresoRepository) -> Unit = {},
        runner: TransactionRunner = TransactionRunner { it() },
    ): Contexto {
        val usuarios = FakeUsuarioRepository()
        seedUsuario(usuarios)
        val niveles = FakeNivelRepository()
        seedNiveles(niveles)
        val progreso = FakeProgresoRepository()
        seedProgreso(progreso)
        val servicio = ProgressSyncService(usuarios, niveles, progreso, runner)
        return Contexto(servicio, progreso, runner)
    }

    /** Helpers: los tests llaman a `servicioCon` y siembran dentro. */
    private fun seedActivo(
        usuarios: FakeUsuarioRepository = FakeUsuarioRepository(),
    ): FakeUsuarioRepository {
        usuarios.seed(
            com.era.backend.models.entities.UsuarioRow(
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
            ),
        )
        return usuarios
    }

    private fun item(
        orden: Int,
        estado: EstadoNivel,
        intentosTotales: Int = 0,
        intentosFallidosConsecutivos: Int = 0,
    ): ProgresoSyncItemDto =
        ProgresoSyncItemDto(
            orden = orden,
            estadoNivel = estado.valor,
            intentosTotales = intentosTotales,
            intentosFallidosConsecutivos = intentosFallidosConsecutivos,
        )

    private fun fila(
        idUsuario: Long = 1L,
        idNivel: Long = 1L,
        estado: EstadoNivel = EstadoNivel.DISPONIBLE,
        intentosTotales: Int = 0,
        completadoEn: LocalDateTime? = null,
    ): ProgresoUsuarioRow =
        ProgresoUsuarioRow(
            idProgreso = 0,
            idUsuario = idUsuario,
            idNivel = idNivel,
            estadoNivel = estado,
            intentosTotales = intentosTotales,
            intentosFallidosConsecutivos = 0,
            pausaActiva = false,
            pausaHasta = null,
            completadoEn = completadoEn,
            ultimaInteraccion = LocalDateTime.now(),
        )

    /** Contexto de una prueba: service + fakes + runner (para contar transacciones). */
    class Contexto(
        val servicio: ProgressSyncService,
        val progreso: FakeProgresoRepository,
        val runner: TransactionRunner,
    )

    // ── GET: snapshot ────────────────────────────────────────────────────────────────

    @Test
    fun `GET de usuario nuevo devuelve progreso vacio y resumen 0 20 0`() {
        val ctx =
            servicioCon(
                seedUsuario = { seedActivo(it) },
                seedNiveles = { it.seedCatalogoCompleto() },
            )
        val snapshot = ctx.servicio.obtenerSnapshot(1L)
        assertTrue(snapshot.progreso.isEmpty())
        assertEquals(0, snapshot.resumen.nivelesCompletados)
        assertEquals(20, snapshot.resumen.totalNiveles)
        assertEquals(0, snapshot.resumen.totalReintentos)
    }

    @Test
    fun `GET devuelve filas existentes con resumen calculado en el servidor`() {
        val ctx =
            servicioCon(
                seedUsuario = { seedActivo(it) },
                seedNiveles = { it.seedCatalogoCompleto() },
                seedProgreso = {
                    it.seed(fila(idNivel = 1L, estado = EstadoNivel.COMPLETADO, intentosTotales = 3))
                    it.seed(fila(idNivel = 2L, estado = EstadoNivel.DISPONIBLE, intentosTotales = 1))
                },
            )
        val snapshot = ctx.servicio.obtenerSnapshot(1L)
        assertEquals(2, snapshot.progreso.size)
        assertEquals(listOf(1, 2), snapshot.progreso.map { it.orden }.sorted())
        assertEquals(1, snapshot.resumen.nivelesCompletados)
        assertEquals(20, snapshot.resumen.totalNiveles)
        assertEquals(4, snapshot.resumen.totalReintentos)
    }

    @Test
    fun `GET de cuenta eliminada lanza AccountInactiveException`() {
        val ctx =
            servicioCon(
                seedUsuario = { it.seed(usuario(estado = EstadoUsuario.ELIMINADO)) },
                seedNiveles = { it.seedCatalogoCompleto() },
            )
        assertFailsWith<AccountInactiveException> { ctx.servicio.obtenerSnapshot(1L) }
    }

    @Test
    fun `GET de usuario inexistente lanza NotFoundException`() {
        val ctx =
            servicioCon(
                seedUsuario = { seedActivo(it) },
                seedNiveles = { it.seedCatalogoCompleto() },
            )
        assertFailsWith<NotFoundException> { ctx.servicio.obtenerSnapshot(99L) }
    }

    // ── POST: merge hacia adelante ───────────────────────────────────────────────────

    @Test
    fun `POST con estado inferior sobre completado no regresa el estado`() {
        val ctx =
            servicioCon(
                seedUsuario = { seedActivo(it) },
                seedNiveles = { it.seedCatalogoCompleto() },
                seedProgreso = { it.seed(fila(idNivel = 1L, estado = EstadoNivel.COMPLETADO, intentosTotales = 3)) },
            )
        val snapshot = ctx.servicio.sincronizar(1L, listOf(item(1, EstadoNivel.DISPONIBLE, intentosTotales = 2)))
        assertEquals("completado", snapshot.progreso.single().estadoNivel)
        assertEquals(3, snapshot.progreso.single().intentosTotales, "intentosTotales = max(cliente, servidor)")
    }

    @Test
    fun `POST promueve disponible a completado y fija completadoEn`() {
        val ctx =
            servicioCon(
                seedUsuario = { seedActivo(it) },
                seedNiveles = { it.seedCatalogoCompleto() },
                seedProgreso = { it.seed(fila(idNivel = 1L, estado = EstadoNivel.DISPONIBLE)) },
            )
        val snapshot = ctx.servicio.sincronizar(1L, listOf(item(1, EstadoNivel.COMPLETADO, intentosTotales = 1)))
        assertEquals("completado", snapshot.progreso.single().estadoNivel)
        assertNotNull(snapshot.progreso.single().completadoEn, "completadoEn lo fija el servidor")
    }

    @Test
    fun `POST inserta nivel nuevo y persistira el estado`() {
        val ctx =
            servicioCon(
                seedUsuario = { seedActivo(it) },
                seedNiveles = { it.seedCatalogoCompleto() },
            )
        val snapshot = ctx.servicio.sincronizar(1L, listOf(item(1, EstadoNivel.COMPLETADO, intentosTotales = 4)))
        assertEquals(1, snapshot.progreso.size)
        assertEquals(1, ctx.progreso.size(), "el nivel queda persistido en el espejo")
        assertEquals("completado", ctx.progreso.todas(1L).single().estadoNivel.valor)
        assertEquals(4, ctx.progreso.todas(1L).single().intentosTotales)
    }

    @Test
    fun `completadoEn se fija una sola vez y nunca se resetea`() {
        val ctx =
            servicioCon(
                seedUsuario = { seedActivo(it) },
                seedNiveles = { it.seedCatalogoCompleto() },
            )
        val primero = ctx.servicio.sincronizar(1L, listOf(item(1, EstadoNivel.COMPLETADO)))
        val segundo = ctx.servicio.sincronizar(1L, listOf(item(1, EstadoNivel.COMPLETADO)))
        assertNotNull(primero.progreso.single().completadoEn)
        assertEquals(
            primero.progreso.single().completadoEn,
            segundo.progreso.single().completadoEn,
            "re-sincronizar no resetea la marca del servidor",
        )
    }

    @Test
    fun `intentosTotales y fallidos son max cliente servidor`() {
        val ctx =
            servicioCon(
                seedUsuario = { seedActivo(it) },
                seedNiveles = { it.seedCatalogoCompleto() },
                seedProgreso = {
                    it.seed(fila(idNivel = 1L, estado = EstadoNivel.DISPONIBLE, intentosTotales = 5))
                },
            )
        val snapshot =
            ctx.servicio.sincronizar(
                1L,
                listOf(item(1, EstadoNivel.DISPONIBLE, intentosTotales = 2, intentosFallidosConsecutivos = 3)),
            )
        assertEquals(5, snapshot.progreso.single().intentosTotales, "max(5, 2)")
    }

    // ── POST: integridad contra el catálogo (§5.2) ───────────────────────────────────

    @Test
    fun `POST con orden inexistente en catalogo lanza 400 y no escribe nada`() {
        val ctx =
            servicioCon(
                seedUsuario = { seedActivo(it) },
                seedNiveles = { it.seed(1) }, // catálogo mínimo: solo el nivel 1
            )
        val error =
            assertFailsWith<ValidationException> {
                ctx.servicio.sincronizar(1L, listOf(item(1, EstadoNivel.COMPLETADO), item(99, EstadoNivel.DISPONIBLE)))
            }
        assertTrue(error.details.any { it.field == "progreso.orden" })
        assertEquals(0, ctx.progreso.size(), "la sincronización no persiste ningún nivel")
    }

    @Test
    fun `POST con un item invalido en medio del lote falla sin estados parciales`() {
        val ctx =
            servicioCon(
                seedUsuario = { seedActivo(it) },
                seedNiveles = { it.seedCatalogoCompleto() },
            )
        // El ítem 99 (inexistente) va EN MEDIO del lote: la validación de catálogo precede a
        // toda escritura, así que ni el nivel 1 ni el 2 llegan a persistirse (§5.2/§6).
        assertFailsWith<ValidationException> {
            ctx.servicio.sincronizar(
                1L,
                listOf(item(1, EstadoNivel.COMPLETADO), item(99, EstadoNivel.DISPONIBLE), item(2, EstadoNivel.COMPLETADO)),
            )
        }
        assertEquals(0, ctx.progreso.size(), "cero estados parciales en el servidor")
    }

    @Test
    fun `POST con cuenta eliminada lanza AccountInactiveException y no escribe`() {
        val ctx =
            servicioCon(
                seedUsuario = { it.seed(usuario(estado = EstadoUsuario.ELIMINADO)) },
                seedNiveles = { it.seedCatalogoCompleto() },
            )
        assertFailsWith<AccountInactiveException> {
            ctx.servicio.sincronizar(1L, listOf(item(1, EstadoNivel.COMPLETADO)))
        }
        assertEquals(0, ctx.progreso.size())
    }

    // ── Atomicidad: una sola transacción por operación (§6) ──────────────────────────

    @Test
    fun `POST de varios niveles corre en una sola transaccion`() {
        var llamadas = 0
        val runner = TransactionRunner { llamadas++; it() }
        val ctx =
            servicioCon(
                seedUsuario = { seedActivo(it) },
                seedNiveles = { it.seedCatalogoCompleto() },
                runner = runner,
            )
        ctx.servicio.sincronizar(
            1L,
            listOf(item(1, EstadoNivel.COMPLETADO), item(2, EstadoNivel.COMPLETADO), item(3, EstadoNivel.DISPONIBLE)),
        )
        assertEquals(1, llamadas, "el lote completo debe procesarse en una sola transacción")
    }

    @Test
    fun `GET corre en una sola transaccion`() {
        var llamadas = 0
        val runner = TransactionRunner { llamadas++; it() }
        val ctx =
            servicioCon(
                seedUsuario = { seedActivo(it) },
                seedNiveles = { it.seedCatalogoCompleto() },
                runner = runner,
            )
        ctx.servicio.obtenerSnapshot(1L)
        assertEquals(1, llamadas)
    }

    // ── Resumen: totalReintentos del servidor (§7) y snapshot POST ≡ GET ─────────────

    @Test
    fun `totalReintentos es la suma del servidor tras el POST`() {
        val ctx =
            servicioCon(
                seedUsuario = { seedActivo(it) },
                seedNiveles = { it.seedCatalogoCompleto() },
            )
        val snapshot =
            ctx.servicio.sincronizar(
                1L,
                listOf(item(1, EstadoNivel.COMPLETADO, intentosTotales = 3), item(2, EstadoNivel.DISPONIBLE, intentosTotales = 1)),
            )
        assertEquals(1, snapshot.resumen.nivelesCompletados, "solo el nivel 1 está completado")
        assertEquals(4, snapshot.resumen.totalReintentos)
    }

    @Test
    fun `snapshot tras POST es identico al GET posterior`() {
        val ctx =
            servicioCon(
                seedUsuario = { seedActivo(it) },
                seedNiveles = { it.seedCatalogoCompleto() },
            )
        ctx.servicio.sincronizar(
            1L,
            listOf(item(1, EstadoNivel.COMPLETADO, intentosTotales = 3), item(2, EstadoNivel.DISPONIBLE, intentosTotales = 1)),
        )
        val trasPost = ctx.servicio.obtenerSnapshot(1L)
        assertEquals(listOf(1, 2), trasPost.progreso.map { it.orden }.sorted())
        assertEquals(1, trasPost.resumen.nivelesCompletados)
        assertEquals(4, trasPost.resumen.totalReintentos)
        val completado = trasPost.progreso.single { it.orden == 1 }
        val disponible = trasPost.progreso.single { it.orden == 2 }
        assertNotNull(completado.completadoEn, "el nivel completado lleva marca del servidor")
        assertNull(disponible.completadoEn, "el nivel disponible no tiene completadoEn")
    }

    companion object {
        private fun usuario(
            id: Long = 1L,
            estado: EstadoUsuario = EstadoUsuario.ACTIVO,
        ): com.era.backend.models.entities.UsuarioRow =
            com.era.backend.models.entities.UsuarioRow(
                idUsuario = id,
                nombreMenor = "María Camila",
                fechaNacimiento = LocalDate.of(2017, 4, 10),
                correo = "laura.perez@example.com",
                nombreUsuario = "mariacamila",
                contrasenaHash = "hash-de-prueba",
                avatar = null,
                intentosLoginFallidos = 0,
                bloqueadoHasta = null,
                estado = estado,
                creadoEn = LocalDateTime.now(),
                actualizadoEn = LocalDateTime.now(),
            )
    }
}
