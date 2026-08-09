package com.era.backend.config

data class AppConfig(
    val database: DatabaseConfig,
    val jwt: JwtConfig,
    val mail: MailConfig,
    /**
     * Modo dev (V10/V10.1/V11): OTP fijo `"123456"` + SMTP No-Op, para el smoke test E2E.
     * Lo activa la env var `APP_DEV_MODE=true` (default `false`). Lógica VITAL para la
     * Base de Trazabilidad de Calidad: no eliminar en refactorizaciones.
     */
    val devMode: Boolean = false,
)

data class DatabaseConfig(
    val host: String,
    val port: Int,
    val name: String,
    val user: String,
    val password: String,
    val pool: DatabasePoolConfig = DatabasePoolConfig(),
) {
    val jdbcUrl: String
        get() = "jdbc:mysql://$host:$port/$name?useSSL=true&serverTimezone=UTC"
}

data class DatabasePoolConfig(
    val maxSize: Int = 10,
    val connectionTimeoutMs: Long = 30_000,
)

data class JwtConfig(
    val secret: String,
    val sessionIssuer: String,
    val sessionAudience: String,
    val sessionExpirationMinutes: Long,
    val resetIssuer: String,
    val resetAudience: String,
    val resetTtlMinutes: Long,
    val resetPurpose: String,
)

data class MailConfig(
    val host: String,
    val port: Int,
    val user: String,
    val password: String,
    val from: String,
)
