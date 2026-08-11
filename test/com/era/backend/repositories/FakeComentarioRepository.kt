package com.era.backend.repositories

/**
 * Fake en memoria de [ComentarioRepository] para los tests del Módulo H. Reproduce el
 * contrato de `insertar` (id secuencial) y permite inspeccionar lo persistido, incluida la
 * sanitización `.trim()` que `ComentarioService` aplica antes de llegar aquí (§7.3).
 */
class FakeComentarioRepository : ComentarioRepository {

    data class Registro(
        val idComentario: Long,
        val idUsuario: Long,
        val contenido: String,
    )

    private val registros = mutableListOf<Registro>()
    private var siguienteId = 1L

    fun size(): Int = registros.size

    fun todos(): List<Registro> = registros.toList()

    fun porUsuario(idUsuario: Long): List<Registro> = registros.filter { it.idUsuario == idUsuario }

    override fun insertar(idUsuario: Long, contenido: String): Long {
        val id = siguienteId++
        registros += Registro(id, idUsuario, contenido)
        return id
    }
}
