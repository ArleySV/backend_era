package com.era.backend.repositories

import com.era.backend.models.entities.UsuarioRow

/**
 * Fake en memoria de [UsuarioRepository] para unit tests del Módulo A. No toca MySQL:
 * reproduce exactamente el contrato que usa `RegistrationService` (findByEmail y
 * existsByUsername), sin SQL ni transacciones.
 */
class FakeUsuarioRepository : UsuarioRepository {

    private val porCorreo = mutableMapOf<String, UsuarioRow>()

    /** Siembra un usuario (activo, eliminado o el que sea) para los tests de unicidad. */
    fun seed(usuario: UsuarioRow) {
        porCorreo[usuario.correo] = usuario
    }

    fun size(): Int = porCorreo.size

    override fun findByEmail(correo: String): UsuarioRow? = porCorreo[correo]

    override fun existsByUsername(nombreUsuario: String): Boolean =
        porCorreo.values.any { it.nombreUsuario == nombreUsuario }
}
