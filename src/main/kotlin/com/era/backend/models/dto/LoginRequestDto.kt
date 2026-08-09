package com.era.backend.models.dto

import kotlinx.serialization.Serializable

/**
 * Contrato de entrada de `POST /api/v1/auth/login` (Módulo B, `modulo-b-analisis.md` §3.1).
 *
 * Único objeto que cruza la API para este endpoint; nunca se exponen entidades
 * (ARQUITECTURA_BASE.md §2.5). La validación de forma se hace en el controller (§4) y las
 * reglas de negocio (bloqueo, contador, bcrypt, estado, emisión JWT) en el service (§5).
 */
@Serializable
data class LoginRequestDto(
    /**
     * Identificador de login: nombre de usuario **o** correo electrónico (B-1, REQ-FUN-02).
     * No blanco; ≤ 255. Si contiene `@` se interpreta como correo (normalizado a minúsculas,
     * V5); si no, como username (case-insensitive, B-6). Dato sensible (CLAUDE.md §6): nunca
     * se loguea.
     */
    val usuarioOCorreo: String,

    /**
     * Contraseña del usuario. No blanco; ≤ 72 (tope técnico de bcrypt). Dato sensible
     * (CLAUDE.md §6): nunca se loguea ni se devuelve en la respuesta.
     */
    val contrasena: String,
)
