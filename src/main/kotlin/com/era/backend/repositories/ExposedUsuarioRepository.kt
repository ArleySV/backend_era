package com.era.backend.repositories

import com.era.backend.models.entities.EstadoUsuario
import com.era.backend.models.entities.UsuarioRow
import com.era.backend.models.entities.UsuarioTable
import java.time.LocalDateTime
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

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
     * Lock de escritura sobre la fila del usuario por correo (Módulo B, login):
     * `SELECT ... FOR UPDATE` serializa intentos de login concurrentes y protege el
     * contador/ventana de bloqueo contra lost-update. Debe ejecutarse dentro de la
     * transacción de `LoginService`.
     */
    override fun findByEmailForUpdate(correo: String): UsuarioRow? =
        UsuarioTable.selectAll()
            .where { UsuarioTable.correo eq correo }
            .forUpdate(ForUpdateOption.ForUpdate)
            .firstOrNull()
            ?.let { aFila(it) }

    /**
     * Lock de escritura sobre la fila del usuario por nombre de usuario (B-6). La
     * coincidencia es case-insensitive por la collation `utf8mb4_unicode_ci` de la
     * columna (V1), igual que en `findByEmailForUpdate`.
     */
    override fun findByUsernameForUpdate(nombreUsuario: String): UsuarioRow? =
        UsuarioTable.selectAll()
            .where { UsuarioTable.nombreUsuario eq nombreUsuario }
            .forUpdate(ForUpdateOption.ForUpdate)
            .firstOrNull()
            ?.let { aFila(it) }

    /**
     * Lock de escritura sobre la fila del usuario por id (Módulo C, paso 3):
     * `SELECT ... FOR UPDATE` serializa reseteos concurrentes y cubre la lectura del hash
     * actual para el veto a repetir la contraseña anterior (REQ-FUN-07 CA5).
     */
    override fun findByIdForUpdate(idUsuario: Long): UsuarioRow? =
        UsuarioTable.selectAll()
            .where { UsuarioTable.idUsuario eq idUsuario.toInt() }
            .forUpdate(ForUpdateOption.ForUpdate)
            .firstOrNull()
            ?.let { aFila(it) }

    /**
     * Lectura del usuario por id sin lock (Módulo D, `GET /users/me`). Espejo de
     * [findByIdForUpdate] sin el `FOR UPDATE`: lectura pura para consultar el perfil.
     */
    override fun findById(idUsuario: Long): UsuarioRow? =
        UsuarioTable.selectAll()
            .where { UsuarioTable.idUsuario eq idUsuario.toInt() }
            .firstOrNull()
            ?.let { aFila(it) }

    /**
     * Soft delete por estado (Módulo E, REQ-FUN-05): `UPDATE usuario SET estado = ...`.
     * Espejo de `actualizarEstadoLogin`; un solo UPDATE de columna. Debe ejecutarse en la
     * transacción de `UsuarioService`; el throw de la excepción ocurre fuera de ella.
     */
    override fun actualizarEstado(idUsuario: Long, estado: EstadoUsuario) {
        UsuarioTable.update({ UsuarioTable.idUsuario eq idUsuario.toInt() }) {
            it[UsuarioTable.estado] = estado.valor
        }
    }

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

    /**
     * Persiste el estado de login del usuario (Módulo B, §5): contador de intentos
     * fallidos consecutivos y ventana de bloqueo. Un solo `UPDATE` cubre incremento por
     * fallo, apertura de ventana al 5.º fallo (B-3), limpieza lazy de ventana expirada
     * (B-2) y reset tras éxito. Debe ejecutarse en la transacción de `LoginService`; el
     * throw de la excepción ocurre fuera de ella para que esta escritura se commitee.
     */
    override fun actualizarEstadoLogin(
        idUsuario: Long,
        intentosLoginFallidos: Int,
        bloqueadoHasta: LocalDateTime?,
    ) {
        UsuarioTable.update({ UsuarioTable.idUsuario eq idUsuario.toInt() }) {
            it[UsuarioTable.intentosLoginFallidos] = intentosLoginFallidos.toUByte()
            it[UsuarioTable.bloqueadoHasta] = bloqueadoHasta
        }
    }

    /**
     * Persiste el nuevo hash bcrypt de la contraseña (Módulo C, paso 3). Espejo de
     * `actualizarEstadoLogin`; no toca `actualizado_en` (coherente con el Módulo B).
     */
    override fun actualizarContrasena(idUsuario: Long, contrasenaHash: String) {
        UsuarioTable.update({ UsuarioTable.idUsuario eq idUsuario.toInt() }) {
            it[UsuarioTable.contrasenaHash] = contrasenaHash
        }
    }

    /**
     * Módulo I: `UPDATE usuario SET avatar = ?`. Ejecutado en la transacción de
     * `AvatarService` (§2.4); la escritura del archivo y su compensación son responsabilidad
     * del service, no de este UPDATE.
     */
    override fun actualizarAvatar(idUsuario: Long, avatar: String?) {
        UsuarioTable.update({ UsuarioTable.idUsuario eq idUsuario.toInt() }) {
            it[UsuarioTable.avatar] = avatar
        }
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
