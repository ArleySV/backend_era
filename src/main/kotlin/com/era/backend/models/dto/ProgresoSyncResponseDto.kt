package com.era.backend.models.dto

import kotlinx.serialization.Serializable

/**
 * Contrato de respuesta de `GET` y `POST /api/v1/progress/sync` (`modulo-g-analisis.md`
 * §4.1 y §4.2). Ambos endpoints comparten el mismo DTO:
 * - `GET`: snapshot autoritativo actual.
 * - `POST`: snapshot **mergeado y persistido** — el servidor "confirma la recepción y
 *   devuelve datos actualizados si existen" (CU-12 paso 3) en un único round-trip.
 */
@Serializable
data class ProgresoSyncResponseDto(
    val progreso: List<NivelProgresoDto>,
    val resumen: ResumenProgresoDto,
)
