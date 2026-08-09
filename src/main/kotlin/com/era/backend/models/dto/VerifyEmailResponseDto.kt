package com.era.backend.models.dto

import kotlinx.serialization.Serializable

/**
 * Contrato de respuesta de `POST /api/v1/auth/verify-email` (Módulo A.1).
 *
 * Principio de mínimo privilegio (CLAUDE.md §6): solo el mensaje de éxito; nunca se
 * devuelve el correo, datos del menor ni el OTP.
 */
@Serializable
data class VerifyEmailResponseDto(
    val message: String,
)
