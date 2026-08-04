package com.era.backend.database

import com.era.backend.config.toAppConfig
import io.ktor.server.netty.EngineMain

/**
 * Punto de entrada standalone para ejecutar las migraciones sin levantar el servidor.
 * Reutiliza la misma carga de configuración de EngineMain (application.yaml + ${VAR}).
 *
 * Uso: .\kotlin run --main-class=com.era.backend.database.MigrateRunnerKt
 */
fun main(args: Array<String>) {
    val server = EngineMain.createServer(args)
    try {
        DatabaseMigrator.migrate(
            server.environment.config
                .toAppConfig()
                .database,
        )
    } finally {
        server.stop(0, 0)
    }
}
