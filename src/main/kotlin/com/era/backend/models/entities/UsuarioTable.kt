package com.era.backend.models.entities

import java.time.LocalDate
import java.time.LocalDateTime
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentDateTime
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.datetime

/**
 * Mapeo 1:1 de la tabla `usuario` (DICCIONARIO_DATOS.md).
 *
 * En el Módulo A solo se usa para consultas de unicidad/estado (§4 service):
 * - correo activo → `EmailAlreadyRegisteredException`.
 * - correo de cuenta en soft delete (`estado = 'eliminado'`) → `EmailLockedException`.
 * - `nombre_usuario` único → `ConflictException` (V1: el username de una cuenta eliminada permanece ocupado).
 */
object UsuarioTable : Table("usuario") {
    val idUsuario = integer("id_usuario").autoIncrement()
    val nombreMenor = varchar("nombre_menor", 120)
    val fechaNacimiento = date("fecha_nacimiento")
    val correo = varchar("correo", 255).uniqueIndex()
    val nombreUsuario = varchar("nombre_usuario", 60).uniqueIndex()
    val contrasenaHash = varchar("contrasena_hash", 255)
    val avatar = varchar("avatar", 255).nullable()
    val intentosLoginFallidos = ubyte("intentos_login_fallidos").default(0u)
    val bloqueadoHasta = datetime("bloqueado_hasta").nullable()
    val estado = varchar("estado", 10)
    val creadoEn = datetime("creado_en").defaultExpression(CurrentDateTime)
    val actualizadoEn = datetime("actualizado_en").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(idUsuario)
}

/**
 * Estados de soft delete (REQ-FUN-05): una cuenta `ELIMINADO` no puede iniciar sesión y
 * su correo queda bloqueado para nuevos registros hasta liberación administrativa.
 * [valor] es el literal persistido en `usuario.estado`.
 */
enum class EstadoUsuario(val valor: String) {
    ACTIVO("activo"),
    ELIMINADO("eliminado"),
}

/**
 * Fila materializada de [UsuarioTable].
 *
 * Contiene campos internos que jamás deben salir por la API ni loguearse en texto plano
 * ([contrasenaHash], [cedulaAcudiente], [correo], [fechaNacimiento]) — CLAUDE.md §6.
 */
data class UsuarioRow(
    val idUsuario: Long,
    val nombreMenor: String,
    val fechaNacimiento: LocalDate,
    val correo: String,
    val nombreUsuario: String,
    val contrasenaHash: String,
    val avatar: String?,
    val intentosLoginFallidos: Int,
    val bloqueadoHasta: LocalDateTime?,
    val estado: EstadoUsuario,
    val creadoEn: LocalDateTime,
    val actualizadoEn: LocalDateTime,
)
