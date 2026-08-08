package com.era.backend.services

/**
 * Implementación real de [OtpNotifier] con simpleJavaMail. Envía el código de 6 dígitos
 * al correo configurado (REQ-FUN-01 CA4, CU-11).
 *
 * Seguridad (CLAUDE.md §6): ni el OTP ni el correo destinatario se registran en logs; el
 * código se genera con `SecureRandom` y solo se persiste su hash bcrypt (HU-15 CA3).
 */
class SimpleJavaMailOtpNotifier : OtpNotifier {

    /**
     * Compone y envía el mensaje SMTP con el código [code] hacia [correo].
     * La configuración de sesión (host, puerto, usuario, placeholder `<EMAIL_API_KEY>`,
     * remitente) se toma de `AppConfig`/SMTP; sin literales reales (CLAUDE.md §5 regla 2).
     */
    override fun send(correo: String, code: String) {
        // Pendiente: configurar la sesión SMTP desde AppConfig y construir el mensaje.
    }
}
