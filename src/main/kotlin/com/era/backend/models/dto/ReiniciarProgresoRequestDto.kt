package com.era.backend.models.dto

import kotlinx.serialization.Serializable

/**
 * Contrato de request de `POST /api/v1/progress/reset` (reinicio de progreso).
 *
 * La reverificación de contraseña es obligatoria: una sesión JWT válida no es suficiente
 * para una operación destructiva como borrar todo el progreso. Espejo de
 * [EliminarCuentaRequestDto] (Módulo E, REQ-FUN-05 CA2).
 *
 * La forma (no blanco, ≤ 72) se valida en el controller; el service solo la usa para
 * bcrypt y nunca la loguea (CLAUDE.md §6).
 */
@Serializable
data class ReiniciarProgresoRequestDto(
    val contrasena: String = "",
)
