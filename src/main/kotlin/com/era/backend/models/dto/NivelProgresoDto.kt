package com.era.backend.models.dto

import kotlinx.serialization.Serializable

/**
 * Ítem del snapshot de progreso de `GET/POST /api/v1/progress/sync`
 * (`modulo-g-analisis.md` §4.1). Un nivel sin fila en `progreso_usuario` se omite (su
 * estado implícito es `bloqueado` con 0 reintentos).
 *
 * Mínimo privilegio (CLAUDE.md §6): nunca expone `id_usuario`, `id_nivel` ni
 * `id_progreso`; la clave es `orden` (estable). `completadoEn`/`ultimaInteraccion` son
 * marcas del servidor (reloj del servidor, §4.4).
 */
@Serializable
data class NivelProgresoDto(
    val orden: Int,
    val estadoNivel: String,
    val intentosTotales: Int,
    val completadoEn: String?,
    val ultimaInteraccion: String,
)
