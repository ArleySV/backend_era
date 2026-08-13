# ERA — Fase 2: Tests de integración MySQL — Diseño técnico de concurrencia

> Documentación oficial del sistema ERA. Ver [`../CLAUDE.md`](../CLAUDE.md) para las reglas
> permanentes y la matriz de trazabilidad.
>
> **Estado:** PROPUESTA — pendiente de aprobación del propietario del proyecto (regla 3 de
> CLAUDE.md §5: antes de implementar más de un archivo, presentar plan y esperar confirmación).
>
> **Base normativa:** [`../CLAUDE.md`](../CLAUDE.md) §4 (reglas 4, 6), §5 (regla 3) y §6
> (datos personales); [`../resources/db/migration/V1__init_schema.sql`](../resources/db/migration/V1__init_schema.sql)
> (tablas `usuario`, `acudiente`, `configuracion`, `registro_pendiente`).

Este documento describe el **diseño técnico** de los tres tests restantes de la Fase 2 del
plan de testing (tests de integración contra **MySQL real**, base `era_db_test`), que deben
**simular concurrencia real** con hilos en Kotlin/JUnit. No es el contrato de un endpoint:
es la estrategia para verificar, contra la BD real, que tres garantías del backend se
cumplen incluso bajo ejecución paralela.

El test de integración ya existente (`MySqlIntegrationTest`, 3 tests: idempotencia Flyway,
UNIQUE de correo, FK `ON DELETE RESTRICT`) queda intacto en su comportamiento; solo se
refactoriza para compartir el pool (ver §2).

---

## 1. Alcance: los tres tests pendientes

| # | Garantía a verificar | Flujo bajo prueba |
|---|---|---|
| 1 | **Transacción atómica de `verify-email`**: si falla la creación a mitad (usuario → acudiente → configuracion), no queda ninguna fila a medias (rollback completo). | `VerificationService.verificarEmail` (A.1, V1:55-57) |
| 2 | **Unicidad anti-TOCTOU de registro**: dos `register` concurrentes con el mismo correo → exactamente uno tiene éxito; la constraint `UNIQUE` es el respaldo final. | `RegistrationService.register` (REQ-FUN-01) |
| 3 | **`SELECT ... FOR UPDATE` del login**: dos fallos simultáneos del mismo usuario no corrompen el contador de intentos (sin lost-update). | `LoginService.login` (REQ-FUN-02) |

---

## 2. Infraestructura compartida

### 2.1 Nuevo `test/com/era/backend/db/MySqlTestPool.kt`

Object con responsabilidad única: la fuente de datos de los tests de integración.

| Miembro | Detalle |
|---|---|
| `NOMBRE_PRUEBA` | `TEST_DB_NAME` (default `era_db_test`), con `require(it != "era_db")` (anti-producción). |
| `POOL` | `HikariDataSource` vía `DatabaseFactory.createDataSource(DatabaseConfig(...))` desde env vars `DB_HOST/DB_PORT/DB_USER/DB_PASSWORD` (mismo patrón que el `MySqlIntegrationTest` actual). |
| `conBase { }` / `verificarBase` | Helpers con el guard `SELECT DATABASE()` (conexión activa debe ser `NOMBRE_PRUEBA`), movidos desde `MySqlIntegrationTest`. |
| `connectExposed()` | Llamada única (lazy) a `DatabaseFactory.connectExposed(POOL)` para que los repositorios Exposed reales funcionen en los tests de service. |

**Por qué compartirlo:** el guard anti-base-equivocada es crítico de seguridad (CLAUDE.md §6,
regla 8): debe vivir en un solo lugar y ser idéntico para todas las clases de integración.

### 2.2 Refactor mínimo de `MySqlIntegrationTest.kt`

Eliminar su `POOL`/helpers privados y consumir `MySqlTestPool`. Los 3 tests existentes no
cambian de lógica ni de aserciones.

---

## 3. Mecanismo de concurrencia (Kotlin/JUnit)

Se usan **hilos reales + `CyclicBarrier`** (no corrutinas) para maximizar la simultaneidad
de entrada a las transacciones:

