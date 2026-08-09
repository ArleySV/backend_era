package com.era.backend.services

import com.era.backend.config.MailConfig
import org.simplejavamail.api.mailer.config.TransportStrategy
import org.simplejavamail.email.EmailBuilder
import org.simplejavamail.mailer.MailerBuilder

/**
 * Implementación real de [OtpNotifier] con simpleJavaMail (REQ-FUN-01 CA4, CU-11).
 *
 * La sesión SMTP se construye desde [MailConfig] (cargada de `${SMTP_*}` vía
 * `application.yaml`): host, puerto, usuario, contraseña y remitente. No hay literales
 * reales en el código: en desarrollo las variables llevan placeholders como
 * `<EMAIL_API_KEY>` (CLAUDE.md §5 regla 2).
 *
 * Seguridad (CLAUDE.md §6): ni el OTP ni el correo destinatario se registran en logs; el
 * código se genera con `SecureRandom` y solo se persiste su hash bcrypt (HU-15 CA3).
 *
 * **Modo No-Op (solo dev, V10.1/V11):** cuando [modoNoOp] es `true`, `send()` imprime el
 * código en consola y NO invoca SMTP. Lo activa `Application.module()` únicamente cuando
 * `APP_DEV_MODE=true` (`AppConfig.devMode`, default `false`), para que el smoke test E2E no
 * falle por falta de un servidor SMTP real. Es un downgrade deliberado de dev: producción
 * con APP_DEV_MODE=true expondría el OTP en consola (excepción acotada a CLAUDE.md §6,
 * autorizada por el propietario, 2026-08-08).
 *
 * ⚠ **VITAL para la Base de Trazabilidad de Calidad (V11):** la lógica de APP_DEV_MODE
 * sostiene el smoke test E2E (`scripts/smoke_test.ps1`). NO eliminar ni renombrar en
 * refactorizaciones futuras.
 */
class SimpleJavaMailOtpNotifier(
    private val mailConfig: MailConfig,
    private val modoNoOp: Boolean = false,
) : OtpNotifier {

    /**
     * Sesión SMTP única y reutilizable (thread-safe). Estrategia según puerto: 465 →
     * SMTPS (SSL implícito); 587/25 → SMTP_TLS (STARTTLS).
     */
    private val mailer =
        MailerBuilder
            .withSMTPServer(mailConfig.host, mailConfig.port, mailConfig.user, mailConfig.password)
            .withTransportStrategy(
                if (mailConfig.port == 465) TransportStrategy.SMTPS else TransportStrategy.SMTP_TLS,
            )
            .buildMailer()

    /**
     * Compone y envía el mensaje SMTP con el código [code] hacia [correo].
     * En modo No-Op (dev) imprime solo el código (sin el correo) y no usa SMTP.
     * No se loguea ni el código ni el destinatario fuera de ese modo.
     */
    override fun send(correo: String, code: String) {
        if (modoNoOp) {
            println("[ERA][DEV] Código OTP: $code")
            return
        }
        val email =
            EmailBuilder
                .startingBlank()
                .from("ERA", mailConfig.from)
                .to(correo)
                .withSubject("Código de verificación ERA")
                .withPlainText("Tu código de verificación es: $code")
                .buildEmail()
        mailer.sendMail(email)
    }
}
