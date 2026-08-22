package com.era.backend.repositories

import com.era.backend.models.entities.EstadoNivel
import com.era.backend.models.entities.ProgresoUsuarioRow
import java.time.LocalDateTime

/**
 * Acceso a datos de `progreso_usuario` (ARQUITECTURA_BASE.md §2.4), el espejo remoto del
 * progreso offline-first del cliente (REQ-FUN-10/11/12, CU-12).
 *
 * Interfaz para que `ProgressSyncService` (y los tests) dependan de la abstracción, no de
 * Exposed: producción usa [ExposedProgresoRepository], los tests un fake en memoria.
 *
 * Solo se persisten **agregados** (`estado_nivel`, `intentos_totales`,
 * `intentos_fallidos_consecutivos`); `pausa_*` no se sincroniza (§3.3). Debe ejecutarse
 * dentro de la transacción de `ProgressSyncService` (la que hace atómico el POST, §6).
 */
interface ProgresoRepository {

    /**
     * Todas las filas de progreso del usuario (para construir el snapshot de respuesta).
     */
    fun findByIdUsuario(idUsuario: Long): List<ProgresoUsuarioRow>

    /**
     * Fila de progreso de un usuario para un nivel concreto, si existe. Base del merge
     * hacia adelante (§3.4).
     */
    fun findByIdUsuarioYNivel(idUsuario: Long, idNivel: Long): ProgresoUsuarioRow?

    /**
     * Alta de una fila de progreso (nivel sin estado previo). Se omiten `pausa_activa`
     * (default 0) y `ultima_interaccion` (default/`ON UPDATE` de la base).
     */
    fun insertar(row: ProgresoUsuarioRow)

    /**
     * Actualización de una fila existente tras el merge hacia adelante. El estado nunca
     * regresa; `completado_en` se fija una sola vez con reloj del servidor (§4.4).
     */
    fun actualizar(
        idProgreso: Long,
        estadoNivel: EstadoNivel,
        intentosTotales: Int,
        intentosFallidosConsecutivos: Int,
        completadoEn: LocalDateTime?,
    )

    /**
     * `COUNT(*)` de niveles del usuario en estado `completado` (§7). Se calcula en el
     * servidor, en una consulta.
     */
    fun contarCompletados(idUsuario: Long): Int

    /**
     * `SUM(intentos_totales)` de todos los niveles del usuario (§7): `totalReintentos`
     * jamás se calcula en el cliente, siempre es agregado del servidor.
     */
    fun sumarIntentosTotales(idUsuario: Long): Int

    /**
     * Elimina todas las filas de `intento` y `progreso_usuario` del usuario indicado.
     * Se usa en el reinicio de progreso (`POST /api/v1/progress/reset`): primero se
     * borran los intentos (FK `RESTRICT` de `intento` hacia `progreso_usuario`, V1) y
     * luego todo el progreso del usuario.
     *
     * Debe ejecutarse dentro de una transacción que garantice atomicidad con la
     * inserción posterior del nivel 1 como `disponible`.
     */
    fun deleteByUsuario(idUsuario: Long)

    /**
     * Asegura que el nivel 1 (orden=1) esté en estado `disponible` para el usuario.
     * Si el usuario ya tiene una fila para ese nivel, la actualiza a `disponible` con
     * contadores en 0. Si no existe, inserta una fila nueva.
     *
     * Se usa en el reinicio de progreso: tras borrar todo el progreso, el nivel 1
     * debe quedar como punto de partida.
     *
     * Debe ejecutarse dentro de la misma transacción que [deleteByUsuario].
     */
    fun ensureNivel1Disponible(idUsuario: Long, idNivel1: Long)
}
