package com.era.backend

import io.ktor.server.application.Application
import io.ktor.server.netty.EngineMain

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    // El enrutado de ERA (auth, OTP, recuperación, cuenta, sincronización)
    // se registrará en los próximos módulos.
}
