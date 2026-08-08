package com.era.backend.models.entities

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentDateTime
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.datetime
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Mapeo 1:1 de la tabla `registro_pendiente` (DICCIONARIO_DATOS.md).
 *
 * Representa los pasos 1 y 2 del registro mientras el correo no se verifica; ninguna
 * fila de `usuario` existe todavía (`modulo-a-analisis.md` §2). El OTP de registro vive
 * en [codigoHash] como hash bcrypt, nunca en texto plano (HU-15 CA3).
 *
 * Nota: la columna `ultimo_envio_en` (migración V2/P3, ARQUITECTURA_BASE.md §5.4 #5) se
 * agrega en la iteración del Módulo A.1; aquí solo las columnas de V1.
 */
object RegistroPendienteTable : Table("registro_pendiente") {
    val idRegistro = integer("id_registro").autoIncrement()
    val correo = varchar("correo", 255).uniqueIndex()
    val nombreUsuario = varchar("nombre_usuario", 60).uniqueIndex()
    val contrasenaHash = varchar("contrasena_hash", 255)
    val nombreMenor = varchar("nombre_menor", 120)
    val fechaNacimiento = date("fecha_nacimiento")
    val nombreAcudiente = varchar("nombre_acudiente", 120)
    val cedulaAcudiente = varchar("cedula_acudiente", 20)
    val avatar = varchar("avatar", 255).nullable()
    val codigoHash = varchar("codigo_hash", 255)
    val intentosFallidos = ubyte("intentos_fallidos").default(0u)
    val expiraEn = datetime("expira_en")
    val creadoEn = datetime("creado_en").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(idRegistro)
}

/**
 * Fila materializada de [RegistroPendienteTable].
 *
 * Contiene campos internos que jamás deben salir por la API ni loguearse en texto plano
 * ([contrasenaHash], [codigoHash], [cedulaAcudiente], [correo]) — CLAUDE.md §6.
 */
data class RegistroPendienteRow(
    val idRegistro: Long,
    val correo: String,
    val nombreUsuario: String,
    val contrasenaHash: String,
    val nombreMenor: String,
    val fechaNacimiento: LocalDate,
    val nombreAcudiente: String,
    val cedulaAcudiente: String,
    val avatar: String?,
    val codigoHash: String,
    val intentosFallidos: Int,
    val expiraEn: LocalDateTime,
    val creadoEn: LocalDateTime,
)
