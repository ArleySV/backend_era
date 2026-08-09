package com.era.backend.models.dto

import kotlinx.serialization.Serializable

/**
 * Contrato de respuesta de `POST /api/v1/auth/login` (`modulo-b-analisis.md` §3.2).
 *
 * Principio de mínimo privilegio (CLAUDE.md §6): solo el token de sesión JWT (30 días,
 * ARQUITECTURA_BASE.md §5.4 #2). Los datos del perfil (username, avatar, correo) se
 * consultan por `GET /api/v1/users/me` (Módulo D), no se duplican aquí.
 */
@Serializable
data class LoginResponseDto(
    val token: String,
)
