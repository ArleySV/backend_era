package com.era.backend.database

import com.era.backend.config.DatabaseConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * Única fuente de conexión a MySQL. Construye el pool HikariCP a partir de
 * `DatabaseConfig` (host/port/name/user/password cargados desde `${DB_*}`
 * vía application.yaml) y lo entrega tanto a Flyway (`DatabaseMigrator`) como a
 * Exposed, de modo que nunca existan dos pools ni configuraciones divergentes.
 */
object DatabaseFactory {
    fun createDataSource(config: DatabaseConfig): HikariDataSource {
        val hikari =
            HikariConfig().apply {
                jdbcUrl = config.jdbcUrl
                driverClassName = "com.mysql.cj.jdbc.Driver"
                username = config.user
                password = config.password
                poolName = "era-hikari"
                maximumPoolSize = config.pool.maxSize
                connectionTimeout = config.pool.connectionTimeoutMs
            }
        return HikariDataSource(hikari)
    }

    fun connectExposed(dataSource: HikariDataSource) {
        Database.connect(dataSource)
    }

    fun close(dataSource: HikariDataSource) {
        dataSource.close()
    }
}
