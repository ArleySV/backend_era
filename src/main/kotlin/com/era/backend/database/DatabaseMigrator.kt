package com.era.backend.database

import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory

object DatabaseMigrator {
    private val log = LoggerFactory.getLogger(DatabaseMigrator::class.java)

    /**
     * Aplica las migraciones Flyway de `resources/db/migration/` **reutilizando el
     * mismo pool HikariCP** que usa Exposed (el creado por `DatabaseFactory`); no
     * abre una conexión propia ni duplica la fuente de datos.
     *
     * `baselineOnMigrate(true)`: si el esquema ya existe con tablas creadas a mano
     * (el diccionario de datos oficial define las tablas), Flyway lo toma como
     * baseline en lugar de fallar. Nunca borra datos físicamente.
     */
    fun migrate(dataSource: DataSource) {
        val result =
            Flyway
                .configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load()
                .migrate()
        log.info(
            "Flyway: {} migración(es) ejecutada(s), versión del esquema: {}",
            result.migrationsExecuted,
            result.targetSchemaVersion,
        )
    }
}
