package com.era.backend.models.dto

import kotlinx.serialization.Serializable

/**
 * Contrato de entrada de `POST /api/v1/auth/password-reset/confirm` (Módulo C, paso 3).
 *
 * Consume el token puente del paso 2, exige que la nueva contraseña cumpla la política
 * compartida (`utils/PasswordPolicy`, C-6) y que no repita la actual (REQ-FUN-07 CA5).
 * La confirmación es espejo exacto del alta de cuenta para no forzar un doble campo.
 */
@Serializable
data class PasswordResetConfirmRequestDto(
    val resetToken: String,
    val nuevaContrasena: String,
    val confirmarContrasena: String,
)
