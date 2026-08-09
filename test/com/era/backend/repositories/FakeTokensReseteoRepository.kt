package com.era.backend.repositories

import com.era.backend.models.entities.TokensReseteoRow
import java.time.LocalDateTime

/**
 * Fake en memoria de [TokensReseteoRepository] para unit tests del Módulo C.
 * Reproduce el contrato que usa `PasswordResetService`: insert (paso 2),
 * findByJtiForUpdate (single-use, paso 3), marcarConsumido y la limpieza lazy de
 * expirados por usuario.
 */
class FakeTokensReseteoRepository : TokensReseteoRepository {

    private val filas = mutableListOf<TokensReseteoRow>()
    private var siguienteId = 1L

    /** Siembra una fila directamente (sin pasar por `insert`) para los tests de single-use. */
    fun seed(row: TokensReseteoRow) {
        filas += row
    }

    fun size(): Int = filas.size

    /** Devuelve la última fila del usuario, espejo de `findByJtiForUpdate`. */
    fun ultimoDe(idUsuario: Long): TokensReseteoRow? =
        filas.lastOrNull { it.idUsuario == idUsuario }

    override fun insert(row: TokensReseteoRow): Long {
        val conId = row.copy(idToken = siguienteId)
        siguienteId += 1
        filas += conId
        return conId.idToken
    }

    override fun findByJtiForUpdate(jti: String): TokensReseteoRow? =
        filas.firstOrNull { it.jti == jti }

    override fun marcarConsumido(idToken: Long) {
        val idx = filas.indexOfFirst { it.idToken == idToken }
        if (idx >= 0) filas[idx] = filas[idx].copy(consumido = true)
    }

    override fun deleteExpiradosPorUsuario(idUsuario: Long) {
        filas.removeAll { it.idUsuario == idUsuario && it.expiraEn.isBefore(LocalDateTime.now()) }
    }
}
