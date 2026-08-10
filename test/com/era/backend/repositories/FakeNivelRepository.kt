package com.era.backend.repositories

import com.era.backend.services.ProgressSyncService

/**
 * Fake en memoria de [NivelRepository] para los unit/route tests del Módulo G. No toca
 * MySQL: reproduce el catálogo `nivel` como un mapa `orden → idNivel` (V1: `orden` 1..20,
 * `UNIQUE`).
 */
class FakeNivelRepository : NivelRepository {

    /** Catálogo sembrado: `orden → idNivel`. */
    private val niveles = mutableMapOf<Int, Long>()
    private var siguienteId = 1L

    /** Siembra un nivel del catálogo; si [idNivel] se omite, asigna uno secuencial. */
    fun seed(orden: Int, idNivel: Long = siguienteId++): Long {
        niveles[orden] = idNivel
        return idNivel
    }

    /**
     * Catálogo completo estándar: ordenes `1..[ProgressSyncService.TOTAL_NIVELES]`, salvo
     * [sinOrden] si se indica (para probar la validación de integridad §5.2 con un orden
     * dentro de rango que no existe en el catálogo).
     */
    fun seedCatalogoCompleto(sinOrden: Int = -1) {
        for (orden in 1..ProgressSyncService.TOTAL_NIVELES) {
            if (orden != sinOrden) seed(orden)
        }
    }

    /** Devuelve el idNivel asignado a un orden, si existe. */
    fun idNivelDe(orden: Int): Long? = niveles[orden]

    override fun ordenesExistentes(ordenes: Collection<Int>): Set<Int> =
        niveles.keys intersect ordenes.toSet()

    override fun findByIdOrden(orden: Int): Long? = niveles[orden]

    override fun findOrdenesByIdNiveles(idNiveles: Collection<Long>): Map<Long, Int> =
        niveles.entries
            .filter { it.value in idNiveles }
            .associate { it.value to it.key }
}
