package com.era.backend.models.dto

import kotlinx.serialization.Serializable

/**
 * Contrato de request de `DELETE /api/v1/users/me` (`modulo-d-analisis.md` §4.1, Módulo E,
 * REQ-FUN-05). La reverificación de contraseña es el requisito CA2: una sesión JWT válida
 * no es suficiente para eliminar la cuenta; se exige demostrar de nuevo la credencial.
 *
 * La forma (no blanco, ≤ 72) se valida en el controller; el service solo la usa para
 * bcrypt y nunca la loguea (CLAUDE.md §6).
 */
@Serializable
data class EliminarCuentaRequestDto(
    val contrasena: String,
)
