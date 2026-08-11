package com.era.backend.models.dto

import kotlinx.serialization.Serializable

/**
 * Body de `POST /api/v1/feedback/comments` (`modulo-h-analisis.md` §4.1).
 *
 * Contiene SOLO `contenido`: el `id_usuario` proviene del `SesionPrincipal` (claim `sub`
 * del token), nunca del cuerpo de la solicitud (mínimo privilegio, CLAUDE.md §6). Con
 * kotlinx.serialization sin `ignoreUnknownKeys`, una clave desconocida en el body (p. ej.
 * `idUsuario`) se rechaza con 400.
 */
@Serializable
data class ComentarioRequestDto(
    val contenido: String,
)
