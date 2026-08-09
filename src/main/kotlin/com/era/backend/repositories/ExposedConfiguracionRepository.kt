package com.era.backend.repositories

import com.era.backend.models.entities.ConfiguracionRow
import com.era.backend.models.entities.ConfiguracionTable
import org.jetbrains.exposed.v1.jdbc.insert

/**
 * Implementación real de [ConfiguracionRepository] sobre Exposed (ARQUITECTURA_BASE.md
 * §2.4). Debe ejecutarse dentro de la transacción de conversión de `VerificationService`.
 */
class ExposedConfiguracionRepository : ConfiguracionRepository {

    /**
     * Crea la configuración por defecto: el insert solo escribe `id_usuario`; el resto lo
     * asigna la base con sus defaults de V1 (mínimo privilegio, no se envían datos que no
     * se necesitan).
     */
    override fun insert(row: ConfiguracionRow): Long {
        val id =
            (ConfiguracionTable.insert {
                it[ConfiguracionTable.idUsuario] = row.idUsuario.toInt()
            }) get ConfiguracionTable.idConfig
        return id.toLong()
    }
}
