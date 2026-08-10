package com.era.backend.models.dto

import kotlinx.serialization.Serializable

/**
 * Ítem del body de `POST /api/v1/progress/sync` (`modulo-g-analisis.md` §4.2). Representa
 * el estado local de un nivel que el cliente acumuló offline.
 *
 * - `orden`: identificador estable del wire (1..20, `nivel.orden`), no la PK interna.
 * - `estadoNivel`: literal del enum `bloqueado|disponible|completado` (REQ-FUN-10).
 * - `intentosTotales` / `intentosFallidosConsecutivos`: contadores agregados; se mergean
 *   hacia adelante con `max` (§3.1). `pausa_activa`/`pausa_hasta` nunca viajan (§3.3).
 */
@Serializable
data class ProgresoSyncItemDto(
    val orden: Int,
    val estadoNivel: String,
    val intentosTotales: Int = 0,
    val intentosFallidosConsecutivos: Int = 0,
)
