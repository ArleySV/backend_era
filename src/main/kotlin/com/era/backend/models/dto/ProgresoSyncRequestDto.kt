package com.era.backend.models.dto

import kotlinx.serialization.Serializable

/**
 * Contrato de request de `POST /api/v1/progress/sync` (`modulo-g-analisis.md` §4.2).
 *
 * El cliente envía el **estado completo de los 20 niveles** (todos o solo los que tienen
 * actividad; ambos son válidos, el merge lo absorbe). `progreso` es nullable en el DTO
 * para que la ausencia del campo se traduzca a 400 `VALIDATION_ERROR` con `details` en el
 * controller (no a `INVALID_REQUEST`).
 */
@Serializable
data class ProgresoSyncRequestDto(
    val progreso: List<ProgresoSyncItemDto>? = null,
)
