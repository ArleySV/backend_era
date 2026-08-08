package com.era.backend.services

/**
 * Abstracción del envío del código OTP por correo (`modulo-a-analisis.md` §5).
 *
 * Implementaciones:
 * - [SimpleJavaMailOtpNotifier]: envío SMTP real (dependencia simpleJavaMail ya declarada).
 * - fake en tests: captura el código sin SMTP para verificar el flujo y las políticas (P1).
 *
 * Nunca se loguea el código OTP ni el correo destinatario (CLAUDE.md §6).
 */
interface OtpNotifier {

    /**
     * Envía el código OTP de 6 dígitos [code] al [correo] del acudiente
     * (CU-11, REQ-FUN-01 CA4).
     *
     * Seguridad (CLAUDE.md §6): no registrar en logs ni el código ni el destinatario; el
     * código nunca viaja ni se persiste en texto plano (solo su hash, ver [OtpService.hash]).
     */
    fun send(correo: String, code: String)
}
