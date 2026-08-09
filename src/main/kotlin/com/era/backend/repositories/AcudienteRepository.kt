package com.era.backend.repositories

import com.era.backend.models.entities.AcudienteRow

/**
 * Acceso a datos de `acudiente` (ARQUITECTURA_BASE.md §2.4).
 *
 * Interfaz para que `VerificationService` (y los tests) dependan de la abstracción, no
 * de Exposed: producción usa [ExposedAcudienteRepository]; los tests usan un fake en
 * memoria (sin MySQL).
 *
 * En el Módulo A.1 solo expone el alta; el `insert` debe ejecutarse dentro de la misma
 * transacción que crea `usuario` y `configuracion` (conversión atómica de
 * `registro_pendiente`, V1:55-57).
 */
interface AcudienteRepository {

    /**
     * Persiste la fila del acudiente (HU-15 CA1) ligada al usuario recién activado.
     *
     * Seguridad (CLAUDE.md §6): nunca loguear `numero_cedula` en texto plano.
     *
     * @return id de la fila creada.
     */
    fun insert(row: AcudienteRow): Long
}
