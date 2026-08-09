package com.era.backend.models.dto

import kotlinx.serialization.Serializable

/**
 * Contrato de entrada de `POST /api/v1/auth/resend-otp` (Módulo A.1, P2).
 *
 * El correo se normaliza a minúsculas en el controller (V5). El service aplica el
 * throttle de reenvío de 60 s (P2) y responde con un mensaje genérico de éxito incluso
 * si no existe un pendiente (anti-enumeración de correos, decisión del propietario).
 */
@Serializable
data class ResendOtpRequestDto(
    /** Correo del acudiente; formato email, ≤ 255. Dato sensible: no se loguea (CLAUDE.md §6). */
    val correo: String,
)
