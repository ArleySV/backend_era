package com.era.backend.exceptions

import kotlinx.serialization.Serializable

/**
 * Formato estándar de respuesta de error (ARQUITECTURA_BASE.md §5.2). `details` solo
 * se puebla en errores de validación (decisión 2026-08-04), para que el cliente sepa
 * qué campo falló (REQ-FUN-01). Nunca contiene stack traces ni datos sensibles.
 */
@Serializable
data class ErrorDto(
    val timestamp: String,
    val status: Int,
    val error: String,
    val message: String,
    val path: String,
    val details: List<FieldError> = emptyList(),
)

/** Error a nivel de campo, usado dentro de `details` en errores de validación. */
@Serializable
data class FieldError(
    val field: String,
    val message: String,
)
