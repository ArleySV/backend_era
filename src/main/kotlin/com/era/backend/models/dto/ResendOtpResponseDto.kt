package com.era.backend.models.dto

import kotlinx.serialization.Serializable

/**
 * Contrato de respuesta de `POST /api/v1/auth/resend-otp` (Módulo A.1, P2).
 *
 * El mensaje es idéntico al del envío inicial del registro a propósito: el service
 * responde 200 con este mensaje aunque no exista un pendiente, para no confirmar si un
 * correo está en proceso de registro (anti-enumeración, decisión del propietario).
 * Mínimo privilegio: nunca se expone el código ni el correo.
 */
@Serializable
data class ResendOtpResponseDto(
    val message: String,
)
