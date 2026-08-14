package com.era.backend.load

import at.favre.lib.crypto.bcrypt.BCrypt
import com.era.backend.config.JwtConfig
import com.era.backend.db.MySqlTestPool
import com.era.backend.module
import com.era.backend.services.JwtTokenService
import io.ktor.server.application.Application
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.test.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Sonda de carga de la fase final (Parte 2). Mide el backend REAL — Ktor + Netty + Hikari +
 * Exposed + MySQL, con el wiring completo de `Application.module()` — bajo carga creciente
 * en los escenarios de mayor riesgo de cola:
 * - `POST /api/v1/auth/login` con contraseña incorrecta contra un usuario **EXISTENTE**:
 *   bcrypt real contra el hash almacenado por request (la variante que llega a bloquear B-2/B-3).
 * - `POST /api/v1/auth/login` con identificador **INEXISTENTE**: bcrypt real contra el
 *   `HASH_DUMMY` de B-4 (`LoginService`); nunca dispara el bloqueo y es la que un atacante
 *   persistente preferiría. Debe quedar **pareja** con la variante existente (mismo coste).
 * - `POST /api/v1/auth/login` con cuenta bloqueada: fast-path B-2 (`modulo-b-analisis.md`
 *   §5), sin bcrypt.
 * - `GET /api/v1/progress/sync` autenticado: lecturas Exposed sobre el pool.
 *
 * Gate: solo se ejecuta cuando la env var `ERA_LOAD_PROBE=true` (la lanza
 * `scripts/load_probe.ps1` contra `era_db_test`). En el runner normal queda reportada como
 * **skipped** y no toca la BD ni levanta servidor. Vivir en el paquete `load` (y no en
 * `db`) la mantiene fuera del `--include-classes=com.era.backend.db.*` de
 * `integration_test.ps1`.
 *
 * Criterio (REQ-NF-01): p95 < 3000 ms en `progress-sync` y en el fast-path de bloqueo. Las
 * variantes `login-fallido` y `login-fallido-inexistente` (bcrypt) son **informativas**:
 * miden la serialización del hash sobre el event loop y su **paridad mutua** (B-4); si
 * exceden el techo se reportan como hallazgo sin hacer fallar la sonda.
 *
 * La evidencia se imprime con prefijo `[LOADPROBE]` para que el script la extraiga del
 * runner y la guarde en `test-results/`.
 */
class LoadProbeTest {

    private companion object {
        /** Secreto del probe, solo para esta sonda; nunca un valor real. */
        const val SECRETO_PRUEBA = "probe-secret-para-la-prueba-de-carga-era-2026"

        /**
         * Usuarios del escenario de ataque: post-fix (2026-08-13) se siembran con un hash
         * bcrypt **coste 11** (el que generan los registros nuevos), generado en `prepararDatos`
         * como el del usuario legítimo. Verifica la rama real del login contra un hash nuevo.
         */
        /** Usuario real del escenario mixto: se siembra con un hash bcrypt coste 11 real. */
        const val USUARIO_LEGIT = "probelogin-legit"

        /** Contraseña correcta del usuario legítimo (solo del probe; nunca un valor real). */
        const val CLAVE_LEGIT = "ClaveLegit!2026"

        /** Contraseña incorrecta usada por todos los requests de ataque (401 genérico). */
        const val CLAVE_INCORRECTA = "clave-incorrecta-probe"

        /** Config JWT idéntica a `application.yaml` pero con [SECRETO_PRUEBA]. */
        val JWT_PROBE =
            JwtConfig(
                secret = SECRETO_PRUEBA,
                sessionIssuer = "era-backend",
                sessionAudience = "era-app-session",
                sessionExpirationMinutes = 43_200,
                resetIssuer = "era-backend",
                resetAudience = "era-app-reset",
                resetTtlMinutes = 10,
                resetPurpose = "PASSWORD_RESET",
            )

        /** Rondas por concurrencia: ~50 request por escenario, sin alargar el C=1. */
        fun rondasPara(concurrencia: Int): Int =
            when {
                concurrencia <= 1 -> 5
                concurrencia <= 5 -> 10
                else -> 5
            }
    }

    private val cursorUsuario = AtomicInteger(0)
    private val cursorInexistente = AtomicInteger(0)

    /** Cliente HTTP compartido por todos los escenarios de la sonda. */
    private val cliente =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()

