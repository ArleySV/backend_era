package com.era.backend.storage

/**
 * Fake en memoria de [AvatarStorage] para unit tests del Módulo I. No toca disco: reproduce
 * exactamente el contrato de la interfaz (guardar/leer/eliminar) para que `AvatarServiceTest`
 * y los tests HTTP validen la lógica de negocio y la compensación (§2.4) sin I/O.
 *
 * Confirma la **independencia de la interfaz** (§7.3): el service y los tests dependen solo de
 * [AvatarStorage], nunca de la implementación concreta.
 */
class FakeAvatarStorage : AvatarStorage {

    private val archivos = mutableMapOf<String, ContenidoAvatar>()

    /** Claves `custom:*` presentes en memoria (para assertar huérfanos/compensación). */
    fun claves(): Set<String> = archivos.keys

    fun contiene(clave: String): Boolean = archivos.containsKey(clave)

    override fun guardar(clave: String, bytes: ByteArray, contentType: String) {
        archivos[clave] = ContenidoAvatar(bytes, contentType)
    }

    override fun leer(clave: String): ContenidoAvatar? = archivos[clave]

    override fun eliminar(clave: String) {
        archivos.remove(clave)
    }
}
