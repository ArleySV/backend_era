package com.era.backend.storage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Implementación de [AvatarStorage] sobre disco local (`modulo-i-analisis.md` §2.3, §7.3).
 *
 * El directorio base se resuelve de configuración (`AppConfig.storage.avatarDir`, nunca
 * hardcodeado). Garantías:
 * - **Anonimización:** las claves son UUID opacos generados por el servicio; el nombre
 *   original del archivo del cliente nunca llega aquí.
 * - **Anti path-traversal:** la clave se normaliza y se verifica que el path resultante quede
 *   dentro del directorio base. Es una barrera extra: por diseño es estructuralmente
 *   imposible en claves UUID (el service solo genera `custom:<uuid>`).
 * - **Escritura atómica:** los bytes se escriben a `<clave>.tmp` y se mueven con
 *   `ATOMIC_MOVE` a la clave definitiva; el tipo MIME canónico se persiste en un archivo
 *   sidecar `<clave>.meta`. Nunca queda un archivo parcial con la clave definitiva.
 * - **Fail-fast en arranque:** [init] crea el directorio o falla claramente ante permisos
 *   negados (decisión 7).
 *
 * La retención ante soft delete NO es responsabilidad de esta clase: `DELETE /me` no toca el
 * storage (§2.3); el service decide cuándo llamar a [eliminar] (compensación y vuelta a
 * preset, §2.4).
 */
class LocalDiskAvatarStorage(
    private val dir: Path,
) : AvatarStorage {

    init {
        try {
            Files.createDirectories(dir)
        } catch (e: Exception) {
            throw AvatarStorageException("No se pudo inicializar el directorio de avatares.", e)
        }
    }

    override fun guardar(clave: String, bytes: ByteArray, contentType: String) {
        if (bytes.isEmpty()) throw AvatarStorageException("El archivo de avatar está vacío.")
        val destino = resolver(clave)
        val temporal = resolver(clave + ".tmp")
        try {
            Files.write(temporal, bytes)
            Files.move(
                temporal,
                destino,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            Files.writeString(resolver(clave + ".meta"), contentType)
        } catch (e: Exception) {
            throw AvatarStorageException("No se pudo guardar el avatar.", e)
        } finally {
            try {
                Files.deleteIfExists(temporal)
            } catch (_: Exception) {
                // No enmascarar el error original: el .tmp residual queda como huérfano (limpia
                // la limpieza de claves del service, §2.4).
            }
        }
    }

    override fun leer(clave: String): ContenidoAvatar? {
        val destino = resolver(clave)
        val meta = resolver(clave + ".meta")
        return try {
            // Archivo de bytes O metadato ausente → dato corrupto/incompleto → null (el
            // service lo traduce a 404 defensivo; §4.1).
            if (!Files.isRegularFile(destino) || !Files.isRegularFile(meta)) return null
            ContenidoAvatar(
                bytes = Files.readAllBytes(destino),
                contentType = Files.readString(meta),
            )
        } catch (e: Exception) {
            throw AvatarStorageException("No se pudo leer el avatar.", e)
        }
    }

    override fun eliminar(clave: String) {
        try {
            Files.deleteIfExists(resolver(clave))
            Files.deleteIfExists(resolver(clave + ".meta"))
        } catch (e: Exception) {
            throw AvatarStorageException("No se pudo eliminar el avatar.", e)
        }
    }

    /** Resuelve [clave] contra el directorio base y verifica que el path quede dentro de él. */
    private fun resolver(clave: String): Path {
        if (clave.isBlank()) throw AvatarStorageException("Clave de avatar vacía.")
        val ruta = dir.resolve(clave).normalize()
        if (!ruta.startsWith(dir)) {
            throw AvatarStorageException("Clave de avatar inválida.")
        }
        return ruta
    }
}
