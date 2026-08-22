package com.era.backend.repositories

import com.era.backend.models.entities.EstadoNivel
import com.era.backend.models.entities.IntentionTable
import com.era.backend.models.entities.ProgresoUsuarioRow
import com.era.backend.models.entities.ProgresoUsuarioTable
import java.time.LocalDateTime
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.deleteWhere
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
            .forUpdate(ForUpdateOption.ForUpdate)
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

    override fun deleteByUsuario(idUsuario: Long) {
        // Paso 1: eliminar intentos del usuario (FK RESTRICT exige este orden).
        val idsProgreso =
            ProgresoUsuarioTable.selectAll()
                .where { ProgresoUsuarioTable.idUsuario eq idUsuario.toInt() }
                .map { it[ProgresoUsuarioTable.idProgreso] }
        if (idsProgreso.isNotEmpty()) {
            IntentionTable.deleteWhere {
                IntentionTable.idProgreso inList idsProgreso
            }
        }
        // Paso 2: eliminar todo el progreso del usuario.
        ProgresoUsuarioTable.deleteWhere {
            ProgresoUsuarioTable.idUsuario eq idUsuario.toInt()
        }
    }

    override fun ensureNivel1Disponible(idUsuario: Long, idNivel1: Long) {
        val existente =
            ProgresoUsuarioTable.selectAll()
                .where {
                    (ProgresoUsuarioTable.idUsuario eq idUsuario.toInt()) and
                        (ProgresoUsuarioTable.idNivel eq idNivel1.toInt())
                }
                .firstOrNull()
        if (existente != null) {
            ProgresoUsuarioTable.update({
                (ProgresoUsuarioTable.idUsuario eq idUsuario.toInt()) and
                    (ProgresoUsuarioTable.idNivel eq idNivel1.toInt())
            }) {
                it[ProgresoUsuarioTable.estadoNivel] = EstadoNivel.DISPONIBLE.valor
                it[ProgresoUsuarioTable.intentosTotales] = 0
                it[ProgresoUsuarioTable.intentosFallidosConsecutivos] = 0u
                it[ProgresoUsuarioTable.pausaActiva] = false
                it[ProgresoUsuarioTable.pausaHasta] = null
                it[ProgresoUsuarioTable.completadoEn] = null
            }
        } else {
            ProgresoUsuarioTable.insert {
                it[ProgresoUsuarioTable.idUsuario] = idUsuario.toInt()
                it[ProgresoUsuarioTable.idNivel] = idNivel1.toInt()
                it[ProgresoUsuarioTable.estadoNivel] = EstadoNivel.DISPONIBLE.valor
                it[ProgresoUsuarioTable.intentosTotales] = 0
                it[ProgresoUsuarioTable.intentosFallidosConsecutivos] = 0u
            }
        }
    }

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