    private data class ResultadoEscenario(
        val nombre: String,
        val concurrencia: Int,
        val rondas: Int,
        val totalRequests: Int,
        val p50Ms: Double,
        val p95Ms: Double,
        val p99Ms: Double,
        val rps: Double,
        val respuestasPorStatus: Map<Int, Int>,
    )

    @Test
    fun `sonda de carga contra el backend real`() {
        assumeTrue(
            System.getenv("ERA_LOAD_PROBE") == "true",
            "Sonda de carga desactivada: ERA_LOAD_PROBE != true (se lanza vía scripts/load_probe.ps1)",
        )

        val avatarDir = Files.createTempDirectory("era-avatar-probe").toString()
        val puerto = puertoLibre()
        // `start(wait=false)` arranca de forma asíncrona: un fallo de `module()` se perdería
        // como excepción no capturada. El handler la hace visible en el output de la sonda.
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            println("[LOADPROBE] EXCEPCION NO CAPTURADA en ${t.name}: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
        }
        val server = arrancarServidor(puerto, avatarDir)
        server.start(wait = false)
        try {
            esperarListo(puerto)
            val base = "http://127.0.0.1:$puerto"
            println("[LOADPROBE] servidor listo en $base (pool=era-hikari)")

            prepararDatos()
            val tokenSync = JwtTokenService(JWT_PROBE).emitir(idUsuarioSync())

            val resultados = mutableListOf<ResultadoEscenario>()

            // Escenario 0: baseline del login legítimo (servidor idle). Cuantifica cuánto
            // espera un usuario real SIN ataque: ~coste de un bcrypt coste 11 en solitario.
            val baselineLegit =
                ejecutarEscenario(
                    nombre = "login-legitimo-baseline",
                    concurrencia = 1,
                    rondas = 10,
                ) { loginRequest(base, USUARIO_LEGIT, CLAVE_LEGIT) }
            resultados += baselineLegit

            // Escenario 1: login con contraseña incorrecta (bcrypt real), rampa 1→50.
            for (c in listOf(1, 5, 10, 20, 50)) {
                resultados +=
                    ejecutarEscenario(
                        nombre = "login-fallido",
                        concurrencia = c,
                        rondas = rondasPara(c),
                    ) { loginRequest(base, siguienteUsuarioLogin(), CLAVE_INCORRECTA) }
            }

            // Escenario 1a: login con identificador INEXISTENTE (variante B-4, HASH_DUMMY),
            // rampa 1→50. Nunca alcanza el 5.º fallo (no hay contador ni ventana): es la
            // variante que un atacante persistente preferiría. Debe quedar pareja con 1.
            for (c in listOf(1, 5, 10, 20, 50)) {
                resultados +=
                    ejecutarEscenario(
                        nombre = "login-fallido-inexistente",
                        concurrencia = c,
                        rondas = rondasPara(c),
                    ) { loginRequest(base, siguienteUsuarioInexistente(), CLAVE_INCORRECTA) }
            }

            // Escenario 1b (mixto): c=50 de ataque + logins LEGÍTIMOS en vuelo simultáneo.
            // Objetivo: el número exacto que espera un usuario real durante el ataque.
            val (ataqueMixto, legitimoDuranteAtaque) =
                ejecutarAtaqueConLegitimos(
                    nombre = "login-fallido+legitimo",
                    concurrencia = 50,
                    rondas = 2,
                    solicitudAtaque = { loginRequest(base, siguienteUsuarioLogin(), CLAVE_INCORRECTA) },
                    solicitudesLegitimas = List(10) { { loginRequest(base, USUARIO_LEGIT, CLAVE_LEGIT) } },
                )

            // Escenario 2: cuenta bloqueada (fast-path B-2, sin bcrypt), concurrencia 20.
            bloquearCuenta()
            resultados +=
                ejecutarEscenario(
                    nombre = "login-bloqueado",
                    concurrencia = 20,
                    rondas = 5,
                ) { loginRequest(base, "probelogin-lock", CLAVE_INCORRECTA) }

            // Escenario 3: GET /progress/sync autenticado (Exposed sobre el pool), rampa.
            for (c in listOf(1, 10, 50)) {
                resultados +=
                    ejecutarEscenario(
                        nombre = "progress-sync",
                        concurrencia = c,
                        rondas = rondasPara(c),
                    ) { syncRequest(base, tokenSync) }
            }

            // Criterio REQ-NF-01 sobre los escenarios sin bcrypt.
            val sync = resultados.filter { it.nombre == "progress-sync" }
            val bloqueado = resultados.single { it.nombre == "login-bloqueado" }
            sync.forEach { r ->
                check(r.p95Ms < 3000.0) {
                    "REQ-NF-01: p95=${r.p95Ms}ms >= 3000ms en progress-sync c=${r.concurrencia}"
                }
            }
            check(bloqueado.p95Ms < 3000.0) { "REQ-NF-01: p95=${bloqueado.p95Ms}ms >= 3000ms en login-bloqueado" }

            // Contrato de códigos por escenario.
            val statusLogin = mapOf(401 to true)
            val statusBloqueado = mapOf(423 to true)
            val statusSync = mapOf(200 to true)
            val statusLegit = mapOf(200 to true)
            resultados.forEach { r ->
                val esperados =
                    when {
                        r.nombre == "login-fallido" -> statusLogin
                        r.nombre == "login-fallido-inexistente" -> statusLogin
                        r.nombre == "login-bloqueado" -> statusBloqueado
                        r.nombre.startsWith("progress-sync") -> statusSync
                        else -> statusLegit
                    }
                val ok =
                    r.respuestasPorStatus.size == esperados.size &&
                        r.respuestasPorStatus.keys.all { esperados.containsKey(it) }
                check(ok) { "Respuestas inesperadas en ${r.nombre} c=${r.concurrencia}: ${r.respuestasPorStatus}" }
            }

            resultados.forEach(::imprimirResultado)
            val p95MaxSync = sync.maxOf { it.p95Ms }
            println(
                "[LOADPROBE] REQ-NF-01: p95 maximo en progress-sync = ${"%.1f".format(
                    p95MaxSync
                )}ms (techo 3000ms) -> ${if (p95MaxSync < 3000.0) "CUMPLE" else "NO CUMPLE"}",
            )
            val p95MaxLogin = resultados.filter { it.nombre == "login-fallido" }.maxOf { it.p95Ms }
            val p95MaxInexistente =
                resultados.filter { it.nombre == "login-fallido-inexistente" }.maxOf { it.p95Ms }

