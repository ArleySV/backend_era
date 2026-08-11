package com.era.backend.models.entities

import java.time.LocalDateTime
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentDateTime
import org.jetbrains.exposed.v1.javatime.datetime

/**
 * Mapeo 1:1 de la tabla `progreso_usuario` (DICCIONARIO_DATOS.md §progreso_usuario):
 * estado de cada nivel para cada usuario, espejo remoto del progreso offline-first del
 * cliente (REQ-FUN-10/11/12, CU-08, CU-12). La unicidad `(id_usuario, id_nivel)` la
 * garantiza `uq_progreso_usuario_nivel` de V1.
 *
 * En el Módulo G (`modulo-g-analisis.md` §3.1) solo se sincronizan **agregados**:
 * `estado_nivel`, `intentos_totales` e `intentos_fallidos_consecutivos`. `pausa_activa`
 * y `pausa_hasta` quedan en el esquema pero **no viajan** en el wire (pausa solo de
 * cliente, §3.3); `ultima_interaccion` la administra la base (`ON UPDATE`).
 */
object ProgresoUsuarioTable : Table("progreso_usuario") {
    val idProgreso = integer("id_progreso").autoIncrement()
    val idUsuario = integer("id_usuario")
    val idNivel = integer("id_nivel")
    val estadoNivel = varchar("estado_nivel", 20)
    val intentosTotales = integer("intentos_totales")
    val intentosFallidosConsecutivos = ubyte("intentos_fallidos_consecutivos")
    val pausaActiva = bool("pausa_activa").default(false)
    val pausaHasta = datetime("pausa_hasta").nullable()
    val completadoEn = datetime("completado_en").nullable()
    val ultimaInteraccion = datetime("ultima_interaccion").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(idProgreso)
}

/**
 * Estados posibles de un nivel para un usuario (REQ-FUN-10 CA1/CA2).
 * [valor] es el literal persistido en `progreso_usuario.estado_nivel`; [precedencia]
 * ordena el merge hacia adelante (§3.4): `BLOQUEADO < DISPONIBLE < COMPLETADO`. El
 * estado **nunca regresa** — la fuente de verdad se mueve solo hacia adelante.
 */
enum class EstadoNivel(val valor: String, val precedencia: Int) {
    BLOQUEADO("bloqueado", 0),
    DISPONIBLE("disponible", 1),
    COMPLETADO("completado", 2),
    ;

    companion object {
        /** Resuelve el literal persistido/en el wire; `null` si no es un estado válido. */
        fun fromValor(valor: String): EstadoNivel? = entries.firstOrNull { it.valor == valor }

        /** Merge hacia adelante: prevalece el estado de mayor precedencia. */
        fun maxDe(a: EstadoNivel, b: EstadoNivel): EstadoNivel =
            if (a.precedencia >= b.precedencia) a else b
    }
}

/**
 * Fila materializada de [ProgresoUsuarioTable].
 *
 * Campos internos que jamás salen por la API: `idProgreso`, `idUsuario`, `idNivel`,
 * `pausaActiva` y `pausaHasta` (CLAUDE.md §6 — mínimo privilegio). El wire usa `orden`
 * (via `NivelRepository`), no `idNivel`.
 */
data class ProgresoUsuarioRow(
    val idProgreso: Long,
    val idUsuario: Long,
    val idNivel: Long,
    val estadoNivel: EstadoNivel,
    val intentosTotales: Int,
    val intentosFallidosConsecutivos: Int,
    val pausaActiva: Boolean,
    val pausaHasta: LocalDateTime?,
    val completadoEn: LocalDateTime?,
    val ultimaInteraccion: LocalDateTime,
)
