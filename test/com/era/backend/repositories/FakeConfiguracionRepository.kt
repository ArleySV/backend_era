package com.era.backend.repositories

import com.era.backend.models.entities.ConfiguracionRow

/**
 * Fake en memoria de [ConfiguracionRepository] para unit tests del Módulo A.1. Guarda las
 * filas insertadas para verificar la conversión transaccional sin MySQL.
 */
class FakeConfiguracionRepository : ConfiguracionRepository {

    private val filas = mutableListOf<ConfiguracionRow>()
    private var siguienteId = 1L

    override fun insert(row: ConfiguracionRow): Long {
        val conId = row.copy(idConfig = siguienteId)
        siguienteId += 1
        filas += conId
        return conId.idConfig
    }

    fun size(): Int = filas.size

    fun todas(): List<ConfiguracionRow> = filas.toList()
}
