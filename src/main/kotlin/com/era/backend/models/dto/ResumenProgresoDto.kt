package com.era.backend.models.dto

import kotlinx.serialization.Serializable

/**
 * Resumen agregado del snapshot (`modulo-g-analisis.md` §7), calculado **en el servidor**
 * sobre `progreso_usuario`:
 * - `nivelesCompletados` = `COUNT(estado_nivel = 'completado')`.
 * - `totalNiveles` = 20 (catálogo, REQ-FUN-10).
 * - `totalReintentos` = `SUM(intentos_totales)` de todos los niveles del usuario.
 *
 * El porcentaje (`nivelesCompletados / totalNiveles × 100`) lo calcula **el cliente**
 * (REQ-FUN-12 CA1); el servidor entrega los datos base.
 */
@Serializable
data class ResumenProgresoDto(
    val nivelesCompletados: Int,
    val totalNiveles: Int,
    val totalReintentos: Int,
)
