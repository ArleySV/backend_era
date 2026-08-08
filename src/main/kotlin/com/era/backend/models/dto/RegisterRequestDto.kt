package com.era.backend.models.dto

import kotlinx.serialization.Serializable

/**
 * Contrato de entrada de `POST /api/v1/auth/register` (Módulo A, `modulo-a-analisis.md` §3.1).
 *
 * Único objeto que cruza la API para este endpoint; nunca se exponen entidades
 * (ARQUITECTURA_BASE.md §2.5). La validación de forma se hace en el controller y las
 * reglas de negocio en el service (§4).
 */
@Serializable
data class RegisterRequestDto(
    /** Nombres completos del menor; ≤ 120 (CU-01, HU-01). */
    val nombreMenor: String,

    /** Fecha de nacimiento ISO `yyyy-MM-dd`, parseable y no futura (V9, REQ-FUN-01 CA3). */
    val fechaNacimiento: String,

    /** Nombre completo del acudiente; ≤ 120 (HU-15 CA1). */
    val nombreAcudiente: String,

    /** Cédula del acudiente, 6–20 alfanuméricos (V8, HU-15 CA1). Dato sensible: nunca se loguea ni se expone (CLAUDE.md §6). */
    val cedulaAcudiente: String,

    /** Correo del acudiente; formato email, ≤ 255, normalizado a minúsculas (V5). Usado para login y notificaciones. */
    val correo: String,

    /** Nombre visible; 3–60 sin espacios (V4). */
    val nombreUsuario: String,

    /** Identificador de preset opcional (`preset:1|2|3`, V6); nunca una foto personalizada (esa solo existe post-verificación, Módulo I). */
    val avatar: String? = null,

    /** Contraseña; política REQ-FUN-01 CA2 (≥8, may/min/núm/símbolo, ≠ username, sin datos personales V3). Se valida en el service. */
    val contrasena: String,

    /** Debe coincidir con [contrasena]; regla de forma, se valida en el controller (§4). */
    val confirmarContrasena: String,
)
