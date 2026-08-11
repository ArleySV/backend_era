package com.era.backend.services

import com.era.backend.exceptions.AccountInactiveException
import com.era.backend.exceptions.NotFoundException
import com.era.backend.exceptions.ValidationException
import com.era.backend.models.entities.EstadoUsuario
import com.era.backend.models.entities.UsuarioRow
import com.era.backend.repositories.FakeUsuarioRepository
import com.era.backend.repositories.TransactionRunner
import com.era.backend.storage.AvatarStorage
import com.era.backend.storage.AvatarStorageException
import com.era.backend.storage.ContenidoAvatar
import com.era.backend.storage.FakeAvatarStorage
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests de [AvatarService] (Módulo I, `modulo-i-analisis.md` §2.4 y §4). Sin MySQL ni
 * disco: `FakeUsuarioRepository` + `FakeAvatarStorage`. Cubre el happy path (subida con clave
 * `custom:<uuid>` y GET con MIME canónico), el reemplazo (borrado best-effort de la foto
 * anterior `custom:*`), las validaciones de negocio (§2.1: tamaño, magic bytes, doble
 * validación MIME), los 403/404 y la **compensación** (§2.4): si `actualizarAvatar` falla tras
 * escribir el archivo, el archivo recién escrito se elimina y el error original se propaga sin
 * enmascararse.
 */
class AvatarServiceTest {

    private fun servicioCon(
        seedUsuario: (FakeUsuarioRepository) -> Unit,
        storage: AvatarStorage = FakeAvatarStorage(),
        runner: TransactionRunner = TransactionRunner { it() },
    ): Contexto {
        val usuarios = FakeUsuarioRepository()
        seedUsuario(usuarios)
        return Contexto(AvatarService(usuarios, storage, runner), usuarios, storage)
    }

    /** Contexto de una prueba: service + fake de usuarios + storage (para assertar estado). */
    class Contexto(
        val servicio: AvatarService,
        val usuarios: FakeUsuarioRepository,
        val storage: AvatarStorage,
    )

    private fun usuario(
        id: Long = 1L,
        avatar: String? = null,
        estado: EstadoUsuario = EstadoUsuario.ACTIVO,
    ): UsuarioRow =
        UsuarioRow(
            idUsuario = id,
            nombreMenor = "María Camila",
            fechaNacimiento = LocalDate.of(2017, 4, 10),
            correo = "laura.perez@example.com",
            nombreUsuario = "mariacamila",
            contrasenaHash = "hash-de-prueba",
            avatar = avatar,
            intentosLoginFallidos = 0,
            bloqueadoHasta = null,
            estado = estado,
            creadoEn = LocalDateTime.now(),
            actualizadoEn = LocalDateTime.now(),
        )

    private fun seedActivo(usuarios: FakeUsuarioRepository): FakeUsuarioRepository {
        usuarios.seed(usuario())
        return usuarios
    }

    // ── Happy path ────────────────────────────────────────────────────────────────────

    @Test
    fun `sube un JPEG y devuelve la confirmacion`() {
        val ctx = servicioCon(seedUsuario = { seedActivo(it) })
        val respuesta = ctx.servicio.subirAvatar(1L, jpeg(), "image/jpeg")
        assertEquals(AvatarService.MENSAJE_AVATAR_ACTUALIZADO, respuesta.message)
        val clave = ctx.usuarios.findById(1L)!!.avatar!!
        assertTrue(clave.startsWith("custom:"))
        assertTrue(clave.endsWith(".jpg"))
        assertEquals(1, (ctx.storage as FakeAvatarStorage).claves().size)
    }

    @Test
    fun `la clave generada es custom con UUID y extension canonica`() {
        val ctx = servicioCon(seedUsuario = { seedActivo(it) })
        ctx.servicio.subirAvatar(1L, png(), "image/png")
        val clave = ctx.usuarios.findById(1L)!!.avatar!!
        assertTrue(clave.matches(Regex("^custom:[0-9a-fA-F-]{36}\\.png$")), "clave: $clave")
    }

    @Test
    fun `subir sobre una foto custom elimina la foto anterior`() {
        val ctx = servicioCon(seedUsuario = { it.seed(usuario(avatar = "custom:vieja.png")) })
        val storage = ctx.storage as FakeAvatarStorage
        storage.guardar("custom:vieja.png", png(), "image/png")
        ctx.servicio.subirAvatar(1L, jpeg(), "image/jpeg")
        assertFalse(storage.contiene("custom:vieja.png"), "la foto anterior debe borrarse")
        assertTrue(ctx.usuarios.findById(1L)!!.avatar!!.endsWith(".jpg"))
        assertEquals(1, storage.claves().size, "solo debe quedar la foto nueva")
    }

