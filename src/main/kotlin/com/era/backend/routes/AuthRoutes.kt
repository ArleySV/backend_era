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
     * (REQ-FUN-01, CU-01).
     *
     * `POST /api/v1/auth/verify-email` — verificación del OTP y activación de la cuenta
     * (Módulo A.1, CU-11).
     *
     * `POST /api/v1/auth/resend-otp` — reenvío del OTP con throttle de 60 s (Módulo A.1, P2).
     */
    route("/api/v1/auth") {
        post("/register") { authController.register(call) }
        post("/verify-email") { authController.verifyEmail(call) }
        post("/resend-otp") { authController.resendOtp(call) }
        post("/login") { authController.login(call) }
    }
}