            // Paridad B-4: ambas variantes (existente vs inexistente) deben quedar parejas
            // entre sí. Se reporta, no se hard-asserta (igual que el resto del login bcrypt).
            val mayorParidad = maxOf(p95MaxLogin, p95MaxInexistente)
            val menorParidad = minOf(p95MaxLogin, p95MaxInexistente)
            println(
                "[LOADPROBE] paridad B-4: login-fallido p95 max = ${"%.1f".format(p95MaxLogin)}ms vs " +
                    "login-fallido-inexistente p95 max = ${"%.1f".format(p95MaxInexistente)}ms " +
                    "(max/min = ${"%.2f".format(mayorParidad / menorParidad)}) -> " +
                    if (mayorParidad < 3000.0) {
                        "bajo el techo REQ-NF-01"
                    } else {
                        "HALLAZGO: bcrypt serializa en el event loop (cuello de botella a documentar)"
                    },
            )

            // Medición clave del escenario mixto: lo que espera un usuario real durante el ataque.
            imprimirMixto(ataqueMixto, legitimoDuranteAtaque, baselineLegit.p95Ms)
        } finally {
            cliente.close()
            server.stop(1000, 3000)
            limpiarDatos()
            Files.deleteIfExists(Paths.get(avatarDir))
        }
    }

    // ── Servidor embebido con el wiring real ──────────────────────────────────────────

    private fun arrancarServidor(
        puerto: Int,
        avatarDir: String,
    ): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
        val config =
            MapApplicationConfig(
                "ktor.deployment.port" to "0",
                "database.host" to (System.getenv("DB_HOST") ?: "localhost"),
                "database.port" to (System.getenv("DB_PORT") ?: "3306"),
                "database.name" to MySqlTestPool.NOMBRE_PRUEBA,
                "database.user" to System.getenv("DB_USER").orEmpty(),
                "database.password" to System.getenv("DB_PASSWORD").orEmpty(),
                "database.pool.maxSize" to "10",
                "database.pool.connectionTimeoutMs" to "30000",
                "jwt.secret" to SECRETO_PRUEBA,
                "jwt.session.issuer" to JWT_PROBE.sessionIssuer,
                "jwt.session.audience" to JWT_PROBE.sessionAudience,
                "jwt.session.expirationMinutes" to JWT_PROBE.sessionExpirationMinutes.toString(),
                "jwt.passwordReset.issuer" to JWT_PROBE.resetIssuer,
                "jwt.passwordReset.audience" to JWT_PROBE.resetAudience,
                "jwt.passwordReset.ttlMinutes" to JWT_PROBE.resetTtlMinutes.toString(),
                "jwt.passwordReset.purpose" to JWT_PROBE.resetPurpose,
                "mail.host" to "smtp.probe.invalid",
                "mail.port" to "587",
                "mail.user" to "probe",
                "mail.password" to "probe",
                "mail.from" to "probe@era.invalid",
                "storage.avatarDir" to avatarDir,
            )
        val environment =
            applicationEnvironment {
                this.config = config
            }
        return embeddedServer(
            Netty,
            environment,
            configure = {
                connector {
                    host = "127.0.0.1"
                    port = puerto
                }
            },
            module = Application::module,
        )
    }

    /** Puerto libre efímero para el servidor del probe (evita colisiones entre runs). */
    private fun puertoLibre(): Int = ServerSocket(0).use { it.localPort }

    private fun esperarListo(puerto: Int) {
        var intentos = 0
        while (intentos < 300) {
            try {
                Socket("127.0.0.1", puerto).use { return }
            } catch (_: IOException) {
                Thread.sleep(100)
                intentos++
            }
        }
        val evidencias = StringBuilder()
        Thread.getAllStackTraces().forEach { (t, frames) ->
            val nombre = t.name
            val relevante =
                frames.any { f ->
                    val c = f.className
                    c.contains("netty") || c.contains("flyway") || c.contains("hikari") ||
                        c.contains("mysql") || c.contains("exposed") || c.contains("ktor") ||
                        c.contains("com.era.backend")
                }
            if (relevante) {
                evidencias.append("\n-- ${t.name} [${t.state}] --\n")
                frames.take(12).forEach { f -> evidencias.append("    $f\n") }
            }
        }
        error("El servidor del probe no aceptó conexiones en 30s. Stacks relevantes:\n$evidencias")
    }

    // ── Datos de prueba en era_db_test ────────────────────────────────────────────────

    /** Estado limpio: truncado de las 12 tablas y siembra de usuarios/catálogo/progreso. */
    private fun prepararDatos() {
        // Post-fix (2026-08-13): los registros nuevos hashean la contraseña con bcrypt coste 11
        // (OtpService.COSTE_BCRYPT_PASSWORD), así que la siembra usa ese coste para medir el
        // escenario "post-fix". El plaintext es una clave REAL distinta de CLAVE_INCORRECTA
        // (que es la que envía el ataque y por eso debe fallar el verify → 401).
        val hashAtaque = BCrypt.withDefaults().hashToString(11, "clave-seed-ataque-2026".toCharArray())
        // Hash real (coste 11) de la contraseña del usuario legítimo: su login exitoso
        // cuesta exactamente lo mismo que el de un usuario real post-fix (una sola vez).
        val hashLegit = BCrypt.withDefaults().hashToString(11, CLAVE_LEGIT.toCharArray())
        MySqlTestPool.conBase { con ->
            con.createStatement().use { st ->
                st.execute("SET FOREIGN_KEY_CHECKS=0")
                MySqlTestPool.TABLAS.forEach { tabla -> st.execute("TRUNCATE TABLE $tabla") }
                st.execute("SET FOREIGN_KEY_CHECKS=1")
            }
            // 500 usuarios para el login-fallido: la rampa entera suma ~455 requests y cada
            // usuario recibe como maximo 1 fallo (nunca alcanzan el 5.º, sin 423s espurios).
            for (i in 0 until 500) {
                MySqlTestPool.insertarUsuario(
                    con,
                    correo = "probe.login$i@era.test",
                    nombreUsuario = usuarioLogin(i),
                    contrasenaHash = hashAtaque,
                )
            }
            // Usuario legítimo del escenario mixto: hash REAL de su contraseña (200 en login).
            MySqlTestPool.insertarUsuario(con, "probe.legit@era.test", USUARIO_LEGIT, hashLegit)
            // Usuario bloqueado para el fast-path B-2 (su hash no se verifica nunca).
            MySqlTestPool.insertarUsuario(con, "probe.lock@era.test", "probelogin-lock", hashAtaque)
            // Catálogo y progreso para el GET /progress/sync.
            MySqlTestPool.insertarCatalogoNiveles(con)
        }
        val idSync = idUsuarioSync()
        MySqlTestPool.conBase { con ->
            for (orden in 1..3) {
                MySqlTestPool.insertarProgreso(con, idSync, MySqlTestPool.idNivelPorOrden(con, orden), "completado", orden)
            }
        }
    }

    private fun idUsuarioSync(): Long =
        MySqlTestPool.conBase { con ->
            con.createStatement().use { st ->
                st.executeQuery("SELECT id_usuario FROM usuario WHERE nombre_usuario = 'probelogin0000'").use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }

    private fun bloquearCuenta() {
        MySqlTestPool.conBase { con ->
            con.createStatement().use { st ->
                st.executeUpdate(
                    "UPDATE usuario SET intentos_login_fallidos = 4, " +
                        "bloqueado_hasta = DATE_ADD(NOW(), INTERVAL 10 MINUTE) " +
                        "WHERE nombre_usuario = 'probelogin-lock'",
                )
            }
        }
    }

    private fun limpiarDatos() {
        MySqlTestPool.conBase { con ->
            con.createStatement().use { st ->
                st.execute("SET FOREIGN_KEY_CHECKS=0")
                MySqlTestPool.TABLAS.forEach { tabla -> st.execute("TRUNCATE TABLE $tabla") }
                st.execute("SET FOREIGN_KEY_CHECKS=1")
            }
        }
    }

    private fun usuarioLogin(i: Int): String = "probelogin" + i.toString().padStart(4, '0')

    /** Siguiente usuario del pool circular (500); cada uno recibe <= 1 fallo en la rampa. */
    private fun siguienteUsuarioLogin(): String = usuarioLogin(cursorUsuario.getAndIncrement() % 500)

    /**
     * Identificador inexistente rotativo: nunca coincide con un usuario sembrado y nunca
     * dispara el bloqueo B-2/B-3 (el camino siempre termina en el `HASH_DUMMY` de B-4).
     */
    private fun siguienteUsuarioInexistente(): String =
        "probe.noexiste" + cursorInexistente.getAndIncrement().toString().padStart(4, '0')

    // ── Cliente HTTP ──────────────────────────────────────────────────────────────────

    private fun loginRequest(
        base: String,
        usuario: String,
        contrasena: String,
    ): HttpRequest =
        HttpRequest.newBuilder(URI("$base/api/v1/auth/login"))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """{"usuarioOCorreo":"$usuario","contrasena":"$contrasena"}""",
                ),
            )
            .build()

    private fun syncRequest(base: String, token: String): HttpRequest =
        HttpRequest.newBuilder(URI("$base/api/v1/progress/sync"))
            .header("Authorization", "Bearer $token")
            .GET()
            .build()

    // ── Ejecución y medición ──────────────────────────────────────────────────────────

    private fun ejecutarEscenario(
        nombre: String,
        concurrencia: Int,
        rondas: Int,
        solicitud: (Int) -> HttpRequest,
    ): ResultadoEscenario {
        val latencias = ArrayList<Long>(concurrencia * rondas)
        val statuses = ConcurrentHashMap<Int, AtomicInteger>()
        var totalWallNs = 0L
        for (ronda in 0 until rondas) {
            val inicioRonda = System.nanoTime()
            val futuras = ArrayList<CompletableFuture<Long>>(concurrencia)
            for (i in 0 until concurrencia) {
                val t0 = System.nanoTime()
                futuras.add(
                    cliente.sendAsync(solicitud(i), HttpResponse.BodyHandlers.ofString())
                        .thenApply { resp ->
                            statuses.computeIfAbsent(resp.statusCode()) { AtomicInteger(0) }.incrementAndGet()
                            System.nanoTime() - t0
                        },
                )
            }
            for (f in futuras) latencias.add(f.join())
            totalWallNs += System.nanoTime() - inicioRonda
        }
        return construirResultado(nombre, concurrencia, rondas, latencias, statuses.mapValues { it.value.get() }, totalWallNs)
    }

    /**
     * Escenario mixto (DoS): `concurrencia` requests de ataque + las [solicitudesLegitimas]
     * se disparan en el mismo instante y compiten por los mismos event-loop threads y el
     * mismo pool. Devuelve los dos buckets medidos por separado: el del ataque y el de los
     * logins legítimos (los que sufren la degradación).
     */
    private fun ejecutarAtaqueConLegitimos(
        nombre: String,
        concurrencia: Int,
        rondas: Int,
        solicitudAtaque: (Int) -> HttpRequest,
        solicitudesLegitimas: List<() -> HttpRequest>,
    ): Pair<ResultadoEscenario, ResultadoEscenario> {
        val latenciasAtaque = ArrayList<Long>(concurrencia * rondas)
        val latenciasLegit = ArrayList<Long>(solicitudesLegitimas.size * rondas)
        val statusesAtaque = ConcurrentHashMap<Int, AtomicInteger>()
        val statusesLegit = ConcurrentHashMap<Int, AtomicInteger>()
        var totalWallNs = 0L
        for (ronda in 0 until rondas) {
            val inicioRonda = System.nanoTime()
            val futurasAtaque = ArrayList<CompletableFuture<Long>>(concurrencia)
            val futurasLegit = ArrayList<CompletableFuture<Long>>(solicitudesLegitimas.size)
            for (i in 0 until concurrencia) {
                val t0 = System.nanoTime()
                futurasAtaque.add(
                    cliente.sendAsync(solicitudAtaque(i), HttpResponse.BodyHandlers.ofString())
                        .thenApply { resp ->
                            statusesAtaque.computeIfAbsent(resp.statusCode()) { AtomicInteger(0) }.incrementAndGet()
                            System.nanoTime() - t0
                        },
                )
            }
            for (fabrica in solicitudesLegitimas) {
                val t0 = System.nanoTime()
                futurasLegit.add(
                    cliente.sendAsync(fabrica(), HttpResponse.BodyHandlers.ofString())
                        .thenApply { resp ->
                            statusesLegit.computeIfAbsent(resp.statusCode()) { AtomicInteger(0) }.incrementAndGet()
                            System.nanoTime() - t0
                        },
                )
            }
            for (f in futurasAtaque) latenciasAtaque.add(f.join())
            for (f in futurasLegit) latenciasLegit.add(f.join())
            totalWallNs += System.nanoTime() - inicioRonda
        }
        return construirResultado(
            nombre,
            concurrencia,
            rondas,
            latenciasAtaque,
            statusesAtaque.mapValues { it.value.get() },
            totalWallNs
        ) to
            construirResultado(
                "$nombre/legitimo",
                solicitudesLegitimas.size,
                rondas,
                latenciasLegit,
                statusesLegit.mapValues { it.value.get() },
                totalWallNs
            )
    }

    private fun construirResultado(
        nombre: String,
        concurrencia: Int,
        rondas: Int,
        latencias: List<Long>,
        statuses: Map<Int, Int>,
        totalWallNs: Long,
    ): ResultadoEscenario {
        val ordenadas = latencias.sorted()
        val n = ordenadas.size

        fun percentil(p: Double): Double = ordenadas[ceil(p * (n - 1)).toInt()] / 1_000_000.0
        return ResultadoEscenario(
            nombre = nombre,
            concurrencia = concurrencia,
            rondas = rondas,
            totalRequests = n,
            p50Ms = percentil(0.50),
            p95Ms = percentil(0.95),
            p99Ms = percentil(0.99),
            rps = n / (totalWallNs / 1e9),
            respuestasPorStatus = statuses,
        )
    }

    private fun imprimirResultado(r: ResultadoEscenario) {
        println(
            "[LOADPROBE] ${r.nombre} c=${r.concurrencia} rondas=${r.rondas} n=${r.totalRequests} " +
                "p50=${"%.1f".format(r.p50Ms)}ms p95=${"%.1f".format(r.p95Ms)}ms p99=${"%.1f".format(r.p99Ms)}ms " +
                "rps=${"%.1f".format(r.rps)} status=${r.respuestasPorStatus}",
        )
    }

    /** Número que decide la urgencia: cuánto espera un usuario real durante el ataque. */
    private fun imprimirMixto(
        ataque: ResultadoEscenario,
        legitimo: ResultadoEscenario,
        p95BaselineLegit: Double,
    ) {
        println(
            "[LOADPROBE] ${ataque.nombre} c=${ataque.concurrencia}: ataque p95=${"%.1f".format(
                ataque.p95Ms
            )}ms (n=${ataque.totalRequests})",
        )
        val delta = legitimo.p95Ms - p95BaselineLegit
        println(
            "[LOADPROBE] ${legitimo.nombre} DURANTE el ataque: p50=${"%.1f".format(
                legitimo.p50Ms
            )}ms p95=${"%.1f".format(legitimo.p95Ms)}ms (n=${legitimo.totalRequests}) " +
                "[baseline p95=${"%.1f".format(p95BaselineLegit)}ms; delta=+${"%.1f".format(delta)}ms]",
        )
    }
}
