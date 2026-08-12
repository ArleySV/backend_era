package com.era.backend.db

import com.era.backend.config.DatabaseConfig
import com.era.backend.database.DatabaseMigrator
import com.era.backend.plugins.DatabaseFactory
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pruebas de integración contra **MySQL real** (Fase 2 del plan de testing). Sin fakes ni
 * mocks: validan el comportamiento real de la base de datos (Flyway, constraints UNIQUE y
 * FK `ON DELETE RESTRICT`) tal como la usan los Módulos A/E (CLAUDE.md §4, §7).
 *
 * Reglas de seguridad (checklist aprobada por el propietario):
 * - Base de pruebas **siempre distinta de `era_db`**: se lee `TEST_DB_NAME` (default
 *   `era_db_test`) y se **rechaza explícitamente** apuntar a `era_db`.
 * - Guard anti-base-equivocada ([verificarBase]): cada acceso a la BD confirma con
 *   `SELECT DATABASE()` que la conexión activa es la base de pruebas; el `@BeforeTest`
 *   lo ejecuta antes de cada test (requisito 5 de la checklist).
 * - Limpieza `TRUNCATE` de las 12 tablas en `@AfterTest` (con FK checks off), para que
 *   los tests sean independientes entre sí y repetibles (requisito 6).
 *
 * Nota sobre `MigrateRunner`: el test de idempotencia usa `DatabaseMigrator.migrate()`,
 * la misma función que invoca `MigrateRunner.main`, sobre el pool de la base de pruebas,
 * sin arrancar el servidor Netty.
 */
class MySqlIntegrationTest {

    @BeforeTest
    fun verificarBaseAntesDeCadaTest() {
        conBase { }
    }

    @AfterTest
    fun limpiarTablas() {
        conBase { con ->
            try {
                con.createStatement().use { st ->
                    st.execute("SET FOREIGN_KEY_CHECKS=0")
                    TABLAS.forEach { tabla -> st.execute("TRUNCATE TABLE $tabla") }
                }
            } finally {
                con.createStatement().use { st -> st.execute("SET FOREIGN_KEY_CHECKS=1") }
            }
        }
    }

    // ── 1. Idempotencia de migraciones ──────────────────────────────────────────────

    @Test
    fun `MigrateRunner sobre base ya migrada es idempotente y no duplica historia`() {
        val antes = conBase { consultaEntero(it, "SELECT COUNT(*) FROM flyway_schema_history") }
        assertEquals(3, antes, "era_db_test debe estar migrada con V1+V2+V3 antes de este test")

        DatabaseMigrator.migrate(POOL) // no debe lanzar: esquema al día

        val despues = conBase { consultaEntero(it, "SELECT COUNT(*) FROM flyway_schema_history") }
        assertEquals(3, despues, "una re-migración no debe duplicar filas en flyway_schema_history")
    }

    // ── 2. Constraint UNIQUE de correo (bypass del service) ─────────────────────────

    @Test
    fun `UNIQUE de correo rechaza el segundo insert directo a BD`() {
        conBase { con ->
            insertarUsuario(con, "primero@example.com", "primero")
        }
        val ex: SQLException =
            conBase { con ->
                assertFailsWith<SQLException> {
                    insertarUsuario(con, "primero@example.com", "segundo")
                }
            }
        assertTrue(ex.message.orEmpty().contains("Duplicate entry"), "MySQL debe rechazar el duplicado: ${ex.message}")
        assertTrue(ex.message.orEmpty().contains("uq_usuario_correo"), "debe apuntar al constraint UNIQUE: ${ex.message}")
        assertEquals(1, conBase { consultaEntero(it, "SELECT COUNT(*) FROM usuario") }, "solo debe quedar el primer usuario")
    }

    // ── 3. FK ON DELETE RESTRICT (soft delete como única vía) ───────────────────────

