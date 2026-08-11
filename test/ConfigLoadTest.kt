package com.example

import com.era.backend.config.toAppConfig
import io.ktor.server.config.yaml.YamlConfigLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Valida la carga del YAML (`application.yaml`) y su mapeo a `AppConfig` **sin
 * arrancar `Application.module()`** (ni HikariCP/MySQL). La conexión real contra
 * MySQL se cubrirá aparte en un test de integración (ver nota en el plan).
 */
class ConfigLoadTest {
    @Test
    fun `yaml env substitution resolves via toAppConfig`() {
        val cfg = checkNotNull(YamlConfigLoader().load("application.yaml")).toAppConfig()

        assertEquals("era_db", cfg.database.name)
        assertTrue(cfg.database.jdbcUrl.startsWith("jdbc:mysql://"))
        assertEquals(10, cfg.database.pool.maxSize)
        assertEquals(30_000L, cfg.database.pool.connectionTimeoutMs)
        assertEquals(43200L, cfg.jwt.sessionExpirationMinutes)
        assertEquals("era-app-session", cfg.jwt.sessionAudience)
        assertEquals("era-app-reset", cfg.jwt.resetAudience)
        assertEquals(10L, cfg.jwt.resetTtlMinutes)
        assertEquals("PASSWORD_RESET", cfg.jwt.resetPurpose)
        assertTrue(cfg.jwt.secret.isNotBlank())
        assertTrue(cfg.mail.host.isNotBlank())
        // Módulo I: AVATAR_STORAGE_DIR debe existir y no estar vacía (Fail-Fast en el loader).
        assertTrue(cfg.storage.avatarDir.isNotBlank())
        // V11: devMode refleja APP_DEV_MODE del entorno (default false), nunca deriva de JWT_SECRET.
        assertEquals(System.getenv("APP_DEV_MODE") == "true", cfg.devMode)
    }
}
