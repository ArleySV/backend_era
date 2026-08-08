package com.era.backend.repositories

import com.era.backend.models.entities.RegistroPendienteRow
import com.era.backend.models.entities.RegistroPendienteTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.LocalDateTime

/**
 * Implementación real de [RegistroPendienteRepository] sobre Exposed
 * (ARQUITECTURA_BASE.md §2.4). Aísla el SQL y el mapeo a [RegistroPendienteRow]; el
 * service nunca ve queries ni columnas. Debe ejecutarse dentro de una transacción (la
 * provee `RegistrationService` vía `TransactionRunner`) para que las operaciones del
 * registro sean atómicas.
 */
class ExposedRegistroPendienteRepository : RegistroPendienteRepository {

    /**
     * Persiste un registro pendiente (pasos 1 + 2) con hash bcrypt de contraseña y del
     * OTP, `expira_en = now + 10 min` e `intentos_fallidos = 0` (REQ-FUN-01, CU-01; §2).
     *
     * Seguridad (CLAUDE.md §6): nunca loguear `contrasena_hash` ni `codigo_hash` en texto
     * plano (ni el correo ni la cédula de la fila).
     *
     * @param row fila a insertar; `id` y `creadoEn` los asigna la base de datos.
     * @return id del registro creado.
     */
    override fun insert(row: RegistroPendienteRow): Long {
        val id = (RegistroPendienteTable.insert {
            it[RegistroPendienteTable.correo] = row.correo
            it[RegistroPendienteTable.nombreUsuario] = row.nombreUsuario
            it[RegistroPendienteTable.contrasenaHash] = row.contrasenaHash
            it[RegistroPendienteTable.nombreMenor] = row.nombreMenor
            it[RegistroPendienteTable.fechaNacimiento] = row.fechaNacimiento
            it[RegistroPendienteTable.nombreAcudiente] = row.nombreAcudiente
            it[RegistroPendienteTable.cedulaAcudiente] = row.cedulaAcudiente
            it[RegistroPendienteTable.avatar] = row.avatar
            it[RegistroPendienteTable.codigoHash] = row.codigoHash
            it[RegistroPendienteTable.intentosFallidos] = row.intentosFallidos.toUByte()
            it[RegistroPendienteTable.expiraEn] = row.expiraEn
        }) get RegistroPendienteTable.idRegistro
        return id.toLong()
    }

    /**
     * Busca un registro pendiente por correo (normalizado a minúsculas, V5) para el check
     * de unicidad de correo contra `registro_pendiente` no expirado (REQ-FUN-01 CA1, §4).
     *
     * Seguridad (CLAUDE.md §6): no loguear el correo ni los hashes de la fila.
     */
    override fun findByEmail(correo: String): RegistroPendienteRow? =
        RegistroPendienteTable.selectAll()
            .where { RegistroPendienteTable.correo eq correo }
            .firstOrNull()
            ?.let { aFila(it) }

    /**
     * Busca un registro pendiente por nombre de usuario para el check de unicidad de
     * `nombreUsuario` (REQ-FUN-01, §4). No loguear datos de la fila.
     */
    override fun findByUsername(nombreUsuario: String): RegistroPendienteRow? =
        RegistroPendienteTable.selectAll()
            .where { RegistroPendienteTable.nombreUsuario eq nombreUsuario }
            .firstOrNull()
            ?.let { aFila(it) }

    /**
     * Limpieza lazy de registros expirados (V2): elimina la fila de [correo] si su
     * `expira_en` ya pasó, liberando el correo/usuario para un nuevo registro. Se invoca
     * desde `RegistrationService.register`, dentro de la misma transacción que valida e
     * inserta, para garantizar atomicidad.
     */
    override fun deleteExpiredByEmail(correo: String) {
        RegistroPendienteTable.deleteWhere {
            (RegistroPendienteTable.expiraEn less LocalDateTime.now()) and
                (RegistroPendienteTable.correo eq correo)
        }
    }

    /**
     * Limpieza lazy de registros expirados por username (V2): elimina la fila de
     * [nombreUsuario] si su `expira_en` ya pasó. Libera el username de un registro
     * abandonado; V1 (username ocupado) aplica solo a cuentas `usuario` en soft delete.
     */
    override fun deleteExpiredByUsername(nombreUsuario: String) {
        RegistroPendienteTable.deleteWhere {
            (RegistroPendienteTable.expiraEn less LocalDateTime.now()) and
                (RegistroPendienteTable.nombreUsuario eq nombreUsuario)
        }
    }

    private fun aFila(fila: ResultRow): RegistroPendienteRow =
        RegistroPendienteRow(
            idRegistro = fila[RegistroPendienteTable.idRegistro].toLong(),
            correo = fila[RegistroPendienteTable.correo],
            nombreUsuario = fila[RegistroPendienteTable.nombreUsuario],
            contrasenaHash = fila[RegistroPendienteTable.contrasenaHash],
            nombreMenor = fila[RegistroPendienteTable.nombreMenor],
            fechaNacimiento = fila[RegistroPendienteTable.fechaNacimiento],
            nombreAcudiente = fila[RegistroPendienteTable.nombreAcudiente],
            cedulaAcudiente = fila[RegistroPendienteTable.cedulaAcudiente],
            avatar = fila[RegistroPendienteTable.avatar],
            codigoHash = fila[RegistroPendienteTable.codigoHash],
            intentosFallidos = fila[RegistroPendienteTable.intentosFallidos].toInt(),
            expiraEn = fila[RegistroPendienteTable.expiraEn],
            creadoEn = fila[RegistroPendienteTable.creadoEn],
        )
}
