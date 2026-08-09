package com.era.backend.repositories

import com.era.backend.models.entities.EstadoUsuario
import com.era.backend.models.entities.UsuarioRow
import com.era.backend.models.entities.UsuarioTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Implementación real de [UsuarioRepository] sobre Exposed (ARQUITECTURA_BASE.md §2.4).
 * Debe ejecutarse dentro de una transacción (la provee `RegistrationService` vía
 * `TransactionRunner`).
 */
class ExposedUsuarioRepository : UsuarioRepository {

    /**
     * Busca un usuario por correo (normalizado a minúsculas, V5). El service usa el
     * estado para decidir: `ACTIVO` → `EmailAlreadyRegisteredException`; `ELIMINADO` →
     * `EmailLockedException` (REQ-FUN-01 CA1, REQ-FUN-05; §4 service).
     *
     * Seguridad (CLAUDE.md §6): nunca loguear el correo, la cédula ni `contrasena_hash`
     * de la fila devuelta.
     */
    override fun findByEmail(correo: String): UsuarioRow? =
        UsuarioTable.selectAll()
            .where { UsuarioTable.correo eq correo }
            .firstOrNull()
            ?.let { aFila(it) }

    /**
     * Verifica si [nombreUsuario] ya está en uso. Aplica a cuentas activas y eliminadas:
     * el username de una cuenta soft-deleted permanece ocupado (V1, REQ-FUN-01).
     * Sin loguear datos de la fila (CLAUDE.md §6).
     */
    override fun existsByUsername(nombreUsuario: String): Boolean =
        UsuarioTable.selectAll()
            .where { UsuarioTable.nombreUsuario eq nombreUsuario }
            .limit(1)
            .firstOrNull() != null

    /**
     * Persiste un usuario (A.1, conversión transaccional): solo se escribe lo que no
     * tiene default (estado explícito `ACTIVO`, `intentos_login_fallidos` 0 y
     * `bloqueado_hasta` NULL); `creado_en`/`actualizado_en` los asigna la base.
     */
    override fun insert(row: UsuarioRow): Long {
        val id =
            (UsuarioTable.insert {
                it[UsuarioTable.nombreMenor] = row.nombreMenor
                it[UsuarioTable.fechaNacimiento] = row.fechaNacimiento
                it[UsuarioTable.correo] = row.correo
                it[UsuarioTable.nombreUsuario] = row.nombreUsuario
                it[UsuarioTable.contrasenaHash] = row.contrasenaHash
                it[UsuarioTable.avatar] = row.avatar
                it[UsuarioTable.intentosLoginFallidos] = row.intentosLoginFallidos.toUByte()
                it[UsuarioTable.bloqueadoHasta] = row.bloqueadoHasta
                it[UsuarioTable.estado] = row.estado.valor
            }) get UsuarioTable.idUsuario
        return id.toLong()
    }

    private fun aFila(fila: ResultRow): UsuarioRow =
        UsuarioRow(
            idUsuario = fila[UsuarioTable.idUsuario].toLong(),
            nombreMenor = fila[UsuarioTable.nombreMenor],
            fechaNacimiento = fila[UsuarioTable.fechaNacimiento],
            correo = fila[UsuarioTable.correo],
            nombreUsuario = fila[UsuarioTable.nombreUsuario],
            contrasenaHash = fila[UsuarioTable.contrasenaHash],
            avatar = fila[UsuarioTable.avatar],
            intentosLoginFallidos = fila[UsuarioTable.intentosLoginFallidos].toInt(),
            bloqueadoHasta = fila[UsuarioTable.bloqueadoHasta],
            estado = EstadoUsuario.entries.firstOrNull { it.valor == fila[UsuarioTable.estado] }
                ?: throw IllegalStateException("Estado de usuario desconocido en BD."),
            creadoEn = fila[UsuarioTable.creadoEn],
            actualizadoEn = fila[UsuarioTable.actualizadoEn],
        )
}
