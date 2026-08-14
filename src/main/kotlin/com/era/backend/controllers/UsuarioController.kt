package com.era.backend.controllers

import com.era.backend.exceptions.FieldError
import com.era.backend.exceptions.ValidationException
import com.era.backend.models.SesionPrincipal
import com.era.backend.models.dto.ActualizarUsuarioRequestDto
import com.era.backend.models.dto.EliminarCuentaRequestDto
import com.era.backend.models.dto.MensajeResponseDto
import com.era.backend.models.dto.UsuarioPerfilDto
import com.era.backend.services.UsuarioService
import com.era.backend.utils.Validators
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond

/**
 * Handler de los endpoints de usuario autenticado (Módulos D y E:
 * `GET /api/v1/users/me`, `PATCH /api/v1/users/me` y `DELETE /api/v1/users/me`). Protegido
 * por el proveedor `session-jwt` (`plugins/AuthenticationConfig.kt`): la identidad llega como
 * [SesionPrincipal], nunca como parámetro del cliente.
 *
 * Valida la forma del input (primera barrera, ARQUITECTURA_BASE.md §2.2) y delega las
 * reglas de negocio en [UsuarioService]. No decide políticas de negocio.
 */
class UsuarioController(
    private val usuarioService: UsuarioService,
) {

    /**
     * Endpoint `GET /api/v1/users/me` (REQ-FUN-06, CU-06, HU-06).
     *
     * Sin body que validar: la identidad proviene del token de sesión (SesionPrincipal).
     * El service verifica el estado de la cuenta (403 `ACCOUNT_INACTIVE`, D-1), mapea el
     * `UsuarioPerfilDto` (mínimo privilegio, D-4) y responde 200.
     */
    suspend fun obtenerPerfil(call: ApplicationCall): Unit {
        val sesion = call.principal<SesionPrincipal>()
            ?: throw IllegalStateException("Sesión no resuelta en ruta autenticada.")
        val respuesta: UsuarioPerfilDto = usuarioService.consultarPerfil(sesion.idUsuario)
        call.respond(HttpStatusCode.OK, respuesta)
    }

    /**
     * Endpoint `PATCH /api/v1/users/me` (REQ-FUN-06 CA5, CU-06, HU-06).
     *
     * Validación de forma: `nombreUsuario` no blanco y V4 (3–60 caracteres sin espacios,
     * regla centralizada en `Validators`) → 400 `VALIDATION_ERROR` con `details`. La
     * unicidad (usuario activo/eliminado y pendiente) y el estado de la cuenta son reglas
     * del service.
     *
     * Respuestas del service (mapeadas por StatusPages): 200 `UsuarioPerfilDto` actualizado,
     * 403 `ACCOUNT_INACTIVE`, 404 defensivo, 409 `CONFLICT`.
     */
    suspend fun actualizarPerfil(call: ApplicationCall): Unit {
        val sesion = call.principal<SesionPrincipal>()
            ?: throw IllegalStateException("Sesión no resuelta en ruta autenticada.")
        val request = call.receive<ActualizarUsuarioRequestDto>()

        val errores = mutableListOf<FieldError>()

        if (request.nombreUsuario.isBlank()) {
            errores += FieldError("nombreUsuario", "Es obligatorio.")
        } else if (!Validators.isValidNombreUsuario(request.nombreUsuario)) {
            errores += FieldError("nombreUsuario", "Debe tener entre 3 y 60 caracteres sin espacios.")
        }

        if (errores.isNotEmpty()) {
            throw ValidationException("Datos de actualización inválidos.", errores)
        }

        val respuesta: UsuarioPerfilDto =
            usuarioService.actualizarNombreUsuario(sesion.idUsuario, request)
        call.respond(HttpStatusCode.OK, respuesta)
    }

    /**
     * Endpoint `DELETE /api/v1/users/me` (REQ-FUN-05, CU-07, HU-05).
     *
     * Validaciones de forma: `contrasena` no blanco y ≤ 72 (tope técnico de bcrypt, espejo
     * de login). La reverificación de contraseña (CA2, D-2), el estado de la cuenta (D-1)
     * y el soft delete (REQ-FUN-05, D-3) son reglas del service.
     *
     * Respuestas del service (mapeadas por StatusPages): 200 `MensajeResponseDto`, 401
     * `INVALID_CREDENTIALS`, 403 `ACCOUNT_INACTIVE`, 404 defensivo.
     */
    suspend fun eliminarCuenta(call: ApplicationCall): Unit {
        val sesion = call.principal<SesionPrincipal>()
            ?: throw IllegalStateException("Sesión no resuelta en ruta autenticada.")
        val request = call.receive<EliminarCuentaRequestDto>()

        val errores = mutableListOf<FieldError>()

        if (request.contrasena.isBlank()) {
            errores += FieldError("contrasena", "Es obligatoria.")
        } else if (request.contrasena.length > 72) {
            errores += FieldError("contrasena", "Máximo 72 caracteres.")
        }

        if (errores.isNotEmpty()) {
            throw ValidationException("Datos de eliminación inválidos.", errores)
        }

        val respuesta: MensajeResponseDto = usuarioService.eliminarCuenta(sesion.idUsuario, request)
        call.respond(HttpStatusCode.OK, respuesta)
    }
}
