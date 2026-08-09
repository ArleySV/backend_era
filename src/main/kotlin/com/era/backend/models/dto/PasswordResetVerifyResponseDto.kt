package com.era.backend.models.dto

import kotlinx.serialization.Serializable

/**
 * Contrato de respuesta de `POST /api/v1/auth/password-reset/verify` (Módulo C, paso 2).
 *
 * Devuelve el token puente JWT de reseteo (10 min, single-use) que el paso 3 consume.
 * El OTP verificada no se reexpone; el token viaja solo al cliente que ya demostró
 * poseer el correo (C-3).
 */
@Serializable
data class PasswordResetVerifyResponseDto(
    val resetToken: String,
)
