# BACKEND_ERA

Backend REST de **ERA (Educación, Repaso y Aprendizaje)**, app Android nativa de trivia
educativa para niños de básica primaria (7 a 11 años). Este repositorio contiene
**solo el backend**; el cliente Android consume esta API vía REST.

> Reglas de trabajo, alcance cerrado y trazabilidad de requisitos: ver [`CLAUDE.md`](CLAUDE.md).

## Stack técnico

| Componente | Elección | Por qué |
|---|---|---|
| Lenguaje | Kotlin | Mismo lenguaje que el cliente Android (interop de equipo y de modelos). |
| Framework | Ktor (Netty) | Ligero y asíncrono, suficiente para una API REST de autenticación y sincronización. |
| Base de datos | MySQL | Administrada con MySQL Workbench; requisito del proyecto. |
| Acceso a datos | Exposed (1.3.1) + HikariCP (7.1.0) | DSL de tablas y consultas con tipado seguro sobre un pool Hikari/MySQL. |
| Sistema de build | **Amper, no Gradle** | El proyecto salió del Ktor Project Generator con Amper; CLAUDE.md prohíbe crear archivos Gradle. |

## Comandos (wrapper Amper)

| Comando | Acción |
|---|---|
| `.\kotlin build` | Compilar |
| `.\kotlin run` | Levantar el servidor (puerto `$PORT`) |
| `.\kotlin test` | Ejecutar los tests |
| `.\kotlin check` | Ejecutar los checks del proyecto (hoy: solo tests) |
| `.\kotlin clean` | Limpiar salidas y cachés de build |
| `.\kotlin show tasks` | Ver el grafo de tareas |
| `.\kotlin do <command>` | Ejecutar un comando personalizado (requiere plugin; hoy ninguno) |

> **Variables de entorno:** `application.yaml` resuelve `${VAR}` (system property o
> variable de entorno). Definir `PORT`, `DB_*`, `JWT_SECRET`, `SMTP_*` antes de
> `run`/`test`; `.env.example` es solo referencia, la JVM no lo lee automáticamente.

## Comandos de desarrollo

### 1. Servidor en modo desarrollo (recarga automática)
- **Hoy:** `.\kotlin run` (sin recarga). Amper/Kotlin CLI no tiene `--watch` para
  aplicaciones JVM.
- Ktor 3.4.3 sí ofrece auto-reload de desarrollo (`ktor.development: true` +
  `ktor.deployment.watch`), pero requiere que las clases se recompilen externamente
  (`.\kotlin build` o Build de IntelliJ) y su funcionamiento desde jar con Amper está
  pendiente de verificar.
- **Implementado:** `.\scripts\dev.ps1` vigila `src/` y `resources/`, recompila con
  `.\kotlin build` y reinicia `.\kotlin run` al detectar cambios
  (`.\scripts\dev.ps1 -Once` compila y corre sin vigilar). El ktor auto-reload
  (`ktor.development` + `ktor.deployment.watch`) sigue pendiente de verificar con
  Amper/jar; la recarga del script no depende de él.

### 2. Tests
- **Hoy:** `.\kotlin test` (equivalente a `./gradlew test`). `.\kotlin check` también
  ejecuta los tests.
- **Suite actual: 61 tests** que cubren el flujo de autenticación y verificación de correo
  (`RegistrationServiceTest`, `OtpServiceTest`, `VerificationServiceTest`,
  `AuthControllerTest`, `AuthControllerVerificationTest`), la validación de forma y
  negocio, el manejo centralizado de errores (`ErrorHandlingTest`) y la carga de
  configuración (`ConfigLoadTest`).
- Nota: los tests auto-descubren `resources/application.yaml`; las `${VAR}` deben
  estar definidas en el entorno de la sesión.

#### Pruebas de Humo (E2E)
Valida el flujo completo **Register → Verify** contra un servidor en ejecución, incluida
la persistencia real en `usuario` / `acudiente` / `configuracion` (Base de Trazabilidad
de Calidad, V11).

**Guía de ejecución (dos terminales):**

1. *Terminal 1 — servidor*: define las variables de entorno y levanta el backend. El
   servidor **queda corriendo en primer plano** (no "termina"; es el comportamiento
   esperado; detener con `Ctrl+C`):
   ```powershell
   $env:PORT='8080'
   $env:JWT_SECRET='<secreto_dev>'
   $env:APP_DEV_MODE='true'
   $env:DB_HOST='localhost'; $env:DB_PORT='3306'; $env:DB_NAME='era_db'
   $env:DB_USER='<usuario>'; $env:DB_PASSWORD='<password>'
   $env:SMTP_HOST='<placeholder>'; $env:SMTP_PORT='587'
   $env:SMTP_USER='x'; $env:SMTP_PASSWORD='x'; $env:SMTP_FROM='x@era.local'
   .\kotlin run
   ```
   Espera el log `Responding at http://127.0.0.1:8080`.
