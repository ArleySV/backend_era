package com.era.backend.repositories

import com.era.backend.models.entities.CodigoVerificacionRow
import com.era.backend.models.entities.CodigoVerificacionTable
import java.time.LocalDateTime
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Implementación real de [CodigoVerificacionRepository] sobre Exposed
 * (ARQUITECTURA_BASE.md §2.4). Aísla el SQL y el mapeo a [CodigoVerificacionRow]; el
 * service nunca ve queries ni columnas. Debe ejecutarse dentro de una transacción (la
 * provee `PasswordResetService` vía `TransactionRunner`).
 */
class ExposedCodigoVerificacionRepository : CodigoVerificacionRepository {

    /**
     * Persiste un código OTP de recuperación con hash bcrypt, `intentos_fallidos = 0`,
     * `expira_en = now + 10 min` y `usado = false` (REQ-FUN-07; Módulo C paso 1).
     * Seguridad (CLAUDE.md §6): nunca loguear `codigo_hash`.
     */
    override fun insert(row: CodigoVerificacionRow): Long {
        val id =
            (CodigoVerificacionTable.insert {
                it[CodigoVerificacionTable.idUsuario] = row.idUsuario.toInt()
                it[CodigoVerificacionTable.codigoHash] = row.codigoHash
                it[CodigoVerificacionTable.intentosFallidos] = row.intentosFallidos.toUByte()
                it[CodigoVerificacionTable.expiraEn] = row.expiraEn
                it[CodigoVerificacionTable.ultimoEnvioEn] = row.ultimoEnvioEn
                it[CodigoVerificacionTable.usado] = row.usado
            }) get CodigoVerificacionTable.idCodigo
        return id.toLong()
    }

    /**
     * Lock de escritura sobre el último código del usuario (Módulo C, C-3):
     * `SELECT ... FOR UPDATE` sobre `id_codigo DESC LIMIT 1`, respaldado por el índice
     * `idx_codigo_usuario_usado (id_usuario, usado)`. Serializa verificación/reenvío
     * concurrentes del mismo usuario. Debe ejecutarse dentro de la transacción del service.
     */
    override fun findUltimoPorUsuarioForUpdate(idUsuario: Long): CodigoVerificacionRow? =
        CodigoVerificacionTable.selectAll()
            .where { CodigoVerificacionTable.idUsuario eq idUsuario.toInt() }
            .orderBy(CodigoVerificacionTable.idCodigo to SortOrder.DESC)
            .limit(1)
            .forUpdate(ForUpdateOption.ForUpdate)
            .firstOrNull()
            ?.let { aFila(it) }

    /**
     * Reenvío de OTP (P2): nuevo hash del código, vigencia `+10 min`, contador a 0 y
     * `ultimo_envio_en = ahora` para el throttle de 60 s (C-2/C-5, espejo de V2).
     */
    override fun actualizarEnvio(
        idCodigo: Long,
        codigoHash: String,
        expiraEn: LocalDateTime,
        ahora: LocalDateTime,
    ) {
        CodigoVerificacionTable.update({ CodigoVerificacionTable.idCodigo eq idCodigo.toInt() }) {
            it[CodigoVerificacionTable.codigoHash] = codigoHash
            it[CodigoVerificacionTable.expiraEn] = expiraEn
            it[CodigoVerificacionTable.intentosFallidos] = 0u
            it[CodigoVerificacionTable.ultimoEnvioEn] = ahora
        }
    }

    /**
     * Persiste el contador de intentos fallidos (P1). El throw de la excepción de dominio
     * ocurre FUERA de la transacción del service para que este incremento se commitee.
     */
    override fun actualizarIntentosFallidos(idCodigo: Long, nuevosIntentos: Int) {
        CodigoVerificacionTable.update({ CodigoVerificacionTable.idCodigo eq idCodigo.toInt() }) {
            it[CodigoVerificacionTable.intentosFallidos] = nuevosIntentos.toUByte()
        }
    }

    /** Marca el código como usado (single-use del OTP) tras una verificación exitosa. */
    override fun marcarUsado(idCodigo: Long) {
        CodigoVerificacionTable.update({ CodigoVerificacionTable.idCodigo eq idCodigo.toInt() }) {
            it[CodigoVerificacionTable.usado] = true
        }
    }

    private fun aFila(fila: ResultRow): CodigoVerificacionRow =
        CodigoVerificacionRow(
            idCodigo = fila[CodigoVerificacionTable.idCodigo].toLong(),
            idUsuario = fila[CodigoVerificacionTable.idUsuario].toLong(),
            codigoHash = fila[CodigoVerificacionTable.codigoHash],
            intentosFallidos = fila[CodigoVerificacionTable.intentosFallidos].toInt(),
            expiraEn = fila[CodigoVerificacionTable.expiraEn],
            ultimoEnvioEn = fila[CodigoVerificacionTable.ultimoEnvioEn],
            usado = fila[CodigoVerificacionTable.usado],
            creadoEn = fila[CodigoVerificacionTable.creadoEn],
        )
}
