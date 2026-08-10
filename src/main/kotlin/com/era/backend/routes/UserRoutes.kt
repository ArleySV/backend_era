package com.era.backend.routes

import com.era.backend.controllers.UsuarioController
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * Declaración del contrato de usuario autenticado (Módulos D y E, ARQUITECTURA_BASE.md
 * §2.1). Solo path + verbo + delegación al controller; sin validaciones, negocio ni SQL.
 *
 * Todo el bloque vive dentro de `authenticate("session-jwt")`: sin un token de sesión
 * válido (`plugins/AuthenticationConfig.kt`) la petición no llega al controller y el
 * `challenge` responde 401 `UNAUTHORIZED` (D-6).
 */
fun Route.userRoutes(usuarioController: UsuarioController) {

    /**
     * `GET /api/v1/users/me` — consulta del perfil del usuario autenticado
     * (Módulo D, REQ-FUN-06, CU-06, HU-06).
     *
     * `DELETE /api/v1/users/me` — eliminación de la propia cuenta por soft delete con
     * reverificación de contraseña (Módulo E, REQ-FUN-05, CU-07, HU-05).
     */
    route("/api/v1/users") {
        authenticate("session-jwt") {
            get("/me") { usuarioController.obtenerPerfil(call) }
            delete("/me") { usuarioController.eliminarCuenta(call) }
        }
    }
}
