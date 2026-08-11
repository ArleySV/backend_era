package com.era.backend.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests de [AvatarValidador] (Módulo I, `modulo-i-analisis.md` §2.1). La detección por
 * magic bytes es la validación **autoritativa**: el `Content-Type` del cliente puede falsearse,
 * el binario no. Se cubren las tres firmas de la whitelist (JPEG/PNG/WebP con sus fourCCs
 * `VP8 `, `VP8L` y `VP8X`) y los rechazos por truncamiento, cabecera corrupta o datos basura.
 */
class AvatarValidadorTest {

    @Test
    fun `MAX_TAMANO_BYTES es exactamente 2 MB`() {
        assertEquals(2 * 1024 * 1024, AvatarValidador.MAX_TAMANO_BYTES)
    }

    // ── Formatos admitidos (whitelist §2.1) ───────────────────────────────────────────

    @Test
    fun `detecta JPEG por la firma FF D8 FF`() {
        assertEquals(FormatoAvatar.JPEG, AvatarValidador.detectarFormato(jpeg()))
    }

    @Test
    fun `detecta PNG por la firma completa de 8 bytes`() {
        assertEquals(FormatoAvatar.PNG, AvatarValidador.detectarFormato(png()))
    }

    @Test
    fun `detecta WebP con fourCC VP8`() {
        assertEquals(FormatoAvatar.WEBP, AvatarValidador.detectarFormato(webp("VP8 ")))
    }

    @Test
    fun `detecta WebP con fourCC VP8L`() {
        assertEquals(FormatoAvatar.WEBP, AvatarValidador.detectarFormato(webp("VP8L")))
    }

    @Test
    fun `detecta WebP con fourCC VP8X`() {
        assertEquals(FormatoAvatar.WEBP, AvatarValidador.detectarFormato(webp("VP8X")))
    }

    @Test
    fun `MIME canonicos y extensiones de la whitelist`() {
        assertEquals("image/jpeg", FormatoAvatar.JPEG.contentType)
        assertEquals("image/png", FormatoAvatar.PNG.contentType)
        assertEquals("image/webp", FormatoAvatar.WEBP.contentType)
        assertEquals("jpg", FormatoAvatar.JPEG.extension)
        assertEquals("png", FormatoAvatar.PNG.extension)
        assertEquals("webp", FormatoAvatar.WEBP.extension)
    }

    // ── Rechazos ──────────────────────────────────────────────────────────────────────

    @Test
    fun `bytes vacios no son un formato valido`() {
        assertNull(AvatarValidador.detectarFormato(byteArrayOf()))
    }

    @Test
    fun `datos basura no son un formato valido`() {
        assertNull(AvatarValidador.detectarFormato(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
    }

    @Test
    fun `JPEG truncado (solo FF D8) no es valido`() {
        assertNull(AvatarValidador.detectarFormato(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
    }

    @Test
    fun `PNG truncado (7 de 8 bytes) no es valido`() {
        assertNull(AvatarValidador.detectarFormato(png().copyOf(7)))
    }

    @Test
    fun `RIFF sin WEBP no es valido`() {
        assertNull(
            AvatarValidador.detectarFormato("RIFF".toByteArray() + "WAVE".toByteArray() + "fmt ".toByteArray()),
        )
    }

    @Test
    fun `RIFF WEBP con fourCC de chunk desconocido no es valido`() {
        assertNull(AvatarValidador.detectarFormato(webp("XXXX")))
    }

    @Test
    fun `cabecera WebP menor a 16 bytes no es valida`() {
        val corta = "RIFF".toByteArray() + "WEBP".toByteArray() // solo 12 bytes
        assertNull(AvatarValidador.detectarFormato(corta))
    }

    private fun jpeg(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01, 0x02, 0x03)

    private fun png(): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01)

    /** Cabecera WebP mínima (16 bytes): `RIFF` + tamaño + `WEBP` + fourCC de chunk. */
    private fun webp(fourCC: String): ByteArray =
        "RIFF".toByteArray() +
            byteArrayOf(0x00, 0x00, 0x00, 0x00) +
            "WEBP".toByteArray() +
            fourCC.toByteArray()
}
