package com.era.backend.repositories

import com.era.backend.models.entities.EstadoUsuario
import com.era.backend.models.entities.UsuarioRow
import java.time.LocalDateTime

/**
 * Fake en memoria de [UsuarioRepository] para unit tests de los Módulos A y A.1. No toca
 * MySQL: reproduce exactamente el contrato que usan `RegistrationService` (findByEmail y
 * existsByUsername) y `VerificationService` (insert), sin SQL ni transacciones.
 */
class FakeUsuarioRepository : UsuarioRepository {

    private val porCorreo = mutableMapOf<String, UsuarioRow>()
    private var siguienteId = 1L

    /** Siembra un usuario (activo, eliminado o el que sea) para los tests de unicidad. */
    fun seed(usuario: UsuarioRow) {
        porCorreo[usuario.correo] = usuario
    }

    fun size(): Int = porCorreo.size

    /** Devuelve la fila con el id asignado (o el que traía, si no era 0). */
    override fun findById(idUsuario: Long): UsuarioRow? =
        porCorreo.values.firstOrNull { it.idUsuario == idUsuario }

    override fun findByEmail(correo: String): UsuarioRow? = porCorreo[correo]

    override fun findByEmailForUpdate(correo: String): UsuarioRow? = porCorreo[correo]

    override fun findByUsernameForUpdate(nombreUsuario: String): UsuarioRow? =
        porCorreo.values.firstOrNull { it.nombreUsuario.equals(nombreUsuario, ignoreCase = true) }

    override fun findByIdForUpdate(idUsuario: Long): UsuarioRow? = findById(idUsuario)

    override fun existsByUsername(nombreUsuario: String): Boolean =
        porCorreo.values.any { it.nombreUsuario == nombreUsuario }

    override fun insert(row: UsuarioRow): Long {
        val id = if (row.idUsuario == 0L) siguienteId++ else row.idUsuario
        porCorreo[row.correo] = row.copy(idUsuario = id)
        return id
    }

    /**
     * Espejo en memoria de `actualizarEstadoLogin`: muta la fila con el contador y la
     * ventana indicados, para que `LoginServiceTest` (Paso 3) pueda assertar el estado
     * tras cada intento (incremento, bloqueo, limpieza lazy y reset tras éxito).
     */
    override fun actualizarEstadoLogin(
        idUsuario: Long,
        intentosLoginFallidos: Int,
        bloqueadoHasta: LocalDateTime?,
    ) {
        val existente = porCorreo.values.firstOrNull { it.idUsuario == idUsuario } ?: return
        porCorreo[existente.correo] =
            existente.copy(
                intentosLoginFallidos = intentosLoginFallidos,
                bloqueadoHasta = bloqueadoHasta,
            )
    }

    /**
     * Espejo en memoria de `actualizarContrasena` (Módulo C): muta el hash de la fila para
     * que `PasswordResetServiceTest` pueda assertar el cambio y el veto a repetir la
     * contraseña anterior.
     */
    override fun actualizarContrasena(idUsuario: Long, contrasenaHash: String) {
        val existente = porCorreo.values.firstOrNull { it.idUsuario == idUsuario } ?: return
        porCorreo[existente.correo] = existente.copy(contrasenaHash = contrasenaHash)
    }

    /**
     * Espejo en memoria de `actualizarEstado` (Módulo E): muta el estado de la fila para
     * que `UsuarioServiceTest` pueda assertar el soft delete (REQ-FUN-05).
     */
    override fun actualizarEstado(idUsuario: Long, estado: EstadoUsuario) {
        val existente = porCorreo.values.firstOrNull { it.idUsuario == idUsuario } ?: return
        porCorreo[existente.correo] = existente.copy(estado = estado)
    }

    /**
     * Espejo en memoria de `actualizarAvatar` (Módulo I): muta la clave de storage en la fila
     * para que `AvatarServiceTest` pueda assertar la persistencia y la compensación.
     */
    override fun actualizarAvatar(idUsuario: Long, avatar: String?) {
        val existente = porCorreo.values.firstOrNull { it.idUsuario == idUsuario } ?: return
        porCorreo[existente.correo] = existente.copy(avatar = avatar)
    }
}
