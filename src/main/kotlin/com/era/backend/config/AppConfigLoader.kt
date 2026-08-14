package com.era.backend.config

import io.ktor.server.application.Application
import io.ktor.server.config.ApplicationConfig

fun ApplicationConfig.toAppConfig(): AppConfig {
    fun path(key: String): String = property(key).getString()

    fun pathOrNull(key: String): String? = propertyOrNull(key)?.getString()

    return AppConfig(
        database =
            DatabaseConfig(
                host = path("database.host"),
                port = path("database.port").toInt(),
                name = path("database.name"),
                user = path("database.user"),
                password = path("database.password"),
                pool =
                    DatabasePoolConfig(
                        maxSize = pathOrNull("database.pool.maxSize")?.toInt() ?: DatabasePoolConfig().maxSize,
                        connectionTimeoutMs =
                            pathOrNull("database.pool.connectionTimeoutMs")?.toLong() ?: DatabasePoolConfig().connectionTimeoutMs,
                    ),
            ),
        jwt =
            JwtConfig(
                secret = validarJwtSecret(path("jwt.secret")),
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
        storage =
            StorageConfig(
                avatarDir = validarAvatarStorageDir(path("storage.avatarDir")),
            ),
        // V11: switch explícito de entorno, NO derivado del JWT_SECRET. Default false.
        devMode = System.getenv("APP_DEV_MODE") == "true",
    )
}

/**
 * Fail-fast del secreto JWT (Parte 1, fase final): `JWT_SECRET` no debe ser una cadena
 * vacía. Un valor ausente ya aborta en `YamlConfigLoader` (placeholder no resoluble); este
 * `require` cierra el hueco del valor **presente pero vacío**, que resolvería a una clave
 * HMAC degenerada (HS256 con key vacía) y dejaría arrancar el servidor con un secreto
 * trivialmente adivinable — violando "no hay fallback ni derivación" (modulo-b-analisis.md
 * §4, auditoría #3). Mismo patrón de mensaje claro que [validarAvatarStorageDir].
 */
private fun validarJwtSecret(secret: String): String {
    require(secret.isNotBlank()) {
        "La variable de entorno JWT_SECRET está definida pero vacía. " +
            "No hay fallback ni derivación (modulo-b-analisis.md §4): se debe configurar un secreto."
    }
    return secret
}

/**
 * Fail-fast del Módulo I en la carga de configuración (paso 3, `modulo-i-analisis.md` §7.6):
 * `AVATAR_STORAGE_DIR` debe existir y no estar vacía; un valor ausente/blanco aborta el
 * arranque antes de tocar BD o disco. La creación del directorio en el filesystem (segunda
 * capa del fail-fast, cuando la ruta existe pero no es creable) la realiza el `init` de
 * [com.era.backend.storage.LocalDiskAvatarStorage] en el wiring de `Application.kt`.
 */
private fun validarAvatarStorageDir(avatarDir: String): String {
    require(avatarDir.isNotBlank()) {
        "La variable de entorno AVATAR_STORAGE_DIR no está definida o está vacía."
    }
    return avatarDir
}

fun Application.loadAppConfig(): AppConfig = environment.config.toAppConfig()
