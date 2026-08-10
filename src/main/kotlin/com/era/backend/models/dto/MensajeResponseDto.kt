package com.era.backend.models.dto

import kotlinx.serialization.Serializable

/**
 * Respuesta genérica de solo mensaje (D-5), reutilizable por cualquier módulo. No expone
 * datos personales (CLAUDE.md §6). Usado por `DELETE /api/v1/users/me`
 * (`modulo-d-analisis.md` §4.2).
 */
@Serializable
data class MensajeResponseDto(
    val message: String,
)
