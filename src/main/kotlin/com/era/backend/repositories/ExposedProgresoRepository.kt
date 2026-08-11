package com.era.backend.repositories

import com.era.backend.models.entities.EstadoNivel
import com.era.backend.models.entities.ProgresoUsuarioRow
import com.era.backend.models.entities.ProgresoUsuarioTable
import java.time.LocalDateTime
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Implementación real de [ProgresoRepository] sobre Exposed (ARQUITECTURA_BASE.md §2.4).
 * Debe ejecutarse dentro de la transacción de `ProgressSyncService` (atomicidad del POST,
 * §6 de `modulo-g-analisis.md`).
 */
class ExposedProgresoRepository : ProgresoRepository {

    override fun findByIdUsuario(idUsuario: Long): List<ProgresoUsuarioRow> =
        ProgresoUsuarioTable.selectAll()
            .where { ProgresoUsuarioTable.idUsuario eq idUsuario.toInt() }
            .map { aFila(it) }

    override fun findByIdUsuarioYNivel(idUsuario: Long, idNivel: Long): ProgresoUsuarioRow? =
        ProgresoUsuarioTable.selectAll()
            .where {
                (ProgresoUsuarioTable.idUsuario eq idUsuario.toInt()) and
                    (ProgresoUsuarioTable.idNivel eq idNivel.toInt())
            }
            .firstOrNull()
            ?.let { aFila(it) }

    override fun insertar(row: ProgresoUsuarioRow) {
        ProgresoUsuarioTable.insert {
            it[ProgresoUsuarioTable.idUsuario] = row.idUsuario.toInt()
            it[ProgresoUsuarioTable.idNivel] = row.idNivel.toInt()
            it[ProgresoUsuarioTable.estadoNivel] = row.estadoNivel.valor
            it[ProgresoUsuarioTable.intentosTotales] = row.intentosTotales
            it[ProgresoUsuarioTable.intentosFallidosConsecutivos] = row.intentosFallidosConsecutivos.toUByte()
            it[ProgresoUsuarioTable.completadoEn] = row.completadoEn
        }
    }

    override fun actualizar(
        idProgreso: Long,
        estadoNivel: EstadoNivel,
        intentosTotales: Int,
        intentosFallidosConsecutivos: Int,
        completadoEn: LocalDateTime?,
    ) {
        ProgresoUsuarioTable.update({ ProgresoUsuarioTable.idProgreso eq idProgreso.toInt() }) {
            it[ProgresoUsuarioTable.estadoNivel] = estadoNivel.valor
            it[ProgresoUsuarioTable.intentosTotales] = intentosTotales
            it[ProgresoUsuarioTable.intentosFallidosConsecutivos] = intentosFallidosConsecutivos.toUByte()
            it[ProgresoUsuarioTable.completadoEn] = completadoEn
        }
    }

    override fun contarCompletados(idUsuario: Long): Int =
        ProgresoUsuarioTable.selectAll()
            .where {
                (ProgresoUsuarioTable.idUsuario eq idUsuario.toInt()) and
                    (ProgresoUsuarioTable.estadoNivel eq "completado")
            }
            .count()
            .toInt()

    override fun sumarIntentosTotales(idUsuario: Long): Int =
        ProgresoUsuarioTable.selectAll()
            .where { ProgresoUsuarioTable.idUsuario eq idUsuario.toInt() }
            .map { it[ProgresoUsuarioTable.intentosTotales] }
            .sum()

    private fun aFila(fila: ResultRow): ProgresoUsuarioRow =
        ProgresoUsuarioRow(
            idProgreso = fila[ProgresoUsuarioTable.idProgreso].toLong(),
            idUsuario = fila[ProgresoUsuarioTable.idUsuario].toLong(),
            idNivel = fila[ProgresoUsuarioTable.idNivel].toLong(),
            estadoNivel = EstadoNivel.fromValor(fila[ProgresoUsuarioTable.estadoNivel])
                ?: throw IllegalStateException("Estado de nivel desconocido en BD."),
            intentosTotales = fila[ProgresoUsuarioTable.intentosTotales],
            intentosFallidosConsecutivos = fila[ProgresoUsuarioTable.intentosFallidosConsecutivos].toInt(),
            pausaActiva = fila[ProgresoUsuarioTable.pausaActiva],
            pausaHasta = fila[ProgresoUsuarioTable.pausaHasta],
            completadoEn = fila[ProgresoUsuarioTable.completadoEn],
            ultimaInteraccion = fila[ProgresoUsuarioTable.ultimaInteraccion],
        )
}
