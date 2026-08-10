package com.era.backend.repositories

import com.era.backend.models.entities.NivelTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Implementación real de [NivelRepository] sobre Exposed (ARQUITECTURA_BASE.md §2.4).
 * Debe ejecutarse dentro de la transacción de `ProgressSyncService`.
 */
class ExposedNivelRepository : NivelRepository {

    override fun ordenesExistentes(ordenes: Collection<Int>): Set<Int> {
        if (ordenes.isEmpty()) return emptySet()
        return NivelTable.selectAll()
            .where { NivelTable.orden inList ordenes.map { it.toUByte() } }
            .map { it[NivelTable.orden].toInt() }
            .toSet()
    }

    override fun findByIdOrden(orden: Int): Long? =
        NivelTable.selectAll()
            .where { NivelTable.orden eq orden.toUByte() }
            .firstOrNull()
            ?.let { it[NivelTable.idNivel].toLong() }

    override fun findOrdenesByIdNiveles(idNiveles: Collection<Long>): Map<Long, Int> {
        if (idNiveles.isEmpty()) return emptyMap()
        return NivelTable.selectAll()
            .where { NivelTable.idNivel inList idNiveles.map { it.toInt() } }
            .associate { it[NivelTable.idNivel].toLong() to it[NivelTable.orden].toInt() }
    }
}
