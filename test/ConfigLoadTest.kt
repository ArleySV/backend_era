package com.example

import com.era.backend.config.loadAppConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigLoadTest {
    @Test
    fun `yaml env substitution resolves via loadAppConfig`() =
        testApplication {
            configure()
            val cfg = application.loadAppConfig()
            assertEquals("era_db", cfg.database.name)
            assertTrue(cfg.database.jdbcUrl.startsWith("jdbc:mysql://"))
            assertEquals(43200L, cfg.jwt.sessionExpirationMinutes)
            assertEquals("era-app-session", cfg.jwt.sessionAudience)
            assertEquals("era-app-reset", cfg.jwt.resetAudience)
            assertEquals(10L, cfg.jwt.resetTtlMinutes)
            assertEquals("PASSWORD_RESET", cfg.jwt.resetPurpose)
            assertTrue(cfg.jwt.secret.isNotBlank())
            assertTrue(cfg.mail.host.isNotBlank())
        }
}
