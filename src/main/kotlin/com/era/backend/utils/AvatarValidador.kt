package com.era.backend.utils

/**
 * Detección del formato de imagen por **magic bytes** (validación autoritativa del Módulo I,
 * `modulo-i-analisis.md` §2.1). El `Content-Type` de la parte multipart NO es autoritativo:
 * puede ser falseado por el cliente; el binario es la fuente de verdad.
 *
 * Formatos admitidos (whitelist):
 * - JPEG: `FF D8 FF`
 * - PNG: `89 50 4E 47 0D 0A 1A 0A`
 * - WebP: `RIFF` + `WEBP` en offset 8 + fourCC de chunk `VP8 `, `VP8L` o `VP8X` en offset 12
 *
 * El fourCC de WebP es el sanity-check estructural de la decisión 7 (decisión aprobada
 * 2026-08-10): el JDK no incluye decodificador WebP, por lo que el chequeo estructural es el
 * gatekeeper mandatorio para ese formato; para JPEG/PNG el decode con `ImageIO` queda como
 * hardening opcional, no como barrera de aceptación (§2.1).
 */
object AvatarValidador {

    /** Tamaño máximo de la foto personalizada (decisión 7, §2.1): 2 MB. */
    const val MAX_TAMANO_BYTES: Int = 2 * 1024 * 1024

    private val FIRMA_JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val FIRMA_PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val RIFF = byteArrayOf(0x52, 0x49, 0x46, 0x46) // "RIFF"
    private val WEBP = byteArrayOf(0x57, 0x45, 0x42, 0x50) // "WEBP"
    private val FOURCC_VP8 = byteArrayOf(0x56, 0x50, 0x38, 0x20) // "VP8 "
    private val FOURCC_VP8L = byteArrayOf(0x56, 0x50, 0x38, 0x4C) // "VP8L"
    private val FOURCC_VP8X = byteArrayOf(0x56, 0x50, 0x38, 0x58) // "VP8X"

    /**
     * Detecta el formato de la imagen por su firma binaria, o `null` si no es un formato
     * permitido o el binario está truncado/corrupto (daño detectado por longitud).
     */
    fun detectarFormato(bytes: ByteArray): FormatoAvatar? {
        if (empiezaCon(bytes, FIRMA_JPEG)) return FormatoAvatar.JPEG
        if (empiezaCon(bytes, FIRMA_PNG)) return FormatoAvatar.PNG
        if (esWebp(bytes)) return FormatoAvatar.WEBP
        return null
    }

    private fun esWebp(bytes: ByteArray): Boolean {
        if (bytes.size < 16) return false
        if (!empiezaCon(bytes, RIFF)) return false
        if (!empiezaCon(bytes, WEBP, offset = 8)) return false
        val fourCC = bytes.copyOfRange(12, 16)
        return fourCC.contentEquals(FOURCC_VP8) ||
            fourCC.contentEquals(FOURCC_VP8L) ||
            fourCC.contentEquals(FOURCC_VP8X)
    }

    private fun empiezaCon(bytes: ByteArray, firma: ByteArray, offset: Int = 0): Boolean {
        if (offset + firma.size > bytes.size) return false
        for (i in firma.indices) {
            if (bytes[offset + i] != firma[i]) return false
        }
        return true
    }
}

/**
 * Formatos de imagen admitidos por el Módulo I (whitelist, `modulo-i-analisis.md` §2.1).
 * [extension] es la extensión canónica usada en la clave de storage (`custom:<uuid>.<ext>`,
 * §2.2) y [contentType] el tipo MIME canónico que se persiste y se sirve (§3.2), derivado
 * de los magic bytes — nunca el declarado por el cliente.
 */
enum class FormatoAvatar(val extension: String, val contentType: String) {
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    WEBP("webp", "image/webp"),
}
