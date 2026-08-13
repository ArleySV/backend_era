package com.era.backend.db

import com.era.backend.config.DatabaseConfig
import com.era.backend.plugins.DatabaseFactory
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.Statement
import kotlin.test.assertEquals

/**
 * Fuente de datos única de los tests de integración contra MySQL real (Fase 2 del plan de
 * testing). Comparte el pool Hikari y el guard anti-base-equivocada entre
 * [MySqlIntegrationTest] y [MySqlConcurrenciaTest].
 *
 * Reglas de seguridad (checklist aprobada por el propietario):
 * - Base de pruebas **siempre distinta de `era_db`**: se lee `TEST_DB_NAME` (default
 *   `era_db_test`) y se **rechaza explícitamente** apuntar a `era_db`.
 * - Guard anti-base-equivocada ([verificarBase]): cada acceso a la BD confirma con
 *   `SELECT DATABASE()` que la conexión activa es la base de pruebas.
 */
object MySqlTestPool {

    /** Base de pruebas (default `era_db_test`). Se **rechaza** `era_db`. */
    val NOMBRE_PRUEBA: String =
        (System.getenv("TEST_DB_NAME")?.takeIf { it.isNotBlank() } ?: "era_db_test")
            .also {
                require(it != "era_db") {
                    "Los tests de integración nunca deben apuntar a la base de producción era_db."
                }
            }

    /** Pool HikariCP real (el mismo `DatabaseFactory` que usa la app) sobre la base de pruebas. */
    val POOL: HikariDataSource by lazy {
        val host = System.getenv("DB_HOST") ?: "localhost"
        val port = System.getenv("DB_PORT")?.toIntOrNull() ?: 3306
        val user = System.getenv("DB_USER").orEmpty()
        val password = System.getenv("DB_PASSWORD").orEmpty()
        require(user.isNotBlank() && password.isNotBlank()) {
            "Faltan DB_USER/DB_PASSWORD: define las variables de entorno de MySQL (ver .env / .env.example)."
        }
        DatabaseFactory.createDataSource(DatabaseConfig(host, port, NOMBRE_PRUEBA, user, password))
    }

    private val exposedConectado: Unit by lazy { DatabaseFactory.connectExposed(POOL) }

    /**
     * Registra el pool como la base por defecto de Exposed (una sola vez por JVM). Lo
     * invoca [MySqlConcurrenciaTest] antes de usar los repositorios Exposed reales; el
     * `by lazy` impide reconectar si Exposed ya está inicializado.
     */
    fun connectExposed() {
        exposedConectado
    }

    /** Las 12 tablas de la aplicación (V1); Flyway (`flyway_schema_history`) nunca se trunca. */
    val TABLAS =
        listOf(
            "intento", "progreso_usuario", "opcion_respuesta", "pregunta", "nivel",
            "comentario", "configuracion", "tokens_reseteo", "codigo_verificacion",
            "registro_pendiente", "acudiente", "usuario",
        )

    /** Abre una conexión del pool y la cierra al terminar, validando siempre la base activa. */
    fun <T> conBase(block: (Connection) -> T): T =
        POOL.connection.use { con ->
            verificarBase(con)
            block(con)
        }

    /**
     * Guard anti-base-equivocada: `SELECT DATABASE()` debe devolver [NOMBRE_PRUEBA]. Si una
     * conexión apuntara a otra base, el test falla aquí.
     */
    fun verificarBase(con: Connection) {
        val actual =
            con.createStatement().use { st ->
                st.executeQuery("SELECT DATABASE()").use { rs ->
                    rs.next()
                    rs.getString(1) ?: "sin base seleccionada"
                }
            }
        assertEquals(NOMBRE_PRUEBA, actual, "La conexión activa debe apuntar a $NOMBRE_PRUEBA, nunca a era_db")
    }

    /** Ejecuta una consulta que devuelve un único entero (COUNT, columna numérica). */
    fun consultaEntero(con: Connection, sql: String): Int {
        con.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                rs.next()
                return rs.getInt(1)
            }
        }
    }

    /**
     * Inserta un usuario de prueba por JDBC (bypass del service) y devuelve su `id_usuario`.
     * [estado] es el literal del soft delete (`activo` por defecto).
     */
    fun insertarUsuario(
        con: Connection,
        correo: String,
        nombreUsuario: String,
        contrasenaHash: String = "hash-bcrypt-de-prueba",
        estado: String = "activo",
    ): Long {
        con.createStatement().use { st ->
            st.executeUpdate(
                "INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado) " +
                    "VALUES ('Menor Test', '2016-05-10', '$correo', '$nombreUsuario', '$contrasenaHash', '$estado')",
                Statement.RETURN_GENERATED_KEYS,
            )
            st.generatedKeys.use { rs ->
                rs.next()
                return rs.getLong(1)
            }
        }
    }
}
