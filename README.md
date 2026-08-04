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
| Acceso a datos | Exposed (pendiente de añadir) | DSL de tablas y consultas con tipado seguro. |
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
- Nota: los tests auto-descubren `resources/application.yaml`; las `${VAR}` deben
  estar definidas en el entorno de la sesión.

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

> Pendiente para los módulos de datos: Exposed, HikariCP y la sección de base de datos en
> `resources/application.yaml`. Se añaden módulo a módulo y previa aprobación (regla de
> trabajo de CLAUDE.md). El driver MySQL ya está disponible para las migraciones.

## Estado del proyecto

- Base verificada del entorno: JDK 21 (Temurin), MySQL Server 8.0, MySQL Workbench 8.0,
  IntelliJ IDEA 2026.2, cliente REST vía `curl`/HTTP Client de IDEA.
- Proyecto funcional: `GET /` responde y el build compila.
- Nada de ERA implementado todavía (rutas, capas y tablas por crear).
