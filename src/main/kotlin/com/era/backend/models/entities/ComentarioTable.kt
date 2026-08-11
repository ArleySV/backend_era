package com.era.backend.models.entities

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentDateTime
import org.jetbrains.exposed.v1.javatime.datetime

/**
 * Mapeo 1:1 de la tabla `comentario` (DICCIONARIO_DATOS.md §comentario): comentarios y
 * sugerencias enviados por el usuario (REQ-FUN-14, CU-10, HU-14).
 *
 * `id_usuario` usa el mismo tipo que `UsuarioTable.idUsuario` (INT UNSIGNED en V1) para que
 * la FK `fk_comentario_usuario` (`ON DELETE RESTRICT`) enlace correctamente. El autor se
 * resuelve SIEMPRE del token de sesión (`SesionPrincipal`), nunca del body
 * (`modulo-h-analisis.md` §4.1).
 *
 * `contenido` es TEXT (máx. 65.535 bytes); el límite real de 2000 caracteres lo impone el
 * controller (validación de forma, §3.1). `enviado_en` lo asigna la base
 * (`DEFAULT CURRENT_TIMESTAMP`); el servidor no lo recibe ni lo expone (mínimo privilegio,
 * CLAUDE.md §6).
 */
object ComentarioTable : Table("comentario") {
    val idComentario = integer("id_comentario").autoIncrement()
    val idUsuario = integer("id_usuario")
    val contenido = text("contenido")
    val enviadoEn = datetime("enviado_en").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(idComentario)
}
