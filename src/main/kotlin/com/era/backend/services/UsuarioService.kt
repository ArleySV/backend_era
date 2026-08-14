package com.era.backend.services

import at.favre.lib.crypto.bcrypt.BCrypt
import com.era.backend.exceptions.AccountInactiveException
import com.era.backend.exceptions.ConflictException
import com.era.backend.exceptions.InvalidCredentialsException
import com.era.backend.exceptions.NotFoundException
import com.era.backend.models.dto.ActualizarUsuarioRequestDto
import com.era.backend.models.dto.EliminarCuentaRequestDto
import com.era.backend.models.dto.MensajeResponseDto
import com.era.backend.models.dto.UsuarioPerfilDto
import com.era.backend.models.entities.EstadoUsuario
import com.era.backend.models.entities.UsuarioRow
import com.era.backend.repositories.RegistroPendienteRepository
import com.era.backend.repositories.TransactionRunner
import com.era.backend.repositories.UsuarioRepository

/**
 * Reglas de negocio de los Módulos D y E (`modulo-d-analisis.md`) — consulta y edición de
 * perfil (REQ-FUN-06, CU-06, HU-06) y eliminación de cuenta por soft delete (REQ-FUN-05, CU-07,
 * HU-05). Puro de Ktor y de SQL: recibe DTOs, lanza excepciones de dominio y delega el
 * acceso a datos en [UsuarioRepository] y [RegistroPendienteRepository] (ARQUITECTURA_BASE.md §2.3).
 *
 * Seguridad:
 * - [consultarPerfil] y [actualizarNombreUsuario] aplican **mínimo privilegio** (D-4): devuelven
 *   solo los 5 campos del `UsuarioPerfilDto`; cédula, nombre del acudiente, hash y contadores
 *   jamás salen.
 * - [eliminarCuenta] reverifica la contraseña (REQ-FUN-05 CA2) y, si es correcta, hace
 *   **soft delete por estado** (`estado = 'eliminado'`), nunca un borrado físico
 *   (CLAUDE.md §7). La verificación bcrypt ocurre FUERA de la transacción (D-3) y el
 *   cambio de estado se aplica en una segunda transacción con guarda anti-carrera.
 * - **Zero logs:** nunca se loguea la contraseña, el hash, el correo, la cédula ni el username
 *   (CLAUDE.md §6).
 */
