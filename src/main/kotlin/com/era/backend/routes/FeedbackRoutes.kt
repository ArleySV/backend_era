package com.era.backend.routes

import com.era.backend.controllers.FeedbackController
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Declaración del contrato de comentarios (Módulo H, `modulo-h-analisis.md` §4). Solo path
 * + verbo + delegación al controller; sin validaciones, negocio ni SQL.
 *
 * Todo el bloque vive dentro de `authenticate("session-jwt")`: sin un token de sesión
 * válido (`plugins/AuthenticationConfig.kt`) la petición no llega al controller y el
 * `challenge` responde 401 `UNAUTHORIZED` (§4.2).
 */
fun Route.feedbackRoutes(feedbackController: FeedbackController) {

    /**
     * `POST /api/v1/feedback/comments` — recibe y persiste el comentario/sugerencia del
     * usuario autenticado; responde la confirmación de recepción (REQ-FUN-14 CA3).
     */
    route("/api/v1/feedback") {
        authenticate("session-jwt") {
            post("/comments") { feedbackController.enviarComentario(call) }
        }
    }
}
