package com.era.backend.models.dto

import kotlinx.serialization.Serializable

/**
 * Contrato de entrada de `POST /api/v1/auth/password-reset/request` (Módulo C, paso 1).
 *
 * La respuesta es deliberadamente idéntica exista o no la cuenta (anti-enumeración,
 * REQ-FUN-07 CA4 / decisión C-1): el cliente no puede distinguir si el correo está
 * registrado. Solo `correo`; mínimo privilegio.
 */
@Serializable
data class PasswordResetRequestDto(
    val correo: String,
)
