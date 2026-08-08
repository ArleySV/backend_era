package com.era.backend.models.dto

import kotlinx.serialization.Serializable

/**
 * Contrato de respuesta de `POST /api/v1/auth/register` (`modulo-a-analisis.md` §3.2).
 *
 * Principio de mínimo privilegio (CLAUDE.md §6): solo el mensaje de éxito; nunca se
 * devuelve correo, datos del menor ni el código OTP.
 */
@Serializable
data class RegisterResponseDto(
    val message: String,
)
