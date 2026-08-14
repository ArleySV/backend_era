package com.era.backend.models.dto

import kotlinx.serialization.Serializable

/**
 * Contrato de entrada de `PATCH /api/v1/users/me` (Módulo D, REQ-FUN-06 CA5, CU-06, HU-06).
 *
 * Alcance restringido (CA5): **solo** `nombreUsuario` es aceptado como modificable; cualquier
 * otro campo enviado en la solicitud se ignora y una clave desconocida → 400 `INVALID_REQUEST`
 * (espejo del Módulo H). La validación de forma V4 (3–60 sin espacios) vive en el controller;
 * la unicidad y el estado de la cuenta son reglas del service.
 */
@Serializable
data class ActualizarUsuarioRequestDto(
    /** Nombre visible; 3–60 caracteres sin espacios (V4, misma regla del registro). */
    val nombreUsuario: String,
)