class UsuarioService(
    private val usuarioRepository: UsuarioRepository,
    private val registroPendienteRepository: RegistroPendienteRepository,
    private val transactionRunner: TransactionRunner,
) {

    companion object {
        /** Mensaje de cuenta en soft delete (D-1): mismo texto que `LoginService`. */
        private const val MENSAJE_CUENTA_INACTIVA = "La cuenta no está activa."

        /** Mensaje genérico de credenciales (D-2): mismo texto que el login (Módulo B). */
        private const val MENSAJE_CREDENCIALES = "Credenciales incorrectas."

        /** Mensaje de éxito del soft delete (D-5, contrato §4.2). */
        private const val MENSAJE_ELIMINADA = "Cuenta eliminada. Tus datos se conservan."

        /** Mensaje de username en uso (V1): idéntico al del registro (Módulo A). */
        private const val MENSAJE_USERNAME_EN_USO = "El nombre de usuario ya está en uso."
    }

    /**
     * Consulta el perfil del usuario autenticado (REQ-FUN-06).
     *
     * Respuestas: 200 con el `UsuarioPerfilDto` · 403 `ACCOUNT_INACTIVE` (D-1) · 404
     * `NOT_FOUND` (defensivo: token válido pero fila inexistente).
     */
    fun consultarPerfil(idUsuario: Long): UsuarioPerfilDto {
        // Exposed exige contexto transaccional incluso para el SELECT (§7.1). La lectura se
        // captura en `var` dentro del bloque y se consume fuera; cero logs de la fila.
        var fila: UsuarioRow? = null
        transactionRunner.run { fila = usuarioRepository.findById(idUsuario) }

        val usuario = fila ?: throw NotFoundException("Usuario no encontrado.")
        if (usuario.estado != EstadoUsuario.ACTIVO) {
            throw AccountInactiveException(MENSAJE_CUENTA_INACTIVA)
        }
        return mapearPerfil(usuario)
    }

    /**
     * Actualiza el nombre de usuario del perfil autenticado (Módulo D, `PATCH /users/me`,
     * REQ-FUN-06 CA5, CU-06, HU-06).
     *
     * Reglas (en una sola transacción, anti-TOCTOU):
     * 1. Lectura con `FOR UPDATE` (guarda anti-carrera frente a `DELETE /me` y a PATCH
     *    concurrentes) y verificación de cuenta `ACTIVO` → 403 `ACCOUNT_INACTIVE` (D-1).
     * 2. Unicidad del nuevo username con **exclusión del propio usuario**: cuentas activas y
     *    en soft delete (V1: el username de una cuenta eliminada permanece ocupado) y filas de
     *    `registro_pendiente` (espejo del alta: un correo sin verificar reserva su username).
     *    → 409 `CONFLICT`.
     * 3. UPDATE de la columna; el UNIQUE de BD queda como backstop anti-carrera.
     *
     * Respuestas: 200 con el `UsuarioPerfilDto` actualizado (mínimo privilegio, D-4) · 403 · 404
     * defensivo · 409 `CONFLICT`.
     */
    fun actualizarNombreUsuario(idUsuario: Long, request: ActualizarUsuarioRequestDto): UsuarioPerfilDto {
        val nuevoNombre = request.nombreUsuario
        var actualizado: UsuarioRow? = null
        transactionRunner.run {
            val usuario = usuarioRepository.findByIdForUpdate(idUsuario)
                ?: throw NotFoundException("Usuario no encontrado.")
            if (usuario.estado != EstadoUsuario.ACTIVO) {
                throw AccountInactiveException(MENSAJE_CUENTA_INACTIVA)
            }
            if (usuarioRepository.existsByUsername(nuevoNombre, excluirId = idUsuario)) {
                throw ConflictException(MENSAJE_USERNAME_EN_USO)
            }
            if (registroPendienteRepository.findByUsername(nuevoNombre) != null) {
                throw ConflictException(MENSAJE_USERNAME_EN_USO)
            }
            usuarioRepository.actualizarNombreUsuario(idUsuario, nuevoNombre)
            actualizado = usuario.copy(nombreUsuario = nuevoNombre)
        }
        return actualizado?.let { mapearPerfil(it) }
            ?: throw NotFoundException("Usuario no encontrado.")
    }

    /**
     * Elimina la cuenta del usuario autenticado (REQ-FUN-05), previa reverificación de su
     * contraseña (CA2).
     *
     * Respuestas: 200 con el `MensajeResponseDto` · 401 `INVALID_CREDENTIALS` (D-2) · 403
     * `ACCOUNT_INACTIVE` (D-1) · 404 `NOT_FOUND` (defensivo).
     */
    fun eliminarCuenta(idUsuario: Long, request: EliminarCuentaRequestDto): MensajeResponseDto {
        var fila: UsuarioRow? = null
        transactionRunner.run {
            // FOR UPDATE: serializa eliminaciones concurrentes del mismo usuario.
            fila = usuarioRepository.findByIdForUpdate(idUsuario)
        }

        val usuario = fila ?: throw NotFoundException("Usuario no encontrado.")
        if (usuario.estado != EstadoUsuario.ACTIVO) {
            throw AccountInactiveException(MENSAJE_CUENTA_INACTIVA)
        }

        // D-3: la verificación bcrypt ocurre FUERA de la transacción (no retener la
        // conexión ni el lock durante el coste del hash; espejo de Módulo C §5.1).
        val credencialValida =
            BCrypt.verifyer()
                .verify(request.contrasena.toCharArray(), usuario.contrasenaHash)
                .verified
        if (!credencialValida) {
            throw InvalidCredentialsException(MENSAJE_CREDENCIALES)
        }

        transactionRunner.run {
            // Segunda transacción (D-3) con guarda anti-carrera: si entre la lectura y este
            // UPDATE la cuenta cambió de estado, no se sobrescribe.
            val actual = usuarioRepository.findByIdForUpdate(idUsuario)
            if (actual != null && actual.estado == EstadoUsuario.ACTIVO) {
                usuarioRepository.actualizarEstado(idUsuario, EstadoUsuario.ELIMINADO)
            }
        }
        return MensajeResponseDto(MENSAJE_ELIMINADA)
    }

    /**
     * Mapeo de mínimo privilegio (D-4): del `UsuarioRow` interno se proyecta SOLO el
     * `UsuarioPerfilDto` de 5 campos. Compartido por [consultarPerfil] y
     * [actualizarNombreUsuario] para garantizar el mismo contrato de respuesta.
     */
    private fun mapearPerfil(usuario: UsuarioRow): UsuarioPerfilDto =
        UsuarioPerfilDto(
            nombreMenor = usuario.nombreMenor,
            fechaNacimiento = usuario.fechaNacimiento.toString(), // D-8: ISO yyyy-MM-dd
            correo = usuario.correo,
            nombreUsuario = usuario.nombreUsuario,
            avatar = usuario.avatar,
        )
}
