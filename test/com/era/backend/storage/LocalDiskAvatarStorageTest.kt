package com.era.backend.storage

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests de [LocalDiskAvatarStorage] (Módulo I, `modulo-i-analisis.md` §2.3). Usan un
 * directorio temporal real para validar: el ciclo guardar/leer/eliminar (con sidecar `.meta`
 * de MIME canónico), la escritura atómica (sin `.tmp` residual), el fail-fast del `init`
 * (decisión 7), el 404 defensivo ante datos corruptos y que las claves hostiles (`:` del
 * prefijo `custom:`, path traversal) nunca escapan del directorio base gracias al nombre de
 * archivo seguro (Base64 URL).
 */
class LocalDiskAvatarStorageTest {

    private lateinit var raiz: Path

    @BeforeTest
    fun crearDirectorioTemporal() {
        raiz = Files.createTempDirectory("avatar-storage-test")
    }

    @AfterTest
    fun limpiarDirectorioTemporal() {
        raiz.toFile().deleteRecursively()
    }

    /** Espejo de la codificación de la clase bajo test (Base64 URL sin padding). */
    private fun nombreSeguro(clave: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(clave.toByteArray(Charsets.UTF_8))

    private fun ruta(clave: String): Path = raiz.resolve(nombreSeguro(clave))

    // ── init fail-fast (decisión 7) ───────────────────────────────────────────────────

    @Test
    fun `el init crea el directorio si no existe`() {
        val inexistente = raiz.resolve("sub/nuevo")
        LocalDiskAvatarStorage(inexistente)
        assertTrue(Files.isDirectory(inexistente))
    }

    @Test
    fun `el init falla claramente si el directorio no se puede crear`() {
        val archivo = Files.createFile(raiz.resolve("no-es-directorio"))
        assertFailsWith<AvatarStorageException> { LocalDiskAvatarStorage(archivo) }
    }

    // ── guardar / leer ────────────────────────────────────────────────────────────────

    @Test
    fun `guardar y leer devuelve el binario con su MIME canonico`() {
        val storage = LocalDiskAvatarStorage(raiz)
        storage.guardar("custom:abc.jpg", bytesJpeg, "image/jpeg")
        val contenido = storage.leer("custom:abc.jpg")
        assertContentEquals(bytesJpeg, contenido!!.bytes)
        assertEquals("image/jpeg", contenido.contentType)
    }

    @Test
    fun `guardar sobrescribe la clave existente`() {
        val storage = LocalDiskAvatarStorage(raiz)
        storage.guardar("custom:abc.jpg", bytesJpeg, "image/jpeg")
        storage.guardar("custom:abc.jpg", bytesPng, "image/png")
        val contenido = storage.leer("custom:abc.jpg")!!
        assertContentEquals(bytesPng, contenido.bytes)
        assertEquals("image/png", contenido.contentType)
    }

    @Test
    fun `leer una clave inexistente devuelve null`() {
        assertNull(LocalDiskAvatarStorage(raiz).leer("custom:nadie.jpg"))
    }

    @Test
    fun `leer con sidecar ausente devuelve null (dato corrupto)`() {
        val storage = LocalDiskAvatarStorage(raiz)
        storage.guardar("custom:abc.jpg", bytesJpeg, "image/jpeg")
        Files.deleteIfExists(ruta("custom:abc.jpg.meta"))
        assertNull(storage.leer("custom:abc.jpg"))
    }

    @Test
    fun `leer con binario ausente devuelve null`() {
        val storage = LocalDiskAvatarStorage(raiz)
        storage.guardar("custom:abc.jpg", bytesJpeg, "image/jpeg")
        Files.deleteIfExists(ruta("custom:abc.jpg"))
        assertNull(storage.leer("custom:abc.jpg"))
    }

    // ── eliminar ─────────────────────────────────────────────────────────────────────

    @Test
    fun `eliminar quita el binario y el sidecar`() {
        val storage = LocalDiskAvatarStorage(raiz)
        storage.guardar("custom:abc.jpg", bytesJpeg, "image/jpeg")
        storage.eliminar("custom:abc.jpg")
        assertNull(storage.leer("custom:abc.jpg"))
        assertFalse(Files.exists(ruta("custom:abc.jpg")))
        assertFalse(Files.exists(ruta("custom:abc.jpg.meta")))
    }

    @Test
    fun `eliminar una clave inexistente es no-op`() {
        LocalDiskAvatarStorage(raiz).eliminar("custom:nadie.jpg") // no debe lanzar
    }

    // ── Validación de entrada ────────────────────────────────────────────────────────

    @Test
    fun `guardar bytes vacios lanza AvatarStorageException`() {
        assertFailsWith<AvatarStorageException> {
            LocalDiskAvatarStorage(raiz).guardar("custom:x.jpg", ByteArray(0), "image/jpeg")
        }
    }

    @Test
    fun `clave en blanco lanza AvatarStorageException`() {
        assertFailsWith<AvatarStorageException> {
            LocalDiskAvatarStorage(raiz).guardar("   ", bytesJpeg, "image/jpeg")
        }
    }

    @Test
    fun `una clave hostil no escapa del directorio base`() {
        val storage = LocalDiskAvatarStorage(raiz)
        storage.guardar("../fuera.jpg", bytesJpeg, "image/jpeg")
        assertContentEquals(bytesJpeg, storage.leer("../fuera.jpg")!!.bytes)
        assertFalse(Files.exists(raiz.parent.resolve("fuera.jpg")), "nada se escribe fuera del directorio base")
        assertTrue(Files.isRegularFile(ruta("../fuera.jpg")), "el archivo queda contenido dentro")
    }

    // ── Escritura atómica ────────────────────────────────────────────────────────────

    @Test
    fun `no queda archivo tmp residual tras guardar`() {
        val storage = LocalDiskAvatarStorage(raiz)
        storage.guardar("custom:abc.jpg", bytesJpeg, "image/jpeg")
        val nombres =
            Files.list(raiz).use { stream -> stream.map { it.fileName.toString() }.toList() }
        assertEquals(2, nombres.size, "solo binario + sidecar")
        assertTrue(nombres.none { it.endsWith(".tmp") }, "no debe quedar el temporal")
    }

    companion object {
        private val bytesJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01)
        private val bytesPng =
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00)
    }
}
