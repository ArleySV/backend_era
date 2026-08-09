package com.era.backend.repositories

import com.era.backend.models.entities.TokensReseteoRow
import com.era.backend.models.entities.TokensReseteoTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.LocalDateTime

/**
 * Implementación real de [TokensReseteoRepository] sobre Exposed (ARQUITECTURA_BASE.md
 * §2.4). Aísla el SQL y el mapeo a [TokensReseteoRow]; el service nunca ve queries ni
 * columnas. Debe ejecutarse dentro de una transacción (la provee `PasswordResetService`
 * vía `TransactionRunner`).
 */
class ExposedTokensReseteoRepository : TokensReseteoRepository {

    /**
     * Persiste el [jti] del token puente con `expira_en = now + 10 min` y
     * `consumido = false` (Módulo C paso 2). El JWT completo jamás se persiste ni se
     * loguea; solo su identificador. Nunca loguear el `jti`.
     */
    override fun insert(row: TokensReseteoRow): Long {
        val id =
            (TokensReseteoTable.insert {
                it[TokensReseteoTable.jti] = row.jti
                it[TokensReseteoTable.idUsuario] = row.idUsuario.toInt()
                it[TokensReseteoTable.expiraEn] = row.expiraEn
                it[TokensReseteoTable.consumido] = row.consumido
            }) get TokensReseteoTable.idToken
        return id.toLong()
    }

    /**
     * Lock de escritura sobre la fila del token por [jti] (Módulo C, single-use C-3):
     * `SELECT ... FOR UPDATE` serializa consumos concurrentes del mismo token. Debe
     * ejecutarse dentro de la transacción del `/confirm`. Nunca loguear el `jti`.
     */
    override fun findByJtiForUpdate(jti: String): TokensReseteoRow? =
        TokensReseteoTable.selectAll()
            .where { TokensReseteoTable.jti eq jti }
            .forUpdate(ForUpdateOption.ForUpdate)
            .firstOrNull()
            ?.let { aFila(it) }

    /** Marca el token como consumido (single-use) tras un cambio de contraseña exitoso. */
    override fun marcarConsumido(idToken: Long) {
        TokensReseteoTable.update({ TokensReseteoTable.idToken eq idToken.toInt() }) {
            it[TokensReseteoTable.consumido] = true
        }
    }

    /**
     * Limpieza lazy de tokens expirados del usuario (patrón V2): elimina sus filas cuyo
     * `expira_en` ya pasó.
     */
    override fun deleteExpiradosPorUsuario(idUsuario: Long) {
        TokensReseteoTable.deleteWhere {
            (TokensReseteoTable.expiraEn less LocalDateTime.now()) and
                (TokensReseteoTable.idUsuario eq idUsuario.toInt())
        }
    }

    private fun aFila(fila: ResultRow): TokensReseteoRow =
        TokensReseteoRow(
            idToken = fila[TokensReseteoTable.idToken].toLong(),
            jti = fila[TokensReseteoTable.jti],
            idUsuario = fila[TokensReseteoTable.idUsuario].toLong(),
            expiraEn = fila[TokensReseteoTable.expiraEn],
            consumido = fila[TokensReseteoTable.consumido],
            creadoEn = fila[TokensReseteoTable.creadoEn],
        )
}
