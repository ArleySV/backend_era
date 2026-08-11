package com.era.backend.storage

/**
 * Contrato de persistencia de archivos de avatar personalizado (Módulo I,
 * `modulo-i-analisis.md` §2.3 y §7.3).
 *
 * **Independencia aprobada (decisión 2026-08-10):** el resto de la aplicación (service,
 * controller, tests) depende ÚNICAMENTE de esta interfaz. La implementación concreta
 * ([LocalDiskAvatarStorage] hoy) se inyecta desde el wiring de `Application.kt`; migrar a
 * S3 implica crear una nueva implementación sin tocar la lógica de negocio.
 *
 * Contrato:
 * - [guardar] persiste o sobrescribe; la clave la genera SIEMPRE el servidor
 *   (`custom:<uuid>`), nunca el cliente.
 * - [leer] devuelve el binario y su **tipo MIME canónico** persistido (el derivado de los
 *   magic bytes al subir, no el `Content-Type` del cliente — §2.1), o `null` si la clave no
 *   existe (el 404 defensivo lo decide el service).
 * - [eliminar] es best-effort: borrar una clave inexistente es no-op; los errores de I/O se
 *   propagan como [AvatarStorageException] para que el service decida (compensación §2.4).
 *
 * Las claves son **opacas y anonimizadas** (UUID, §2.3): nunca contienen el nombre del
 * archivo original del cliente ni datos personales.
 */
interface AvatarStorage {

    /** Persiste (o sobrescribe) el binario bajo [clave] con su tipo MIME canónico. */
    fun guardar(clave: String, bytes: ByteArray, contentType: String)

    /** Lee el binario y su tipo MIME; `null` si [clave] no existe o está corrupta. */
    fun leer(clave: String): ContenidoAvatar?

    /** Elimina el archivo y su metadato; no-op si [clave] no existe. */
    fun eliminar(clave: String)
}

/**
 * Resultado de una lectura de avatar: el binario y su tipo MIME canónico (§3.2 del análisis:
 * el servido usa SIEMPRE este tipo, jamás el declarado por el cliente en la subida).
 */
data class ContenidoAvatar(
    val bytes: ByteArray,
    val contentType: String,
)

/**
 * Error de I/O del almacenamiento de avatares. No es una excepción de dominio: el plugin
 * StatusPages la mapea al 500 `INTERNAL_ERROR` genérico (sin detalle sensible al cliente,
 * §4.1); el detalle queda solo en el log del servidor.
 */
class AvatarStorageException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
