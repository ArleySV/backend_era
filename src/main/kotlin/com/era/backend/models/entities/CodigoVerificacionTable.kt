package com.era.backend.models.entities

import java.time.LocalDateTime
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentDateTime
import org.jetbrains.exposed.v1.javatime.datetime

/**
 * Mapeo 1:1 de la tabla `codigo_verificacion` (DICCIONARIO_DATOS.md).
 *
 * Código OTP exclusivo del flujo de **recuperación de contraseña** (Módulo C); el usuario
 * ya existe en este punto (a diferencia del registro, cuyo OTP vive en
 * `registro_pendiente.codigo_hash`). El código se guarda en [codigoHash] como hash bcrypt,
 * nunca en texto plano (HU-15 CA3, CLAUDE.md §6).
 *
 * [ultimoEnvioEn] (migración V3, decisión C-2/C-5) soporta el throttle de reenvío (P2,
 * 60 s) del OTP de recuperación: se escribe en el alta y en cada reenvío. El índice
 * `idx_codigo_usuario_usado (id_usuario, usado)` de V1 respalda la búsqueda del último
 * código de un usuario.
 */
object CodigoVerificacionTable : Table("codigo_verificacion") {
    val idCodigo = integer("id_codigo").autoIncrement()
    val idUsuario = integer("id_usuario")
    val codigoHash = varchar("codigo_hash", 255)
    val intentosFallidos = ubyte("intentos_fallidos").default(0u)
    val expiraEn = datetime("expira_en")
    val ultimoEnvioEn = datetime("ultimo_envio_en").nullable()
    val usado = bool("usado").default(false)
    val creadoEn = datetime("creado_en").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(idCodigo)
}

/**
 * Fila materializada de [CodigoVerificacionTable].
 *
 * Contiene un campo interno que jamás debe salir por la API ni loguearse en texto plano
 * ([codigoHash]) — CLAUDE.md §6.
 */
data class CodigoVerificacionRow(
    val idCodigo: Long,
    val idUsuario: Long,
    val codigoHash: String,
    val intentosFallidos: Int,
    val expiraEn: LocalDateTime,
    val ultimoEnvioEn: LocalDateTime? = null,
    val usado: Boolean = false,
    val creadoEn: LocalDateTime,
)
