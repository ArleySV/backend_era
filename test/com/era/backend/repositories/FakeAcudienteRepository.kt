package com.era.backend.repositories

import com.era.backend.models.entities.AcudienteRow

/**
 * Fake en memoria de [AcudienteRepository] para unit tests del Módulo A.1. Guarda las
 * filas insertadas para verificar la conversión transaccional sin MySQL.
 */
class FakeAcudienteRepository : AcudienteRepository {

    private val filas = mutableListOf<AcudienteRow>()
    private var siguienteId = 1L

    override fun insert(row: AcudienteRow): Long {
        val conId = row.copy(idAcudiente = siguienteId)
        siguienteId += 1
        filas += conId
        return conId.idAcudiente
    }

    fun size(): Int = filas.size

    fun todas(): List<AcudienteRow> = filas.toList()
}