2. *Terminal 2 — prueba*: define las credenciales de BD (no se heredan de la otra
   terminal) y ejecuta el smoke test:
   ```powershell
   $env:DB_HOST='localhost'; $env:DB_PORT='3306'; $env:DB_NAME='era_db'
   $env:DB_USER='<usuario>'; $env:DB_PASSWORD='<password>'
   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\smoke_test.ps1
   ```
   Salida esperada: `[1/5] Servidor OK` → `[2/5] Limpieza OK` → `[3/5] 201 OK` →
   `[4/5] 200 OK` → `[5/5] BD OK` → `[FINAL] SMOKE TEST PASS` (exit 0).

**Por qué es importante `APP_DEV_MODE="true"` en el entorno de desarrollo:**
- Activa el **OTP fijo `123456`** (`OtpService` en modo determinista) y el **envío SMTP
  No-Op** (`SimpleJavaMailOtpNotifier` imprime el código en consola sin conectarse a un
  servidor de correo). Sin este flag, el smoke test no podría verificar el código (no hay
  SMTP real en dev) y `register` fallaría al intentar enviar el correo.
- Es **solo para desarrollo**: en producción `APP_DEV_MODE` debe estar ausente o en
  `false`; el OTP real usa `SecureRandom` y el SMTP envía de verdad. No deriva del
  `JWT_SECRET` (decisión V11).

**Requisitos técnicos del script:**
- Servidor levantado en `http://localhost:$PORT`.
- `mysql.exe` accesible en el `PATH` (MySQL Server 8.0): el script limpia datos previos
  del usuario de prueba y valida la persistencia SQL.
- El script hace *preflight* (`GET /`), limpia `test@example.com` / `test_user`, registra,
  verifica con `123456` y solo imprime el PASS final si el `INNER JOIN` sobre
  `usuario` / `acudiente` / `configuracion` devuelve exactamente `1` fila.

### 3. Migraciones de base de datos
- **Implementado: Flyway.** `org.flywaydb:flyway-core` + `org.flywaydb:flyway-mysql`
  (dependencias de runtime), scripts SQL versionados en `resources/db/migration/`
  (`V1__*.sql`, ...). Historia en base (`flyway_schema_history`), sin borrar nada
  físicamente. `baselineOnMigrate(true)` permite adoptarlo sobre un esquema ya creado a
  mano.
- Aplicar migraciones sin levantar el servidor:
  ```
  .\scripts\migrate.ps1
  ```
  (equivale a `.\kotlin run --main-class=com.era.backend.database.MigrateRunnerKt`).
- El migrador también se ejecutará en el arranque del servidor (antes de conectar
  Exposed) cuando se implemente la capa de datos.
- Alternativa parcial descartada: Exposed `SchemaUtils`/`MigrationUtils` alinean el
  esquema desde el DSL, pero **sin versionado ni historial**. El plugin Gradle de Exposed
  (genera scripts comparando definiciones) **no aplica**: el proyecto usa Amper, no Gradle.

### 4. Formato / lint
- **Hoy: ninguno.** `.\kotlin check` solo ejecuta tests (no hay check de lint).
- **Implementado: ktlint 1.8.0 como CLI independiente** (jar descargado a `.tools/`, sin
  tocar `module.yaml`, no es dependencia de runtime) vía `scripts/`:
  - `.\scripts\lint.ps1` → comprobar (`ktlint 'src/**/*.kt' 'test/**/*.kt'`).
  - `.\scripts\lint.ps1 -Format` → corregir automáticamente (`ktlint --format`).

### Amper: ¿tareas personalizadas o scripts externos?
Amper expone un grafo de tareas (`kotlin show tasks`, `kotlin task <name>`) y permite
definir tareas/checks/commands personalizados, pero **solo escribiendo un plugin**
(`product: jvm/amper-plugin` + `plugin.yaml`, acciones en Kotlin). Para scripts de
conveniencia eso es desproporcionado; este repo resuelve los casos 1, 3 y 4 con **scripts
externos** en `scripts/` (`dev.ps1`, `migrate.ps1`, `lint.ps1`).

