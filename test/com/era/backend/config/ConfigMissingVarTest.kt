package com.era.backend.config

import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Contracto de fail-fast de la carga de configuración (Parte 1, fase final del plan).
 *
 * Mecanismo verificado empíricamente (2026-08-12): la resolución de `${VAR}` ocurre en
 * `YamlConfigLoader`, NO en `toAppConfig()` — un placeholder ausente que llegara a este
 * punto pasaría literal. Por eso este test cubre el contrato del que `toAppConfig()` SÍ es
 * responsable: **valores presentes pero vacíos** deben abortar la carga con mensaje claro
 * que nombre la variable. El comportamiento de las variables **ausentes** en el arranque
 * real lo cubre `scripts/config_smoke.ps1` (evidencia de boot).
 *
 * Las 12 variables críticas: DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD, JWT_SECRET,
 * SMTP_HOST, SMTP_PORT, SMTP_USER, SMTP_PASSWORD, SMTP_FROM y AVATAR_STORAGE_DIR.
 */
class ConfigMissingVarTest {

    /** Mapa espejo de `application.yaml` con valores dummy literales (sin placeholders). */
    private fun configBase(): MapApplicationConfig =
        MapApplicationConfig(
            "ktor.deployment.port" to "8080",
            "database.host" to "localhost",
            "database.port" to "3306",
            "database.name" to "era_db",
            "database.user" to "usuario",
            "database.password" to "password",
            "database.pool.maxSize" to "10",
            "database.pool.connectionTimeoutMs" to "30000",
            "jwt.secret" to "secreto-de-prueba-suficientemente-largo-para-hmac",
            "jwt.session.issuer" to "era-backend",
            "jwt.session.audience" to "era-app-session",
            "jwt.session.expirationMinutes" to "43200",
            "jwt.passwordReset.issuer" to "era-backend",
            "jwt.passwordReset.audience" to "era-app-reset",
            "jwt.passwordReset.ttlMinutes" to "10",
            "jwt.passwordReset.purpose" to "PASSWORD_RESET",
            "mail.host" to "smtp.example.com",
            "mail.port" to "587",
            "mail.user" to "api-key",
            "mail.password" to "api-key",
            "mail.from" to "era@example.com",
            "storage.avatarDir" to "C:/tmp/avatars",
        )

    @Test
    fun `mapa base sin placeholders carga sin error`() {
        val cfg = configBase().toAppConfig()
        assertEquals("era_db", cfg.database.name)
        assertEquals(10, cfg.database.pool.maxSize)
        assertEquals("era-app-session", cfg.jwt.sessionAudience)
        assertEquals("era@example.com", cfg.mail.from)
        assertEquals("C:/tmp/avatars", cfg.storage.avatarDir)
    }

    @Test
    fun `JWT_SECRET vacio se rechaza en la carga sin fallback ni derivacion`() {
        val config = configBase()
        config.put("jwt.secret", "")
        val error =
            assertFailsWith<IllegalArgumentException>("JWT_SECRET vacío debe abortar la carga") {
                config.toAppConfig()
            }
        assertTrue(error.message?.contains("JWT_SECRET") == true, "Mensaje: ${error.message}")
    }

    @Test
    fun `AVATAR_STORAGE_DIR vacio se rechaza en la carga`() {
        val config = configBase()
        config.put("storage.avatarDir", "")
        val error =
            assertFailsWith<IllegalArgumentException>("AVATAR_STORAGE_DIR vacío debe abortar la carga") {
                config.toAppConfig()
            }
        assertTrue(error.message?.contains("AVATAR_STORAGE_DIR") == true, "Mensaje: ${error.message}")
    }
}
