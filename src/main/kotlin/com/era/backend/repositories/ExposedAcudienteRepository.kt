package com.era.backend.repositories

import com.era.backend.models.entities.AcudienteRow
import com.era.backend.models.entities.AcudienteTable
import org.jetbrains.exposed.v1.jdbc.insert

/**
 * Implementación real de [AcudienteRepository] sobre Exposed (ARQUITECTURA_BASE.md §2.4).
 * Debe ejecutarse dentro de la transacción de conversión de `VerificationService`.
 */
class ExposedAcudienteRepository : AcudienteRepository {

    /**
     * Persiste la fila del acudiente ligada al usuario recién activado. `creado_en` y
     * `actualizado_en` los asigna la base (defaults de V1). Sin loguear la cédula.
     */
    override fun insert(row: AcudienteRow): Long {
        val id =
            (AcudienteTable.insert {
                it[AcudienteTable.idUsuario] = row.idUsuario.toInt()
                it[AcudienteTable.nombreCompleto] = row.nombreCompleto
                it[AcudienteTable.numeroCedula] = row.numeroCedula
            }) get AcudienteTable.idAcudiente
        return id.toLong()
    }
}
