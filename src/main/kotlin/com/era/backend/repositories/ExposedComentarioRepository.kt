package com.era.backend.repositories

import com.era.backend.models.entities.ComentarioTable
import org.jetbrains.exposed.v1.jdbc.insert

/**
 * Implementación real de [ComentarioRepository] sobre Exposed (ARQUITECTURA_BASE.md §2.4).
 * Debe ejecutarse dentro de la transacción de `ComentarioService` (`modulo-h-analisis.md`
 * §7.3): una sola inserción, pero dentro de `TransactionRunner` para mantener el estándar
 * de manejo de excepciones de Exposed.
 */
class ExposedComentarioRepository : ComentarioRepository {

    override fun insertar(idUsuario: Long, contenido: String): Long {
        val id =
            (ComentarioTable.insert {
                it[ComentarioTable.idUsuario] = idUsuario.toInt()
                it[ComentarioTable.contenido] = contenido
            }) get ComentarioTable.idComentario
        return id.toLong()
    }
}
