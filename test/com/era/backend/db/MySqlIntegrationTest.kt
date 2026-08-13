package com.era.backend.db

import com.era.backend.database.DatabaseMigrator
import java.sql.SQLException
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
 * El pool Hikari, el guard anti-base-equivocada (`SELECT DATABASE()`), el listado de tablas
 * para el TRUNCATE y los helpers JDBC viven en [MySqlTestPool], compartido con
 * [MySqlConcurrenciaTest] (diseño aprobado en `docs/fase2-tests-mysql-diseno.md` §2).
 *
 * Reglas de seguridad (checklist aprobada por el propietario):
 * - Base de pruebas **siempre distinta de `era_db`**: se lee `TEST_DB_NAME` (default
 *   `era_db_test`) y se **rechaza explícitamente** apuntar a `era_db`.
 * - Guard anti-base-equivocada: cada acceso a la BD confirma con `SELECT DATABASE()` que la
 *   conexión activa es la base de pruebas; el `@BeforeTest` lo ejecuta antes de cada test.
 * - Limpieza `TRUNCATE` de las 12 tablas en `@AfterTest` (con FK checks off), para que
 *   los tests sean independientes entre sí y repetibles.
 *
 * Nota sobre `MigrateRunner`: el test de idempotencia usa `DatabaseMigrator.migrate()`,
 * la misma función que invoca `MigrateRunner.main`, sobre el pool de la base de pruebas,
 * sin arrancar el servidor Netty.
 */
class MySqlIntegrationTest {

    @BeforeTest
    fun verificarBaseAntesDeCadaTest() {
        MySqlTestPool.conBase { }
    }

    @AfterTest
    fun limpiarTablas() {
        MySqlTestPool.conBase { con ->
            try {
                con.createStatement().use { st ->
                    st.execute("SET FOREIGN_KEY_CHECKS=0")
                    MySqlTestPool.TABLAS.forEach { tabla -> st.execute("TRUNCATE TABLE $tabla") }
                }
            } finally {
                con.createStatement().use { st -> st.execute("SET FOREIGN_KEY_CHECKS=1") }
            }
        }
    }

    // ── 1. Idempotencia de migraciones ──────────────────────────────────────────────

    @Test
    fun `MigrateRunner sobre base ya migrada es idempotente y no duplica historia`() {
        val antes = MySqlTestPool.conBase { MySqlTestPool.consultaEntero(it, "SELECT COUNT(*) FROM flyway_schema_history") }
        assertEquals(3, antes, "era_db_test debe estar migrada con V1+V2+V3 antes de este test")

        DatabaseMigrator.migrate(MySqlTestPool.POOL) // no debe lanzar: esquema al día

        val despues = MySqlTestPool.conBase { MySqlTestPool.consultaEntero(it, "SELECT COUNT(*) FROM flyway_schema_history") }
        assertEquals(3, despues, "una re-migración no debe duplicar filas en flyway_schema_history")
    }

    // ── 2. Constraint UNIQUE de correo (bypass del service) ─────────────────────────

    @Test
    fun `UNIQUE de correo rechaza el segundo insert directo a BD`() {
        MySqlTestPool.conBase { con ->
            MySqlTestPool.insertarUsuario(con, "primero@example.com", "primero")
        }
        val ex: SQLException =
            MySqlTestPool.conBase { con ->
                assertFailsWith<SQLException> {
                    MySqlTestPool.insertarUsuario(con, "primero@example.com", "segundo")
                }
            }
        assertTrue(ex.message.orEmpty().contains("Duplicate entry"), "MySQL debe rechazar el duplicado: ${ex.message}")
        assertTrue(ex.message.orEmpty().contains("uq_usuario_correo"), "debe apuntar al constraint UNIQUE: ${ex.message}")
        assertEquals(1, MySqlTestPool.conBase { MySqlTestPool.consultaEntero(it, "SELECT COUNT(*) FROM usuario") }, "solo debe quedar el primer usuario")
    }

    // ── 3. FK ON DELETE RESTRICT (soft delete como única vía) ───────────────────────

    @Test
    fun `ON DELETE RESTRICT impide borrar fisicamente un usuario con acudiente`() {
        val id =
            MySqlTestPool.conBase { con ->
                MySqlTestPool.insertarUsuario(con, "con.acudiente@example.com", "conacudiente")
            }
        MySqlTestPool.conBase { con ->
            con.createStatement().use { st ->
                st.executeUpdate(
                    "INSERT INTO acudiente (id_usuario, nombre_completo, numero_cedula) " +
                        "VALUES ($id, 'Acudiente Test', '1023456789')",
                )
            }
        }
        val ex =
            assertFailsWith<SQLException> {
                MySqlTestPool.conBase { con ->
                    con.createStatement().use { st -> st.executeUpdate("DELETE FROM usuario WHERE id_usuario = $id") }
                }
            }
        assertTrue(
            ex.message.orEmpty().contains("foreign key constraint fails"),
            "la FK RESTRICT debe bloquear el DELETE físico: ${ex.message}",
        )
        assertEquals(1, MySqlTestPool.conBase { MySqlTestPool.consultaEntero(it, "SELECT COUNT(*) FROM usuario") }, "el usuario debe seguir existiendo")
        assertEquals(1, MySqlTestPool.conBase { MySqlTestPool.consultaEntero(it, "SELECT COUNT(*) FROM acudiente") }, "el acudiente debe conservarse")
    }
}
