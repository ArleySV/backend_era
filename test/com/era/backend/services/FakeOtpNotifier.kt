package com.era.backend.services

/**
 * Fake de [OtpNotifier] para unit tests: captura el (correo, código) enviado sin tocar
 * SMTP, para poder verificar el flujo feliz del registro y las políticas P1 sin una
 * infraestructura de correo. Como en producción, no loguea nada.
 */
class FakeOtpNotifier : OtpNotifier {

    val envios = mutableListOf<Pair<String, String>>()

    override fun send(correo: String, code: String) {
        envios += correo to code
    }

    fun ultimoCodigo(): String? = envios.lastOrNull()?.second
}
