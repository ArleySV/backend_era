package com.era.backend.models.entities

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentDateTime
import org.jetbrains.exposed.v1.javatime.datetime

/**
 * Mapeo mínimo de la tabla `intento` (DICCIONARIO_DATOS.md §intento).
 *
 * Este mapeo se crea exclusivamente para soportar el DELETE de intentos en el endpoint
 * de reinicio de progreso (`POST /api/v1/progress/reset`). La tabla `intento` tiene FK
 * hacia `progreso_usuario(id_progreso)` con `ON DELETE RESTRICT` (V1), así que las filas
 * de intento deben eliminarse antes de borrar el progreso del usuario.
 *
 * No se expone por API ni se sincroniza (mínimo privilegio, CLAUDE.md §6). Solo se usa
 * el `DELETE` vía Exposed para mantener la consistencia referencial.
 */
object IntentionTable : Table("intento") {
    val idIntento = integer("id_intento").autoIncrement()
    val idProgreso = integer("id_progreso")
    val idOpcionElegida = integer("id_opcion_elegida").nullable()
    val fueCorrecto = bool("fue_correcto").default(false)
    val segundosRestantes = ubyte("segundos_restantes").default(0u)
    val registradoEn = datetime("registrado_en").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(idIntento)
}
