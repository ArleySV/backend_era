package com.era.backend

import com.era.backend.config.loadAppConfig
import com.era.backend.controllers.AuthController
import com.era.backend.database.DatabaseMigrator
import com.era.backend.plugins.DatabaseFactory
import com.era.backend.plugins.configurePlugins
import com.era.backend.repositories.ExposedAcudienteRepository
import com.era.backend.repositories.ExposedConfiguracionRepository
import com.era.backend.repositories.ExposedRegistroPendienteRepository
import com.era.backend.repositories.ExposedTransactionRunner
import com.era.backend.repositories.ExposedUsuarioRepository
import com.era.backend.routes.authRoutes
import com.era.backend.services.JwtTokenService
import com.era.backend.services.LoginService
import com.era.backend.services.OtpService
import com.era.backend.services.RegistrationService
import com.era.backend.services.SimpleJavaMailOtpNotifier
import com.era.backend.services.VerificationService
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.netty.EngineMain
import io.ktor.server.routing.routing

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    configurePlugins()

    val config = loadAppConfig()
    val dataSource = DatabaseFactory.createDataSource(config.database)

    monitor.subscribe(ApplicationStopped) {
        DatabaseFactory.close(dataSource)
    }

    // Un mismo pool HikariCP para Flyway y para Exposed (no hay segunda fuente).
    DatabaseMigrator.migrate(dataSource)
    DatabaseFactory.connectExposed(dataSource)

    // Composición de dependencias del Módulo A/A.1: repositorios Exposed → services → controller.
    val registroRepository = ExposedRegistroPendienteRepository()
    val usuarioRepository = ExposedUsuarioRepository()
    val acudienteRepository = ExposedAcudienteRepository()
    val configuracionRepository = ExposedConfiguracionRepository()

    // Modo dev (V10/V10.1/V11): APP_DEV_MODE=true activa el OTP fijo ("123456") y el envío
    // SMTP No-Op (println en consola). Lógica VITAL para el smoke test E2E; no eliminar.
    val esModoDev = config.devMode
    val notifier = SimpleJavaMailOtpNotifier(config.mail, modoNoOp = esModoDev)
    val otpService = OtpService(notifier, otpDeterminista = esModoDev)

    val registrationService =
        RegistrationService(registroRepository, usuarioRepository, otpService, ExposedTransactionRunner)
    val verificationService =
        VerificationService(
            registroRepository,
            usuarioRepository,
            acudienteRepository,
            configuracionRepository,
            otpService,
            ExposedTransactionRunner,
        )
    // Módulo B (login): emisión del token de sesión (30 días, JWT_SECRET) + reglas de login.
    val loginService =
        LoginService(usuarioRepository, ExposedTransactionRunner, JwtTokenService(config.jwt))

    val authController = AuthController(registrationService, verificationService, loginService)

    // Contrato público de autenticación (Módulos A, A.1 y B): register, verify-email,
    // resend-otp, login.
    routing {
        authRoutes(authController)
    }
}
