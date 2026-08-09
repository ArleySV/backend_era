package com.era.backend.models.entities

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentDateTime
import org.jetbrains.exposed.v1.javatime.datetime
import java.time.LocalDateTime

/**
 * Mapeo 1:1 de la tabla `configuracion` (DICCIONARIO_DATOS.md). Relación 1:1 con
 * `usuario` (`id_usuario` único, `uq_config_usuario`).
 *
 * En el Módulo A.1 solo se usa para insertar la fila por defecto durante la conversión
 * transaccional de `registro_pendiente` → `usuario` + `acudiente` + `configuracion`
 * (V1:113-127). El resto de las preferencias son del cliente (REQ-FUN-13) y se
 * persistirán en la sincronización (CU-12).
 */
object ConfiguracionTable : Table("configuracion") {
    val idConfig = integer("id_config").autoIncrement()
    val idUsuario = integer("id_usuario").uniqueIndex()
    val sonido = bool("sonido").default(true)
    val musica = bool("musica").default(true)
    val temaVisual = varchar("tema_visual", 10).default("claro")
    val tamanoTexto = varchar("tamano_texto", 20).default("mediano")
    val actualizadoEn = datetime("actualizado_en").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(idConfig)
}

/**
 * Fila materializada de [ConfiguracionTable].
 *
 * En A.1 el `insert` solo persiste `id_usuario`; las preferencias restantes las asigna
 * la base con sus defaults (V1). Los valores de la fila modelan el estado completo para
 * la futura sincronización (CU-12).
 */
data class ConfiguracionRow(
    val idConfig: Long,
    val idUsuario: Long,
    val sonido: Boolean,
    val musica: Boolean,
    val temaVisual: String,
    val tamanoTexto: String,
    val actualizadoEn: LocalDateTime,
)
