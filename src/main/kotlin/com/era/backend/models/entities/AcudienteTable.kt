package com.era.backend.models.entities

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentDateTime
import org.jetbrains.exposed.v1.javatime.datetime
import java.time.LocalDateTime

/**
 * Mapeo 1:1 de la tabla `acudiente` (DICCIONARIO_DATOS.md). Relación 1:1 con `usuario`
 * (`id_usuario` único, `uq_acudiente_usuario`).
 *
 * En el Módulo A.1 solo se usa para insertar la fila del acudiente durante la
 * conversión transaccional de `registro_pendiente` → `usuario` + `acudiente` +
 * `configuracion` (V1:55-57). La FK hacia `usuario` está en el DDL, no aquí (misma
 * convención que las demás tablas Exposed del proyecto).
 */
object AcudienteTable : Table("acudiente") {
    val idAcudiente = integer("id_acudiente").autoIncrement()
    val idUsuario = integer("id_usuario").uniqueIndex()
    val nombreCompleto = varchar("nombre_completo", 120)
    val numeroCedula = varchar("numero_cedula", 20)
    val creadoEn = datetime("creado_en").defaultExpression(CurrentDateTime)
    val actualizadoEn = datetime("actualizado_en").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(idAcudiente)
}

/**
 * Fila materializada de [AcudienteTable].
 *
 * [numeroCedula] es documento de identidad (HU-15 CA1): jamás sale por la API ni se
 * loguea en texto plano (CLAUDE.md §6).
 */
data class AcudienteRow(
    val idAcudiente: Long,
    val idUsuario: Long,
    val nombreCompleto: String,
    val numeroCedula: String,
    val creadoEn: LocalDateTime,
    val actualizadoEn: LocalDateTime,
)
