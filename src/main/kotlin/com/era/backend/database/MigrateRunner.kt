package com.era.backend.database

import com.era.backend.config.toAppConfig
import com.era.backend.plugins.DatabaseFactory
import io.ktor.server.netty.EngineMain

/**
 * Punto de entrada standalone para ejecutar las migraciones sin levantar el servidor.
 * Reutiliza la misma carga de configuración de EngineMain (application.yaml + ${VAR})
 * y el mismo pool HikariCP de `DatabaseFactory` que usará la app en producción.
 *
 * Uso: .\kotlin run --main-class=com.era.backend.database.MigrateRunnerKt
 */
fun main(args: Array<String>) {
    val server = EngineMain.createServer(args)
    try {
        val database = server.environment.config.toAppConfig().database
        val dataSource = DatabaseFactory.createDataSource(database)
        try {
            DatabaseMigrator.migrate(dataSource)
        } finally {
            DatabaseFactory.close(dataSource)
        }
    } finally {
        server.stop(0, 0)
    }
}