    @Test
    fun `ON DELETE RESTRICT impide borrar fisicamente un usuario con acudiente`() {
        val id =
            conBase { con ->
                insertarUsuario(con, "con.acudiente@example.com", "conacudiente")
            }
        conBase { con ->
            con.createStatement().use { st ->
                st.executeUpdate(
                    "INSERT INTO acudiente (id_usuario, nombre_completo, numero_cedula) " +
                        "VALUES ($id, 'Acudiente Test', '1023456789')",
                )
            }
        }
        val ex =
            assertFailsWith<SQLException> {
                conBase { con ->
                    con.createStatement().use { st -> st.executeUpdate("DELETE FROM usuario WHERE id_usuario = $id") }
                }
            }
        assertTrue(
            ex.message.orEmpty().contains("foreign key constraint fails"),
            "la FK RESTRICT debe bloquear el DELETE físico: ${ex.message}",
        )
        assertEquals(1, conBase { consultaEntero(it, "SELECT COUNT(*) FROM usuario") }, "el usuario debe seguir existiendo")
        assertEquals(1, conBase { consultaEntero(it, "SELECT COUNT(*) FROM acudiente") }, "el acudiente debe conservarse")
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────

    /** Abre una conexión del pool y la cierra al terminar, validando siempre la base activa. */
    private fun <T> conBase(block: (Connection) -> T): T =
        POOL.connection.use { con ->
            verificarBase(con)
            block(con)
        }

    /**
     * Guard anti-base-equivocada (requisito 5): `SELECT DATABASE()` debe devolver
     * [NOMBRE_PRUEBA]. Si una conexión apuntara a otra base, el test falla aquí.
     */
    private fun verificarBase(con: Connection) {
        val actual = con.createStatement().use { st ->
            st.executeQuery("SELECT DATABASE()").use { rs ->
                rs.next()
                rs.getString(1) ?: "sin base seleccionada"
            }
        }
        assertEquals(NOMBRE_PRUEBA, actual, "La conexión activa debe apuntar a $NOMBRE_PRUEBA, nunca a era_db")
    }

    private fun insertarUsuario(con: Connection, correo: String, nombreUsuario: String): Long {
        con.createStatement().use { st ->
            st.executeUpdate(
                "INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash) " +
                    "VALUES ('Menor Test', '2016-05-10', '$correo', '$nombreUsuario', 'hash-bcrypt-de-prueba')",
                Statement.RETURN_GENERATED_KEYS,
            )
            st.generatedKeys.use { rs ->
                rs.next()
                return rs.getLong(1)
            }
        }
    }

    private fun consultaEntero(con: Connection, sql: String): Int {
        con.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                rs.next()
                return rs.getInt(1)
            }
        }
    }

    companion object {
        /**
         * Base de pruebas (default `era_db_test`). Se **rechaza** `era_db`: los tests de
         * integración jamás deben ejecutarse contra la base de producción/desarrollo.
         */
        private val NOMBRE_PRUEBA: String =
            (System.getenv("TEST_DB_NAME")?.takeIf { it.isNotBlank() } ?: "era_db_test")
                .also {
                    require(it != "era_db") {
                        "Los tests de integración nunca deben apuntar a la base de producción era_db."
                    }
                }

        /** Pool HikariCP real (el mismo `DatabaseFactory` que usa la app) sobre la base de pruebas. */
        private val POOL: HikariDataSource by lazy {
            val host = System.getenv("DB_HOST") ?: "localhost"
            val port = System.getenv("DB_PORT")?.toIntOrNull() ?: 3306
            val user = System.getenv("DB_USER").orEmpty()
            val password = System.getenv("DB_PASSWORD").orEmpty()
            require(user.isNotBlank() && password.isNotBlank()) {
                "Faltan DB_USER/DB_PASSWORD: define las variables de entorno de MySQL (ver .env / .env.example)."
            }
            DatabaseFactory.createDataSource(DatabaseConfig(host, port, NOMBRE_PRUEBA, user, password))
        }

        /** Las 12 tablas de la aplicación (V1); Flyway (`flyway_schema_history`) nunca se trunca. */
        private val TABLAS =
            listOf(
                "intento", "progreso_usuario", "opcion_respuesta", "pregunta", "nivel",
                "comentario", "configuracion", "tokens_reseteo", "codigo_verificacion",
                "registro_pendiente", "acudiente", "usuario",
            )
    }
}
