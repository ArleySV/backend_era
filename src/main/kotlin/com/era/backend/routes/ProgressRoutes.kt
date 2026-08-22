package com.era.backend.routes

import com.era.backend.controllers.ProgressController
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Declaración del contrato de sincronización de progreso (Módulo G, `modulo-g-analisis.md`
 * §4). Solo path + verbo + delegación al controller; sin validaciones, negocio ni SQL.
 *
 * Todo el bloque vive dentro de `authenticate("session-jwt")`: sin un token de sesión
 * válido (`plugins/AuthenticationConfig.kt`) la petición no llega al controller y el
 * `challenge` responde 401 `UNAUTHORIZED` (§8).
 */
fun Route.progressRoutes(progressController: ProgressController) {

    /**
     * `GET /api/v1/progress/sync` — snapshot autoritativo del progreso del usuario
     * autenticado (bootstrap al login, cambio de dispositivo, reconciliación).
     *
     * `POST /api/v1/progress/sync` — sube el estado local acumulado; el servidor mergea
     * hacia adelante, persiste atómicamente y devuelve el snapshot resultante en un único
     * round-trip (CU-12 paso 3).
     *
     * `POST /api/v1/progress/reset` — reinicia todo el progreso del usuario (borra
     * intentos y progreso, deja nivel 1 como disponible). Requiere reverificación de
     * contraseña (patrón D-3 del Módulo E).
     */
    route("/api/v1/progress") {
        authenticate("session-jwt") {
            get("/sync") { progressController.getSync(call) }
            post("/sync") { progressController.postSync(call) }
            post("/reset") { progressController.reiniciarProgreso(call) }
        }
    }
}
