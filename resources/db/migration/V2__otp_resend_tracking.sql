-- ============================================================================
-- ERA — Migración V2: seguimiento del último envío de OTP (P3)
-- Motor: MySQL 8.0 · Migrador: Flyway (resources/db/migration/V2__otp_resend_tracking.sql)
-- Base: decisión ARQUITECTURA_BASE.md §5.4 #5 (P3) — soporta el throttle de reenvío
-- de OTP (P2): mínimo 60 s entre envíos del mismo código (OtpResendThrottledException,
-- 429 OTP_RESEND_THROTTLED). Aplica solo a `registro_pendiente`; la columna
-- equivalente de `codigo_verificacion` se decide al implementar el Módulo C.
--
-- Por qué una columna nueva: `creado_en` es auditoría de alta (no debe reescribirse
-- en un reenvío) y `expira_en` se resetea a `now + 10 min` en cada envío; ninguno
-- sirve de referencia del último envío real.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- registro_pendiente
-- ----------------------------------------------------------------------------
ALTER TABLE registro_pendiente
    ADD COLUMN ultimo_envio_en DATETIME NULL
    AFTER expira_en;