> **Ejecución de scripts PowerShell:** si la política de ejecución del sistema lo impide
> (`UnauthorizedAccess`), lanzar con
> `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\<script>.ps1` o
> configurar `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`.

> **Dependencias del catálogo:** en `module.yaml`, los alias con guiones de
> `libs.versions.toml` se referencian con puntos (`$libs.flyway-core` → `$libs.flyway.core`,
> `$libs.mysql-connector-j` → `$libs.mysql.connector.j`), igual que
> `$libs.logback-classic` → `$libs.logback.classic`.

## Dependencias configuradas

Las dependencias se declaran en `libs.versions.toml` (versiones) y `module.yaml`
(módulo). El prefijo `$ktor.*` lo gestiona el catálogo de Ktor de Amper (versión
resuelta: **3.4.3**), de modo que no se fijan versiones manuales que puedan
desincronizarse.

| Dependencia | Versión | Para qué sirve |
|---|---|---|
| `$ktor.server.contentNegotiation` | gestionada por Amper | Leer/escribir cuerpos JSON en los endpoints. |
| `$ktor.serialization.kotlinx.json` | gestionada por Amper | Serialización JSON (kotlinx). |
| `$ktor.server.auth.jwt` | gestionada por Amper | Tokens JWT para sesiones (login/logout). |
| `$libs.bcrypt` (`at.favre.lib:bcrypt`) | 0.10.2 | Hash de un solo sentido de contraseñas (REQ-FUN-01); descartado el cifrado reversible. |
| `$libs.simpleJavaMail` (`org.simplejavamail:simple-java-mail`) | 8.12.6 | Envío de correos OTP vía SMTP. |
| `$libs.flyway-core` (`org.flywaydb:flyway-core`) | 12.11.0 | Migraciones versionadas de esquema. |
| `$libs.flyway-mysql` (`org.flywaydb:flyway-mysql`) | 12.11.0 | Soporte de MySQL en Flyway (community). |
| `$libs.mysql-connector-j` (`com.mysql:mysql-connector-j`) | 9.6.0 | Driver JDBC oficial para MySQL. |

> Exposed (core/java.time/jdbc) y HikariCP ya están declarados y los usa la capa de
> repositorios; la configuración de base de datos vive en `AppConfig`/
> `resources/application.yaml` (migraciones Flyway + pool Hikari/MySQL). Nuevas
> dependencias se añaden módulo a módulo y previa aprobación (regla de trabajo de
> CLAUDE.md).

## Estado del proyecto

- Base verificada del entorno: JDK 21 (Temurin), MySQL Server 8.0, MySQL Workbench 8.0,
  IntelliJ IDEA 2026.2, cliente REST vía `curl`/HTTP Client de IDEA.
- Proyecto funcional: `GET /` responde y el build compila.
- **Módulo A (Registro) completo:** `POST /api/v1/auth/register` operativo, con
  validaciones de forma (V4–V9) en el controller y de negocio (V1–V3, política de
  contraseña CA2) en el service.
- **Módulo A.1 (Verificación de correo) completo:** `POST /api/v1/auth/verify-email` y
  `POST /api/v1/auth/resend-otp` operativos — conversión transaccional pendiente → cuenta,
  políticas P1 (3 fallos invalidan el OTP) y P2 (60 s entre reenvíos), anti-enumeración y
  SMTP fuera de transacción.
- **Capa de datos:** Exposed/Flyway (esquema 12 tablas, V1+V2 aplicadas).
- **Prueba de humo E2E verificada:** `scripts/smoke_test.ps1` pasa Register → Verify con
  persistencia real en `usuario` / `acudiente` / `configuracion` (ver "Pruebas de Humo").

## Endpoints de la API (v1)

| Endpoint | Función |
|---|---|
| `POST /api/v1/auth/register` | Registro del menor y su acudiente + envío del OTP de 6 dígitos al correo. Valida la forma (V4–V9) y las reglas de negocio (unicidad de correo/usuario V1, limpieza lazy V2, política de contraseña CA2/V3), crea el registro pendiente con hash bcrypt (contraseña + OTP, vigencia 10 min) y responde `201 Created` con `{ "message": ... }`. |

Respuestas de error (formato estándar `ErrorDto`): `400` `VALIDATION_ERROR` (con
`details` por campo) / `INVALID_REQUEST`, y `409` `EMAIL_ALREADY_REGISTERED`,
`EMAIL_LOCKED` o `CONFLICT`.
