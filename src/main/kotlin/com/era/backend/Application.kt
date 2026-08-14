package com.era.backend

import com.era.backend.config.loadAppConfig
import com.era.backend.controllers.AuthController
import com.era.backend.controllers.AvatarController
import com.era.backend.controllers.FeedbackController
import com.era.backend.controllers.ProgressController
import com.era.backend.controllers.UsuarioController
import com.era.backend.database.DatabaseMigrator
import com.era.backend.plugins.DatabaseFactory
import com.era.backend.plugins.configureAuthentication
import com.era.backend.plugins.configurePlugins
import com.era.backend.repositories.ExposedAcudienteRepository
import com.era.backend.repositories.ExposedCodigoVerificacionRepository
import com.era.backend.repositories.ExposedComentarioRepository
import com.era.backend.repositories.ExposedConfiguracionRepository
import com.era.backend.repositories.ExposedNivelRepository
import com.era.backend.repositories.ExposedProgresoRepository
import com.era.backend.repositories.ExposedRegistroPendienteRepository
import com.era.backend.repositories.ExposedTokensReseteoRepository
import com.era.backend.repositories.ExposedTransactionRunner
import com.era.backend.repositories.ExposedUsuarioRepository
import com.era.backend.routes.authRoutes
import com.era.backend.routes.feedbackRoutes
import com.era.backend.routes.progressRoutes
import com.era.backend.routes.userRoutes
import com.era.backend.services.AvatarService
import com.era.backend.services.ComentarioService
import com.era.backend.services.JwtTokenService
import com.era.backend.services.LoginService
import com.era.backend.services.LogoutService
import com.era.backend.services.OtpService
import com.era.backend.services.PasswordResetService
import com.era.backend.services.ProgressSyncService
import com.era.backend.services.RegistrationService
import com.era.backend.services.SimpleJavaMailOtpNotifier
import com.era.backend.services.UsuarioService
import com.era.backend.services.VerificationService
import com.era.backend.storage.AvatarStorage
import com.era.backend.storage.LocalDiskAvatarStorage
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.netty.EngineMain
import io.ktor.server.routing.routing
import java.nio.file.Path

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    configurePlugins()

    val config = loadAppConfig()
    val dataSource = DatabaseFactory.createDataSource(config.database)

    // Proveedor JWT de sesión (`session-jwt`): SIEMPRE antes de `routing {}` (un
    // `authenticate` sobre un proveedor no instalado lanza en arranque). Módulos D/E.
    configureAuthentication(config.jwt)

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
    // Repositorios del Módulo C (recuperación de contraseña): OTP reutilizado y token puente.
    val codigoVerificacionRepository = ExposedCodigoVerificacionRepository()
    val tokensReseteoRepository = ExposedTokensReseteoRepository()

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
    // Instancia única de JwtTokenService compartida entre Módulo B (sesión) y Módulo C (token puente).
    val jwtTokenService = JwtTokenService(config.jwt)
    // Módulo B (login): emisión del token de sesión (30 días, JWT_SECRET) + reglas de login.
    val loginService =
        LoginService(usuarioRepository, ExposedTransactionRunner, jwtTokenService)
    // Módulo C (recuperación de contraseña): anti-enumeración, throttle, token puente y
    // veto a repetir la contraseña anterior. Reutiliza el OTP del Módulo A.
    val passwordResetService =
        PasswordResetService(
            usuarioRepository,
            codigoVerificacionRepository,
            tokensReseteoRepository,
            otpService,
            jwtTokenService,
            config.jwt,
            ExposedTransactionRunner,
        )

    // Módulo F (logout): stateless, sin BD; solo log de auditoría y confirmación formal.
    val logoutService = LogoutService()

    val authController =
        AuthController(
            registrationService,
            verificationService,
            loginService,
            passwordResetService,
            logoutService,
        )
    // Módulos D, D-PATCH y E (perfil, edición de username y eliminación de cuenta): rutas
    // protegidas por `session-jwt`. `UsuarioService` inyecta `registroPendienteRepository`
    // para el chequeo de unicidad del PATCH contra usernames reservados por registros sin
    // verificar (espejo del alta, Módulo A).
    val usuarioService =
        UsuarioService(usuarioRepository, registroRepository, ExposedTransactionRunner)
    val usuarioController = UsuarioController(usuarioService)

    // Módulo G (sincronización de progreso, CU-12): catálogo `nivel` como ancla referencial
    // + espejo `progreso_usuario`. El POST es atómico vía `ExposedTransactionRunner` (§6).
    val nivelRepository = ExposedNivelRepository()
    val progresoRepository = ExposedProgresoRepository()
    val progressSyncService =
        ProgressSyncService(usuarioRepository, nivelRepository, progresoRepository, ExposedTransactionRunner)
    val progressController = ProgressController(progressSyncService)

    // Módulo H (comentarios, REQ-FUN-14/CU-10/HU-14): solo escritura en `comentario`.
    val comentarioRepository = ExposedComentarioRepository()
    val comentarioService =
        ComentarioService(usuarioRepository, comentarioRepository, ExposedTransactionRunner)
    val feedbackController = FeedbackController(comentarioService)

    // Módulo I (avatar personalizado, REQ-FUN-06 CA4/CA5/CU-06 3a): inyección por capas
    // Repository -> Storage -> Service -> Controller. El storage se inyecta SIEMPRE como la
    // interfaz AvatarStorage (abstracción aprobada, §7.3), nunca la implementación concreta:
    // migrar a S3 es reemplazar esta línea sin tocar service/controller/rutas. Fail-fast del
    // filesystem: el `init` de LocalDiskAvatarStorage crea el directorio o aborta el arranque.
    val avatarStorage: AvatarStorage = LocalDiskAvatarStorage(Path.of(config.storage.avatarDir))
    val avatarService = AvatarService(usuarioRepository, avatarStorage, ExposedTransactionRunner)
    val avatarController = AvatarController(avatarService)

    // Contrato público de autenticación (Módulos A, A.1, B, C y F): register, verify-email,
    // resend-otp, login, password-reset/request, password-reset/verify, password-reset/confirm,
    // logout.
    routing {
        authRoutes(authController)
        userRoutes(usuarioController, avatarController)
        progressRoutes(progressController)
        feedbackRoutes(feedbackController)
    }
}
