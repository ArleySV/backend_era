package com.era.backend.models.entities

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentDateTime
import org.jetbrains.exposed.v1.javatime.datetime
import java.time.LocalDateTime

/**
 * Mapeo 1:1 de la tabla `tokens_reseteo` (DICCIONARIO_DATOS.md).
 *
 * Registro del token puente JWT de corta vida (10 min) emitido por `password-reset/verify`
 * y consumido por `password-reset/confirm` (Módulo C, ARQUITECTURA_BASE.md §2.3/C-3). El
 * [jti] (identificador único del JWT) se persiste para garantizar el **single-use**: una
 * fila `consumido = 1` invalida cualquier reintento con el mismo token.
 *
 * El token en sí (la cadena JWT) jamás se persiste ni se loguea; solo su [jti]
 * (CLAUDE.md §6). El `UNIQUE(jti)` de V1 (`uq_tokens_reseteo_jti`) es la barrera final de
 * unicidad.
 */
object TokensReseteoTable : Table("tokens_reseteo") {
    val idToken = integer("id_token").autoIncrement()
    val jti = varchar("jti", 64).uniqueIndex()
    val idUsuario = integer("id_usuario")
    val expiraEn = datetime("expira_en")
    val consumido = bool("consumido").default(false)
    val creadoEn = datetime("creado_en").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(idToken)
}

/**
 * Fila materializada de [TokensReseteoTable].
 *
 * No contiene el JWT, solo su [jti]; nunca debe loguearse el jti ni el token derivado
 * (CLAUDE.md §6).
 */
data class TokensReseteoRow(
    val idToken: Long,
    val jti: String,
    val idUsuario: Long,
    val expiraEn: LocalDateTime,
    val consumido: Boolean = false,
    val creadoEn: LocalDateTime,
)