```kotlin
fun <T> ejecutarConcurrentemente(n: Int, bloque: (Int) -> T): List<Result<T>> {
    val executor = Executors.newFixedThreadPool(n)
    val barrier = CyclicBarrier(n)
    return try {
        (0 until n).map { i ->
            executor.submit(Callable {
                barrier.await(10, TimeUnit.SECONDS) // todos liberados a la vez
                try { Result.success(bloque(i)) } catch (t: Throwable) { Result.failure(t) }
            })
        }.map { it.get(60, TimeUnit.SECONDS) }
    } finally {
        executor.shutdownNow() // nunca fugarse hilos
    }
}
```

Puntos de diseño:

- **Cada hilo ejecuta `ExposedTransactionRunner.run { ... }`** — la misma ruta que usa el
  servidor con cada request — sobre su **propia conexión** Hikari (pool `maxSize=10`,
  usamos 2). Concurrencia real a nivel de MySQL/InnoDB, no simulada en memoria.
- `barrier.await(timeout)`: si un hilo no llega a la barrera en 10 s, el test falla
  visiblemente en lugar de colgarse.
- `Future.get(60 s)`: tope por tarea; `shutdownNow()` en `finally`.
- Cada tarea devuelve `Result<T>`: el test decide qué es "éxito" y qué "fallo esperado".

---

## 4. Test 1 — Rollback de `verify-email`

**Objetivo:** probar la conversión atómica usuario + acudiente + configuracion
(V1:55-57, `VerificationService.verificarEmail`).

**Setup:**
1. Crear el `registro_pendiente` con `RegistrationService` real
   (`OtpService(FakeOtpNotifier(), otpDeterminista = true)` → el código generado siempre es
   `"123456"`; conocemos el hash bcrypt de ese código).
2. Construir un `VerificationService` con repositorios reales Exposed y
   `ExposedTransactionRunner`, **excepto** `acudienteRepository`, que es un stub:

   ```kotlin
   class AcudienteQueFalla : AcudienteRepository {
       override fun insert(row: AcudienteRow): Long =
           throw SQLException("fallo inducido en acudiente")
   }
   ```

**Invocación:**
```kotlin
assertFailsWith<SQLException> { service.verificarEmail(correo, "123456") }
```
El flujo inserta `usuario` (éxito) → el stub lanza en `acudiente` → la excepción propaga
fuera de `transaction {}` → **rollback completo** (incluido el `deleteById` del pendiente).

**Post-condiciones (consultas JDBC con `conBase`, guard incluido):**
- `usuario WHERE correo = X` → **0**
- `acudiente` → **0**
- `configuracion` → **0**
- `registro_pendiente WHERE correo = X` → **1** (el pendiente sigue ahí, intacto)

**Criterio de aceptación:** ningún conteo distinto de los esperados ⇒ la transacción
revirtió por completo. Si `transaction {}` no revirtiera, quedaría al menos la fila de
`usuario`.

---

## 5. Test 2 — Unicidad anti-TOCTOU del registro

**Objetivo:** con dos `register` concurrentes del mismo correo (usernames **distintos**
para aislar solo la colisión de correo), exactamente uno tiene éxito.

**Setup:** `RegistrationService` real (repos Exposed + OtpService determinista +
`ExposedTransactionRunner`). Dos DTOs idénticos salvo `nombreUsuario` (`user_a` / `user_b`).

**Ejecución:** `ejecutarConcurrentemente(2) { i -> service.register(dto[i]) }`.

**Por qué ambos pueden llegar al INSERT:** los checks previos del registro usan SELECT
**sin lock** (`findByEmail`, `existsByUsername`); bajo `REPEATABLE READ`, un SELECT
consistente no ve el INSERT no commiteado del otro hilo. Ambos pueden superar la validación
y colisionar en el `INSERT` → decide `uq_registro_pendiente_correo`. El bcrypt(12) de
contraseña + OTP dentro de la transacción (~200-400 ms) amplía la ventana de solapamiento.

**Post-condiciones (invariantes deterministas):**
- Exactamente **1** `register()` retorna `Result.success`; la otra tarea falla con
  `EmailAlreadyRegisteredException` (perdió en el check) **o** con error de clave duplicada
  (perdió en el INSERT, posiblemente deadlock 1213 de InnoDB) — ambos cuentan como
  "perdedor".
