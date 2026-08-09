package com.era.backend.repositories

import com.era.backend.models.entities.RegistroPendienteRow
import java.time.LocalDateTime

/**
 * Fake en memoria de [RegistroPendienteRepository] para unit tests de los Módulos A y
 * A.1. Reproduce el contrato que usan `RegistrationService` (insert, findByEmail,
 * findByUsername, deleteExpiredByEmail, deleteExpiredByUsername) y `VerificationService`
 * (findByEmailForUpdate, deleteById, actualizarIntentosFallidos,
 * actualizarCodigoReenvio), incluyendo la misma lógica de expiración que la
 * implementación Exposed (V2: solo se borra si `expiraEn` ya pasó).
 */
class FakeRegistroPendienteRepository : RegistroPendienteRepository {

    private val filas = mutableListOf<RegistroPendienteRow>()
    private var siguienteId = 1L

    /** Siembra una fila directamente (sin pasar por `insert`) para los tests de V2/unicidad. */
    fun seed(row: RegistroPendienteRow) {
        filas += row
    }

    override fun insert(row: RegistroPendienteRow): Long {
        val conId = row.copy(idRegistro = siguienteId)
        siguienteId += 1
        filas += conId
        return conId.idRegistro
    }

    override fun findByEmail(correo: String): RegistroPendienteRow? =
        filas.firstOrNull { it.correo == correo }

    override fun findByUsername(nombreUsuario: String): RegistroPendienteRow? =
        filas.firstOrNull { it.nombreUsuario == nombreUsuario }

    override fun deleteExpiredByEmail(correo: String) {
        filas.removeAll { it.correo == correo && it.expiraEn.isBefore(LocalDateTime.now()) }
    }

    override fun deleteExpiredByUsername(nombreUsuario: String) {
        filas.removeAll { it.nombreUsuario == nombreUsuario && it.expiraEn.isBefore(LocalDateTime.now()) }
    }

    override fun findByEmailForUpdate(correo: String): RegistroPendienteRow? = findByEmail(correo)

    override fun deleteById(idRegistro: Long) {
        filas.removeAll { it.idRegistro == idRegistro }
    }

    override fun actualizarIntentosFallidos(idRegistro: Long, nuevosIntentos: Int) {
        val idx = filas.indexOfFirst { it.idRegistro == idRegistro }
        if (idx >= 0) filas[idx] = filas[idx].copy(intentosFallidos = nuevosIntentos)
    }

    override fun actualizarCodigoReenvio(
        idRegistro: Long,
        codigoHash: String,
        expiraEn: LocalDateTime,
        ahora: LocalDateTime,
    ) {
        val idx = filas.indexOfFirst { it.idRegistro == idRegistro }
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

    fun size(): Int = filas.size
}