    @Test
    fun `subir sobre un preset no intenta borrar archivos`() {
        val ctx = servicioCon(seedUsuario = { it.seed(usuario(avatar = "preset:1")) })
        val storage = ctx.storage as FakeAvatarStorage
        ctx.servicio.subirAvatar(1L, jpeg(), "image/jpeg")
        assertEquals(1, storage.claves().size, "los presets no tienen archivo que borrar")
    }

    // ── Validaciones de negocio (§2.1) ───────────────────────────────────────────────

    @Test
    fun `formato no permitido lanza ValidationException sin persistir`() {
        val ctx = servicioCon(seedUsuario = { seedActivo(it) })
        val ex = assertFailsWith<ValidationException> {
            ctx.servicio.subirAvatar(1L, byteArrayOf(1, 2, 3, 4), "image/png")
        }
        assertEquals("avatar", ex.details.single().field)
        assertEquals(0, (ctx.storage as FakeAvatarStorage).claves().size)
        assertNull(ctx.usuarios.findById(1L)!!.avatar)
    }

    @Test
    fun `tamano mayor a 2 MB lanza ValidationException sin persistir`() {
        val ctx = servicioCon(seedUsuario = { seedActivo(it) })
        val ex = assertFailsWith<ValidationException> {
            ctx.servicio.subirAvatar(1L, ByteArray(2 * 1024 * 1024 + 1), "image/jpeg")
        }
        assertEquals("avatar", ex.details.single().field)
        assertEquals(0, (ctx.storage as FakeAvatarStorage).claves().size)
    }

    @Test
    fun `Content-Type declarado que no coincide con los magic bytes lanza ValidationException`() {
        val ctx = servicioCon(seedUsuario = { seedActivo(it) })
        assertFailsWith<ValidationException> {
            ctx.servicio.subirAvatar(1L, jpeg(), "image/png")
        }
        assertEquals(0, (ctx.storage as FakeAvatarStorage).claves().size)
    }

    @Test
    fun `Content-Type nulo lanza ValidationException`() {
        val ctx = servicioCon(seedUsuario = { seedActivo(it) })
        assertFailsWith<ValidationException> {
            ctx.servicio.subirAvatar(1L, jpeg(), null)
        }
        assertEquals(0, (ctx.storage as FakeAvatarStorage).claves().size)
    }

    // ── Cuenta inactiva / inexistente ────────────────────────────────────────────────

    @Test
    fun `cuenta eliminada lanza AccountInactiveException sin escribir archivo`() {
        val ctx =
            servicioCon(seedUsuario = { it.seed(usuario(estado = EstadoUsuario.ELIMINADO)) })
        assertFailsWith<AccountInactiveException> {
            ctx.servicio.subirAvatar(1L, jpeg(), "image/jpeg")
        }
        assertEquals(0, (ctx.storage as FakeAvatarStorage).claves().size, "nada se persiste de una cuenta inactiva")
    }

    @Test
    fun `usuario inexistente lanza NotFoundException sin escribir archivo`() {
        val ctx = servicioCon(seedUsuario = { seedActivo(it) })
        assertFailsWith<NotFoundException> {
            ctx.servicio.subirAvatar(99L, jpeg(), "image/jpeg")
        }
        assertEquals(0, (ctx.storage as FakeAvatarStorage).claves().size)
    }

    // ── Compensación (§2.4) ──────────────────────────────────────────────────────────

    @Test
    fun `si actualizarAvatar falla se elimina el archivo recien escrito y se propaga el error`() {
        val usuarios = FakeUsuarioRepository().apply { seed(usuario()) }
        val storage = FakeAvatarStorage()
        var transacciones = 0
        val runner = TransactionRunner {
            transacciones++
            if (transacciones == 2) throw RuntimeException("caída simulada de BD")
            it()
        }
        val servicio = AvatarService(usuarios, storage, runner)
        val ex = assertFailsWith<RuntimeException> {
            servicio.subirAvatar(1L, jpeg(), "image/jpeg")
        }
        assertEquals("caída simulada de BD", ex.message, "el error original no se enmascara")
        assertTrue(storage.claves().isEmpty(), "no debe quedar huérfano en disco")
        assertNull(usuarios.findById(1L)!!.avatar, "la BD no cambia")
    }

