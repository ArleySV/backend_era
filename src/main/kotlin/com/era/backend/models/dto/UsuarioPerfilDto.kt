package com.era.backend.models.dto

import kotlinx.serialization.Serializable

/**
 * Contrato de respuesta de `GET /api/v1/users/me` (`modulo-d-analisis.md` §3.2, Módulo D,
 * REQ-FUN-06). Mínimo privilegio (D-4): **solo** estos 5 campos; nunca la cédula del
 * acudiente, el nombre del acudiente, el hash de contraseña ni contadores (CLAUDE.md §6).
 */
@Serializable
data class UsuarioPerfilDto(
    val nombreMenor: String,
    val fechaNacimiento: String,
    val correo: String,
    val nombreUsuario: String,
    val avatar: String?,
)
