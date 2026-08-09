package com.era.backend.models.dto

import kotlinx.serialization.Serializable

/**
 * Contrato de entrada de `POST /api/v1/auth/password-reset/verify` (Módulo C, paso 2).
 *
 * Verifica el OTP recibido en el paso 1 y devuelve un token puente de corta vida
 * (10 min, single-use) para el paso 3. El OTP nunca se devuelve ni se loguea.
 */
@Serializable
data class PasswordResetVerifyRequestDto(
    val correo: String,
    val codigo: String,
)
