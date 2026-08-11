package com.era.backend.controllers

import com.era.backend.exceptions.FieldError
import com.era.backend.exceptions.ValidationException
import com.era.backend.models.SesionPrincipal
import com.era.backend.models.dto.ComentarioRequestDto
import com.era.backend.models.dto.MensajeResponseDto
import com.era.backend.services.ComentarioService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond

/**
 * Handler de `POST /api/v1/feedback/comments` (Módulo H). Protegido por el proveedor
 * `session-jwt` (`plugins/AuthenticationConfig.kt`): la identidad llega como
 * [SesionPrincipal], nunca como parámetro del cliente.
 *
 * Valida la forma del input (primera línea de defensa, `modulo-h-analisis.md` §3.1) —
 * `isBlank()` y `length > 2000` — ANTES de invocar al Service, y delega las reglas de
 * negocio en [ComentarioService]. No decide políticas de negocio.
 */
class FeedbackController(
    private val comentarioService: ComentarioService,
) {

    /**
     * Endpoint `POST /api/v1/feedback/comments` (REQ-FUN-14, CU-10, HU-14).
     *
     * Validaciones de forma (§3.1): `contenido` no blanco y ≤ 2000 caracteres. Los mensajes
     * de error son genéricos y **nunca** incluyen el texto del comentario (regla de oro §5).
     *
     * Respuestas (mapeadas por StatusPages): 200 `MensajeResponseDto` · 400
     * `VALIDATION_ERROR` · 401 challenge del proveedor · 403 `ACCOUNT_INACTIVE`.
     */
    suspend fun enviarComentario(call: ApplicationCall): Unit {
        val sesion = call.principal<SesionPrincipal>()
            ?: throw IllegalStateException("Sesión no resuelta en ruta autenticada.")
        val request = call.receive<ComentarioRequestDto>()

        val errores = mutableListOf<FieldError>()
        if (request.contenido.isBlank()) {
            errores += FieldError("contenido", "Es obligatorio.")
        } else if (request.contenido.length > LIMITE_CARACTERES) {
            errores += FieldError("contenido", "Máximo $LIMITE_CARACTERES caracteres.")
        }

        if (errores.isNotEmpty()) {
            throw ValidationException("Datos del comentario inválidos.", errores)
        }

        val respuesta: MensajeResponseDto =
            comentarioService.enviarComentario(sesion.idUsuario, request.contenido)
        call.respond(HttpStatusCode.OK, respuesta)
    }

    companion object {
        /** Límite de caracteres del comentario (decisión aprobada, §3.1). */
        const val LIMITE_CARACTERES = 2000
    }
}
