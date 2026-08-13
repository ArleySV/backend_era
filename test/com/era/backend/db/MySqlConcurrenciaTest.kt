package com.era.backend.db

import at.favre.lib.crypto.bcrypt.BCrypt
import com.era.backend.config.JwtConfig
import com.era.backend.exceptions.EmailAlreadyRegisteredException
import com.era.backend.exceptions.InvalidCredentialsException
import com.era.backend.models.dto.LoginRequestDto
import com.era.backend.models.dto.RegisterRequestDto
import com.era.backend.models.entities.AcudienteRow
import com.era.backend.repositories.AcudienteRepository
import com.era.backend.repositories.ExposedConfiguracionRepository
import com.era.backend.repositories.ExposedRegistroPendienteRepository
import com.era.backend.repositories.ExposedTransactionRunner
import com.era.backend.repositories.ExposedUsuarioRepository
import com.era.backend.services.FakeOtpNotifier
import com.era.backend.services.JwtTokenService
import com.era.backend.services.LoginService
import com.era.backend.services.OtpService
import com.era.backend.services.RegistrationService
import com.era.backend.services.VerificationService
import java.sql.SQLException
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests de integración con **concurrencia real** contra MySQL (Fase 2, diseño aprobado en
 * `docs/fase2-tests-mysql-diseno.md`): hilos reales + `CyclicBarrier` para verificar que
 * tres garantías del backend se cumplen bajo ejecución paralela:
 * 1. **Rollback atómico de `verify-email`**: un fallo a mitad de la conversión (usuario →
 *    acudiente → configuracion) no deja ninguna fila residual.
 * 2. **Unicidad anti-TOCTOU del registro**: dos `register` concurrentes con el mismo correo
 *    dejan exactamente un `registro_pendiente` (la constraint UNIQUE decide).
 * 3. **`SELECT ... FOR UPDATE` del login**: dos fallos simultáneos del mismo usuario quedan
 *    contabilizados en serie (`intentos_login_fallidos = 2`), sin lost-update.
 *
 * Reglas de seguridad (checklist aprobada por el propietario):
 * - Base de pruebas **siempre distinta de `era_db`** (guard en [MySqlTestPool]).
 * - Cada test hace su **propio TRUNCATE** en `@AfterTest` (independencia total entre tests).
 * - Cada tarea captura `Throwable` → `Result.failure`: un deadlock 1213 ocasional de InnoDB
 *   (que revierte al perdedor sin filas residuales) NUNCA hace fallar el harness; las
 *   invariantes solo exigen "1 éxito + 1 fila".
 * - Sin datos reales: valores sintéticos y nunca se loguean correos, cédulas ni hashes.
 */
class MySqlConcurrenciaTest {