- `registro_pendiente WHERE correo = X` → **1**
- `usuario` → **0** (el registro sigue en estado pendiente)

**Por qué es válido aunque el "ganador" sea no-determinista:** la **invariante** sí lo es.
Si la constraint `UNIQUE` no existiera, ambos podrían commiteear → `COUNT = 2` → test rojo.

---

## 6. Test 3 — `FOR UPDATE` del login bajo concurrencia

**Objetivo:** dos fallos simultáneos del mismo usuario deben quedar contabilizados en
serie (`intentos_login_fallidos = 2`), sin lost-update (no 1).

**Setup:**
1. Insertar por JDBC un `usuario` con `estado = 'activo'`, `intentos_login_fallidos = 0`,
   `bloqueado_hasta = NULL` y un hash bcrypt real de una contraseña (que no usaremos).
2. `LoginService` real (repos Exposed + `ExposedTransactionRunner`) con un
   `JwtTokenService` construido con config dummy (nunca se emite token: ambos logins fallan).

**Ejecución:**
```kotlin
ejecutarConcurrentemente(2) { i ->
    service.login(LoginRequestDto(username, "ContrasenaIncorrecta1!"))
}
```

**Por qué el resultado es determinista (=2):** cada login corre `findByUsernameForUpdate`
(`SELECT ... FOR UPDATE`). InnoDB serializa: el segundo bloquea en el lock de la fila hasta
el commit del primero; las locking reads leen **siempre** la última versión commiteada, así
que el segundo lee el contador ya en 1 y escribe 2. Sin `FOR UPDATE`, ambos podrían leer 0 y
escribir 1 → el test fallaría. Es la verificación real del patrón (auditoría #2 del
`LoginService`).

**Post-condiciones:**
- Ambos logins lanzan `InvalidCredentialsException` (el throw ocurre **fuera** de la
  transacción; el incremento se commitea — mismo patrón que P1 en A.1).
- `intentos_login_fallidos` → **2**
- `bloqueado_hasta` → **NULL** (2 < 5, sin bloqueo prematuro)

---

## 7. Archivos a tocar (tras aprobación)

| Archivo | Cambio |
|---|---|
| `test/com/era/backend/db/MySqlTestPool.kt` | **nuevo** — pool + guard + `connectExposed` (ver §2.1) |
| `test/com/era/backend/db/MySqlConcurrenciaTest.kt` | **nuevo** — los 3 tests (§4-§6) |
| `test/com/era/backend/db/MySqlIntegrationTest.kt` | refactor al pool compartido (§2.2) |
| `scripts/integration_test.ps1` | `--include-classes=com.era.backend.db.*` (corre ambas clases) |
| `CLAUDE.md` | 265 tests, 28 suites + mención de los tests de concurrencia (solo tras verde) |

**Verificación:** compilar (`task :BACKEND_ERA:compileJvmTest`), ejecutar vía
`scripts/integration_test.ps1` (preflight anti-`era_db` + evidencia en `test-results/`),
confirmar los 6 tests en verde (3 existentes + 3 nuevos) antes de tocar `CLAUDE.md`.

---

## 8. Riesgos y notas

- **Exposed multi-hilo:** cada `transaction {}` corre en su propio hilo; es el mismo camino
  del servidor con cada request. Si la v1 API presentara afinidad de thread (no esperado),
  el fallback sería replicar la transacción con JDBC crudo. Se validará en la primera
  corrida real.
- **Deadlock 1213 de InnoDB** (test 2): InnoDB aborta a un perdedor; cuenta como "fallo del
  perdedor". La invariante (1 éxito, 1 fila) se mantiene.
- **Tiempo de corrida:** +3-6 s (bcrypt coste 12 + lock contention). Aceptable dentro del
  script de integración.
- **Seguridad (CLAUDE.md §6):** los tests no loguean correos, cédulas ni hashes; los datos
  de prueba usan valores sintéticos y la limpieza `TRUNCATE` corre en `@AfterTest`.
- **Repetibilidad:** los tests dependen de `era_db_test` ya migrada (V1+V2+V3) y del
  preflight del script; cada corrida es independiente.
