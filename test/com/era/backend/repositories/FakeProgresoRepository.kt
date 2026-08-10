package com.era.backend.repositories

import com.era.backend.models.entities.EstadoNivel
import com.era.backend.models.entities.ProgresoUsuarioRow
import java.time.LocalDateTime

/**
 * Fake en memoria de [ProgresoRepository] para los unit/route tests del Módulo G. No toca
 * MySQL: reproduce el contrato que usa `ProgressSyncService` (lectura, upsert por
 * `(id_usuario, id_nivel)` y agregados del resumen) sin SQL ni transacciones.
 */
class FakeProgresoRepository : ProgresoRepository {

    /** Filas por `(idUsuario, idNivel)` — espejo de la `UNIQUE (id_usuario, id_nivel)`. */
    private val filas = mutableMapOf<Pair<Long, Long>, ProgresoUsuarioRow>()
    private var siguienteId = 1L

    /** Siembra una fila de progreso existente (merge contra servidor). */
    fun seed(row: ProgresoUsuarioRow) {
        filas[(row.idUsuario to row.idNivel)] = row
    }

    /** Filas de un usuario, para assertions. */
    fun todas(idUsuario: Long): List<ProgresoUsuarioRow> =
        filas.values.filter { it.idUsuario == idUsuario }

    fun size(): Int = filas.size

    override fun findByIdUsuario(idUsuario: Long): List<ProgresoUsuarioRow> = todas(idUsuario)

    override fun findByIdUsuarioYNivel(idUsuario: Long, idNivel: Long): ProgresoUsuarioRow? =
        filas[(idUsuario to idNivel)]

    override fun insertar(row: ProgresoUsuarioRow) {
        val id = if (row.idProgreso == 0L) siguienteId++ else row.idProgreso
        filas[(row.idUsuario to row.idNivel)] = row.copy(idProgreso = id)
    }

    override fun actualizar(
        idProgreso: Long,
        estadoNivel: EstadoNivel,
        intentosTotales: Int,
        intentosFallidosConsecutivos: Int,
        completadoEn: LocalDateTime?,
    ) {
        val existente = filas.values.firstOrNull { it.idProgreso == idProgreso } ?: return
        filas[(existente.idUsuario to existente.idNivel)] =
            existente.copy(
                estadoNivel = estadoNivel,
                intentosTotales = intentosTotales,
                intentosFallidosConsecutivos = intentosFallidosConsecutivos,
                completadoEn = completadoEn,
            )
    }

    override fun contarCompletados(idUsuario: Long): Int =
        todas(idUsuario).count { it.estadoNivel == EstadoNivel.COMPLETADO }

    override fun sumarIntentosTotales(idUsuario: Long): Int =
        todas(idUsuario).sumOf { it.intentosTotales }
}
