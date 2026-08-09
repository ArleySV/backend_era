package com.era.backend.repositories

import com.era.backend.models.entities.CodigoVerificacionRow
import java.time.LocalDateTime

/**
 * Fake en memoria de [CodigoVerificacionRepository] para unit tests del Módulo C.
 * Reproduce el contrato que usa `PasswordResetService`: insert (paso 1),
 * findUltimoPorUsuarioForUpdate (paso 1/2), actualizarEnvio (P2), actualizarIntentosFallidos
 * (P1) y marcarUsado (single-use del OTP). `findUltimoPorUsuarioForUpdate` devuelve la
 * última fila sembrada del usuario, espejo del `ORDER BY id_codigo DESC LIMIT 1` de Exposed.
 */
class FakeCodigoVerificacionRepository : CodigoVerificacionRepository {

    private val filas = mutableListOf<CodigoVerificacionRow>()
    private var siguienteId = 1L

    /** Siembra una fila directamente (sin pasar por `insert`) para los tests de verificación. */
    fun seed(row: CodigoVerificacionRow) {
        filas += row
    }

    fun size(): Int = filas.size

    /** Devuelve la última fila del usuario, espejo de `findUltimoPorUsuarioForUpdate`. */
    fun ultimoDe(idUsuario: Long): CodigoVerificacionRow? =
        filas.lastOrNull { it.idUsuario == idUsuario }

    override fun insert(row: CodigoVerificacionRow): Long {
        val conId = row.copy(idCodigo = siguienteId)
        siguienteId += 1
        filas += conId
        return conId.idCodigo
    }

    override fun findUltimoPorUsuarioForUpdate(idUsuario: Long): CodigoVerificacionRow? =
        ultimoDe(idUsuario)

    override fun actualizarEnvio(
        idCodigo: Long,
        codigoHash: String,
        expiraEn: LocalDateTime,
        ahora: LocalDateTime,
    ) {
        val idx = filas.indexOfFirst { it.idCodigo == idCodigo }
        if (idx >= 0) {
            filas[idx] =
                filas[idx].copy(
                    codigoHash = codigoHash,
                    expiraEn = expiraEn,
                    intentosFallidos = 0,
                    ultimoEnvioEn = ahora,
                )
        }
    }

    override fun actualizarIntentosFallidos(idCodigo: Long, nuevosIntentos: Int) {
        val idx = filas.indexOfFirst { it.idCodigo == idCodigo }
        if (idx >= 0) filas[idx] = filas[idx].copy(intentosFallidos = nuevosIntentos)
    }

    override fun marcarUsado(idCodigo: Long) {
        val idx = filas.indexOfFirst { it.idCodigo == idCodigo }
        if (idx >= 0) filas[idx] = filas[idx].copy(usado = true)
    }
}
