package com.era.backend

import com.era.backend.config.loadAppConfig
import com.era.backend.database.DatabaseMigrator
import com.era.backend.plugins.DatabaseFactory
import com.era.backend.plugins.configurePlugins
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.netty.EngineMain

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    configurePlugins()

    val dataSource = DatabaseFactory.createDataSource(loadAppConfig().database)

    monitor.subscribe(ApplicationStopped) {
        DatabaseFactory.close(dataSource)
    }

    // Un mismo pool HikariCP para Flyway y para Exposed (no hay segunda fuente).
    DatabaseMigrator.migrate(dataSource)
    DatabaseFactory.connectExposed(dataSource)

    // El enrutado de ERA (auth, OTP, recuperación, cuenta, sincronización)
    // se registrará en los próximos módulos.
}
