package com.era.backend.config

import io.ktor.server.application.Application
import io.ktor.server.config.ApplicationConfig

fun ApplicationConfig.toAppConfig(): AppConfig {
    fun path(key: String): String = property(key).getString()

    return AppConfig(
        database =
            DatabaseConfig(
                host = path("database.host"),
                port = path("database.port").toInt(),
                name = path("database.name"),
                user = path("database.user"),
                password = path("database.password"),
            ),
        jwt =
            JwtConfig(
                secret = path("jwt.secret"),
                sessionIssuer = path("jwt.session.issuer"),
                sessionAudience = path("jwt.session.audience"),
                sessionExpirationMinutes = path("jwt.session.expirationMinutes").toLong(),
                resetIssuer = path("jwt.passwordReset.issuer"),
                resetAudience = path("jwt.passwordReset.audience"),
                resetTtlMinutes = path("jwt.passwordReset.ttlMinutes").toLong(),
                resetPurpose = path("jwt.passwordReset.purpose"),
            ),
        mail =
            MailConfig(
                host = path("mail.host"),
                port = path("mail.port").toInt(),
                user = path("mail.user"),
                password = path("mail.password"),
                from = path("mail.from"),
            ),
    )
}

fun Application.loadAppConfig(): AppConfig = environment.config.toAppConfig()
