package com.era.backend.routes

import com.era.backend.controllers.AuthController
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Declaración del contrato público de autenticación (Módulo A, ARQUITECTURA_BASE.md §2.1).
 * Solo path + verbo + delegación al controller; sin validaciones, negocio ni SQL.
 */
fun Route.authRoutes(authController: AuthController) {

    /**
     * `POST /api/v1/auth/register` — alta de `registro_pendiente` + envío del OTP
     * (REQ-FUN-01, CU-01). La verificación del código es del Módulo A.1 (`verify-email`).
     */
    route("/api/v1/auth") {
        post("/register") { authController.register(call) }
    }
}
