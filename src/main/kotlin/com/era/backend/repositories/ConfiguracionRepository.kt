package com.era.backend.repositories

import com.era.backend.models.entities.ConfiguracionRow

/**
 * Acceso a datos de `configuracion` (ARQUITECTURA_BASE.md §2.4).
 *
 * Interfaz para que `VerificationService` (y los tests) dependan de la abstracción, no
 * de Exposed: producción usa [ExposedConfiguracionRepository]; los tests usan un fake en
 * memoria (sin MySQL).
 *
 * En el Módulo A.1 solo expone el alta; el `insert` debe ejecutarse dentro de la misma
 * transacción que crea `usuario` y `acudiente` (conversión atómica de
 * `registro_pendiente`, V1:55-57).
 */
interface ConfiguracionRepository {

    /**
     * Crea la configuración por defecto del usuario (REQ-FUN-13, CU-09): solo se
     * persiste `id_usuario`; `sonido`/`musica`/`tema_visual`/`tamano_texto` quedan en
     * sus defaults de V1.
     *
     * @return id de la fila creada.
     */
    fun insert(row: ConfiguracionRow): Long
}
