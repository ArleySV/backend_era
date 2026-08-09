package com.era.backend.models.dto

import kotlinx.serialization.Serializable

/**
 * Contrato de respuesta de `POST /api/v1/auth/password-reset/request` (Módulo C, paso 1)
 * y de `POST /api/v1/auth/password-reset/confirm` (Módulo C, paso 3).
 *
 * Solo el mensaje genérico; nunca el correo, el OTP ni el token (anti-enumeración y
 * mínimo privilegio, CLAUDE.md §6).
 */
@Serializable
data class PasswordResetResponseDto(
    val message: String,
)
