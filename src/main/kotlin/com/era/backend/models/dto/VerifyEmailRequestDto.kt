package com.era.backend.models.dto

import kotlinx.serialization.Serializable

/**
 * Contrato de entrada de `POST /api/v1/auth/verify-email` (Módulo A.1, REQ-FUN-01
 * paso 3, CU-11).
 *
 * El correo se normaliza a minúsculas en el controller (V5) y el código es un OTP de
 * 6 dígitos (REQ-FUN-01 CA4). La verificación (coincidencia, vigencia, P1) es regla de
 * negocio del service; aquí solo se transporta el input.
 */
@Serializable
data class VerifyEmailRequestDto(
    /** Correo del acudiente; formato email, ≤ 255. Dato sensible: no se loguea (CLAUDE.md §6). */
    val correo: String,

    /** OTP numérico de 6 dígitos recibido por correo. */
    val codigo: String,
)