    @BeforeTest
    fun verificarBaseAntesDeCadaTest() {
        MySqlTestPool.connectExposed()
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

    // ── Test 1: rollback atómico de verify-email ────────────────────────────────────

    @Test
    fun `verify-email revierte por completo si falla el insert del acudiente`() {
        val notifier = FakeOtpNotifier()
        val otp = OtpService(notifier, otpDeterminista = true)
        val registro = ExposedRegistroPendienteRepository()
        val usuario = ExposedUsuarioRepository()

        // Alta del pendiente con el servicio real (OTP determinista "123456", coste 12).
        RegistrationService(registro, usuario, otp, ExposedTransactionRunner)
            .register(requestRegistro(CORREO_CONCURRENCIA, "verifrace"))

        // La verificación usa acudiente que falla a mitad de la conversión: el usuario se
        // inserta (éxito) y el stub lanza SQLException al insertar el acudiente → rollback.
        val acudienteQueFalla =
            object : AcudienteRepository {
                override fun insert(row: AcudienteRow): Long =
                    throw SQLException("fallo inducido en acudiente")
            }
        val verify =
            VerificationService(
                registroRepository = registro,
                usuarioRepository = usuario,
                acudienteRepository = acudienteQueFalla,
                configuracionRepository = ExposedConfiguracionRepository(),
                otpService = otp,
                transactionRunner = ExposedTransactionRunner,
            )

        assertFailsWith<SQLException> { verify.verificarEmail(CORREO_CONCURRENCIA, "123456") }

        // Post-condiciones: la transacción revirtió TODO y el pendiente quedó intacto.
        assertEquals(0, MySqlTestPool.conBase { MySqlTestPool.consultaEntero(it, "SELECT COUNT(*) FROM usuario") })
        assertEquals(0, MySqlTestPool.conBase { MySqlTestPool.consultaEntero(it, "SELECT COUNT(*) FROM acudiente") })
        assertEquals(0, MySqlTestPool.conBase { MySqlTestPool.consultaEntero(it, "SELECT COUNT(*) FROM configuracion") })
        assertEquals(
            1,
            MySqlTestPool.conBase {
                MySqlTestPool.consultaEntero(it, "SELECT COUNT(*) FROM registro_pendiente WHERE correo = '$CORREO_CONCURRENCIA'")
            },
        )
    }

    // ── Test 2: unicidad anti-TOCTOU del registro ────────────────────────────────────

    @Test
    fun `dos registros concurrentes del mismo correo dejan exactamente un pendiente`() {
        val otp = OtpService(FakeOtpNotifier(), otpDeterminista = true)
        val service =
            RegistrationService(
                ExposedRegistroPendienteRepository(),
                ExposedUsuarioRepository(),
                otp,
                ExposedTransactionRunner,
            )

        // Mismo correo, usernames DISTINTOS: aísla la colisión exclusivamente de correo.
        val dtoA = requestRegistro(CORREO_CONCURRENCIA, "race_user_a")
        val dtoB = requestRegistro(CORREO_CONCURRENCIA, "race_user_b")

        val resultados = ejecutarConcurrentemente(2) { i -> service.register(if (i == 0) dtoA else dtoB) }

        // Invariante determinista: exactamente 1 éxito y 1 perdedor esperado. El perdedor
        // puede ser el check de unicidad (EmailAlreadyRegisteredException) o la constraint
        // UNIQUE / deadlock 1213 en el INSERT (SQLException) — ambos cuentan como "perdedor".
        assertEquals(1, resultados.count { it.isSuccess }, "la unicidad debe dejar exactamente un ganador")
        val perdedor = resultados.first { it.isFailure }.exceptionOrNull()
        assertTrue(
            perdedor is EmailAlreadyRegisteredException || perdedor is SQLException,
            "el perdedor debe ser el check (EmailAlreadyRegisteredException) o la constraint/deadlock " +
                "(SQLException), fue: ${perdedor?.javaClass?.name}",
        )
        assertEquals(
            1,
            MySqlTestPool.conBase {
                MySqlTestPool.consultaEntero(it, "SELECT COUNT(*) FROM registro_pendiente WHERE correo = '$CORREO_CONCURRENCIA'")
            },
            "si la constraint UNIQUE no existiera quedarían 2 filas",
        )
        assertEquals(0, MySqlTestPool.conBase { MySqlTestPool.consultaEntero(it, "SELECT COUNT(*) FROM usuario") })
    }

    // ── Test 3: FOR UPDATE del login bajo concurrencia ──────────────────────────────

    @Test
    fun `dos logins fallidos concurrentes quedan en serie por FOR UPDATE`() {
        // Usuario activo con hash bcrypt real (coste bajo, solo test); ambos logins usarán
        // una contraseña distinta, así que ninguno emite token.
        val hash = BCrypt.withDefaults().hashToString(4, "ContrasenaNuncaUsada1!".toCharArray())
        MySqlTestPool.conBase { con ->
            MySqlTestPool.insertarUsuario(con, "login.race@example.com", "loginrace", contrasenaHash = hash)
        }

        val login =
            LoginService(
                usuarioRepository = ExposedUsuarioRepository(),
                transactionRunner = ExposedTransactionRunner,
                jwtTokenService = JwtTokenService(JWT_CONFIG_TEST),
            )
        val dto = LoginRequestDto(usuarioOCorreo = "loginrace", contrasena = "ContrasenaIncorrecta1!")

        val resultados = ejecutarConcurrentemente(2) { login.login(dto) }

        // Ambos deben fallar con el mismo 401 genérico (nunca emitir token).
        resultados.forEach { resultado ->
            val ex = assertFailsWith<InvalidCredentialsException> { resultado.getOrThrow() }
            assertEquals("Credenciales incorrectas.", ex.message)
        }

        // FOR UPDATE serializó los dos incrementos: contador en 2 y sin ventana (2 < 5).
        MySqlTestPool.conBase { con ->
            con.createStatement().use { st ->
                st.executeQuery(
                    "SELECT intentos_login_fallidos, bloqueado_hasta FROM usuario WHERE nombre_usuario = 'loginrace'",
                ).use { rs ->
                    rs.next()
                    assertEquals(2, rs.getInt(1), "sin FOR UPDATE ambos podrían escribir 1 (lost-update)")
                    assertTrue(rs.getString(2) == null, "bloqueado_hasta debe quedar NULL (2 < 5, sin bloqueo prematuro)")
                }
            }
        }
    }

    // ── Mecanismo de concurrencia ───────────────────────────────────────────────────

    /**
     * Lanza [n] tareas en hilos reales, liberadas a la vez por una [CyclicBarrier]. Cada
     * tarea devuelve `Result<T>`: cualquier `Throwable` (incluido un deadlock 1213) se
     * captura como fallo esperado, de modo que el harness nunca se cuelga ni revienta por
     * una excepción no capturada. `shutdownNow()` garantiza que no se fugen hilos.
     */
    private fun <T> ejecutarConcurrentemente(n: Int, bloque: (Int) -> T): List<Result<T>> {
        val executor = Executors.newFixedThreadPool(n)
        val barrier = CyclicBarrier(n)
        return try {
            (0 until n)
                .map { i ->
                    executor.submit(
                        Callable {
                            try {
                                barrier.await(10, TimeUnit.SECONDS) // todos liberados a la vez
                                Result.success(bloque(i))
                            } catch (t: Throwable) {
                                Result.failure(t)
                            }
                        },
                    )
                }
                .map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun requestRegistro(correo: String, nombreUsuario: String) =
        RegisterRequestDto(
            nombreMenor = "Menor Concurrencia",
            fechaNacimiento = "2016-05-10",
            nombreAcudiente = "Acudiente Concurrencia",
            cedulaAcudiente = "1023456789",
            correo = correo,
            nombreUsuario = nombreUsuario,
            avatar = null,
            contrasena = "Trivia#2025",
            confirmarContrasena = "Trivia#2025",
        )

    companion object {
        /** Correo sintético único de la suite; nunca es un dato real (CLAUDE.md §6). */
        private const val CORREO_CONCURRENCIA = "concurrencia.race@example.com"

        /** Config JWT de test: solo se firmaría si un login exitoso emitiera token (no ocurre aquí). */
        val JWT_CONFIG_TEST =
            JwtConfig(
                secret = "test-secret",
                sessionIssuer = "era-backend",
                sessionAudience = "era-app-session",
                sessionExpirationMinutes = 43200,
                resetIssuer = "era-backend",
                resetAudience = "era-app-reset",
                resetTtlMinutes = 10,
                resetPurpose = "PASSWORD_RESET",
            )
    }
}
