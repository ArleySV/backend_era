-- ============================================================================
-- ERA — Migración V3: seguimiento del último envío del OTP de recuperación (P2)
-- Motor: MySQL 8.0 · Migrador: Flyway (resources/db/migration/V3__codigo_verificacion_ultimo_envio.sql)
-- Base: decisión del análisis del Módulo C (C-2/C-5, aprobado por el propietario);
-- ARQUITECTURA_BASE.md §5.4 #5 (P3) — soporta el throttle de reenvío (P2, mínimo 60 s)
-- del OTP de recuperación de contraseña (REQ-FUN-07): se reutiliza
-- OtpResendThrottledException (429 OTP_RESEND_THROTTLED), igual que en
-- `registro_pendiente` (V2).
--
-- Por qué una columna nueva: `creado_en` es auditoría de alta (no debe reescribirse
-- en un reenvío) y `expira_en` se resetea a `now + 10 min` en cada envío; ninguno
-- sirve de referencia del último envío real. Es el espejo de la V2 sobre la tabla
-- `codigo_verificacion` (V2 dejó escrita esta decisión: "la columna equivalente de
-- `codigo_verificacion` se decide al implementar el Módulo C").
-- ============================================================================

-- ----------------------------------------------------------------------------
-- codigo_verificacion
-- ----------------------------------------------------------------------------
ALTER TABLE codigo_verificacion
    ADD COLUMN ultimo_envio_en DATETIME NULL
    AFTER expira_en;