    @Test
    fun `un fallo al eliminar en la compensacion no enmascara el error original`() {
        val usuarios = FakeUsuarioRepository().apply { seed(usuario()) }
        var transacciones = 0
        val runner = TransactionRunner {
            transacciones++
            if (transacciones == 2) throw RuntimeException("caída simulada de BD")
            it()
        }
        val servicio = AvatarService(usuarios, StorageQueFallaAlEliminar(), runner)
        val ex = assertFailsWith<RuntimeException> {
            servicio.subirAvatar(1L, jpeg(), "image/jpeg")
        }
        assertEquals("caída simulada de BD", ex.message)
    }

    // ── GET ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `GET devuelve el binario con su MIME canonico`() {
        val ctx = servicioCon(seedUsuario = { it.seed(usuario(avatar = "custom:foto.png")) })
        val storage = ctx.storage as FakeAvatarStorage
        storage.guardar("custom:foto.png", png(), "image/png")
        val contenido = ctx.servicio.obtenerAvatar(1L)
        assertTrue(contenido.bytes.contentEquals(png()))
        assertEquals("image/png", contenido.contentType)
    }

    @Test
    fun `GET sin avatar personalizado lanza NotFoundException`() {
        val ctx = servicioCon(seedUsuario = { seedActivo(it) })
        assertFailsWith<NotFoundException> { ctx.servicio.obtenerAvatar(1L) }
    }

    @Test
    fun `GET con avatar preset lanza NotFoundException`() {
        val ctx = servicioCon(seedUsuario = { it.seed(usuario(avatar = "preset:2")) })
        assertFailsWith<NotFoundException> { ctx.servicio.obtenerAvatar(1L) }
    }

    @Test
    fun `GET con clave custom pero archivo ausente lanza NotFoundException defensiva`() {
        val ctx = servicioCon(seedUsuario = { it.seed(usuario(avatar = "custom:foto.jpg")) })
        assertFailsWith<NotFoundException> { ctx.servicio.obtenerAvatar(1L) }
    }

    @Test
    fun `GET de cuenta eliminada lanza AccountInactiveException`() {
        val ctx =
            servicioCon(seedUsuario = {
                it.seed(usuario(avatar = "custom:foto.jpg", estado = EstadoUsuario.ELIMINADO))
            })
        assertFailsWith<AccountInactiveException> { ctx.servicio.obtenerAvatar(1L) }
    }

    @Test
    fun `GET de usuario inexistente lanza NotFoundException`() {
        val ctx = servicioCon(seedUsuario = { seedActivo(it) })
        assertFailsWith<NotFoundException> { ctx.servicio.obtenerAvatar(99L) }
    }

    // ── Bloque reutilizable para el futuro PATCH (§2.4) ──────────────────────────────

    @Test
    fun `eliminarAvatarSiPersonalizado no toca el storage con null o preset`() {
        val storage = FakeAvatarStorage()
        val servicio = AvatarService(FakeUsuarioRepository(), storage, TransactionRunner { it() })
        servicio.eliminarAvatarSiPersonalizado(null)
        servicio.eliminarAvatarSiPersonalizado("preset:1")
        assertTrue(storage.claves().isEmpty())
    }

    @Test
    fun `eliminarAvatarSiPersonalizado borra una clave custom y tolera fallos de I-O`() {
        val storage = FakeAvatarStorage()
        storage.guardar("custom:vieja.jpg", jpeg(), "image/jpeg")
        val servicio = AvatarService(FakeUsuarioRepository(), storage, TransactionRunner { it() })
        servicio.eliminarAvatarSiPersonalizado("custom:vieja.jpg")
        assertTrue(storage.claves().isEmpty())
        // Fallo de I/O: best-effort, no debe lanzar.
        AvatarService(FakeUsuarioRepository(), StorageQueFallaAlEliminar(), TransactionRunner { it() })
            .eliminarAvatarSiPersonalizado("custom:algo.jpg")
    }

    companion object {
        private fun jpeg(): ByteArray =
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01, 0x02)

        private fun png(): ByteArray =
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00)
    }

    /** Storage cuyo `eliminar` siempre falla: verifica que la compensación no enmascare el error original. */
    private class StorageQueFallaAlEliminar : AvatarStorage {
        override fun guardar(clave: String, bytes: ByteArray, contentType: String) = Unit

        override fun leer(clave: String): ContenidoAvatar? = null

        override fun eliminar(clave: String): Unit = throw AvatarStorageException("fallo de I/O simulado")
    }
}
