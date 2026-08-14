# BACKEND_ERA

Backend REST de **ERA (Educación, Repaso y Aprendizaje)**, app Android nativa de trivia
educativa para niños de básica primaria (7 a 11 años). Este repositorio contiene
**solo el backend**; el cliente Android consume esta API vía REST.

> Reglas de trabajo, alcance cerrado y trazabilidad de requisitos: ver [`CLAUDE.md`](CLAUDE.md).

## Integrantes del proyecto

| Nombre |
|---|
| EYBAR ARLEY SALCEDO VELASCO |
| JUNIOR JARRINSON LAVERDE LORZA |
| JAIRO DE JESUS FLOREZ CARVAJAL |

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
> variable de entorno). Definir `PORT`, `DB_*`, `JWT_SECRET`, `SMTP_*` y
> `AVATAR_STORAGE_DIR` antes de `run`/`test`; `.env.example` es solo referencia, la
> JVM no lo lee automáticamente. `AVATAR_STORAGE_DIR` es obligatoria (fail-fast): si no
> está definida, el servidor no arranca (ver Módulo I).

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
- **Suite actual: 196 tests** que cubren el flujo de autenticación, verificación de correo,
  login, recuperación de contraseña, cierre de sesión, perfil/eliminación de cuenta,
  sincronización de progreso y comentarios (`RegistrationServiceTest`, `OtpServiceTest`,
  `VerificationServiceTest`, `LoginServiceTest`, `PasswordResetServiceTest`,
  `LogoutServiceTest`, `UsuarioServiceTest`, `ProgressSyncServiceTest`,
  `ComentarioServiceTest`, `AuthControllerTest`, `AuthControllerVerificationTest`,
  `AuthControllerLoginTest`, `AuthControllerPasswordResetTest`,
  `AuthControllerLogoutTest`, `UserRoutesTest`, `ProgressControllerTest`,
  `FeedbackControllerTest`), la validación de forma y negocio,
  el manejo centralizado de errores (`ErrorHandlingTest`) y la carga de configuración
  (`ConfigLoadTest`).
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
   $env:AVATAR_STORAGE_DIR='C:\temp\era_avatares'   # directorio local de avatares (Módulo I); si no existe, el init lo crea
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
| `$ktor.server.auth.jwt` | gestionada por Amper | Autenticación JWT de sesiones. **Módulo B (login):** en uso — `JwtTokenService` emite tokens HS256 de 30 días; trae transitivamente la librería de firma `com.auth0:java-jwt`. |
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
- **Módulo B (Login) completo:** `POST /api/v1/auth/login` operativo — login por
  usuario/correo (case-insensitive, B-6), bloqueo de 2 min tras 5 fallos (B-2/B-3, ventana
  con limpieza lazy), error genérico anti-enumeración con hash dummy por timing (B-4),
  soft delete evaluado solo tras contraseña correcta (B-5) y emisión del JWT de sesión de
  30 días (`JwtTokenService`, HS256, `sub`/`iss`/`aud`/`jti`).
- **Módulo C (Recuperación de contraseña) completo:** `POST /api/v1/auth/password-reset/request`
  (OTP anti-enumeración C-1, throttle 60 s C-2), `/verify` (P1 3 fallos invalidan, single-use
  del OTP, token puente JWT de 10 min single-use con doble vínculo `jti`+`sub` C-3) y
  `/confirm` (política compartida C-6, veto a repetir la contraseña anterior REQ-FUN-07 CA5).
  Todo el acceso a datos en transacción; SMTP y emisión JWT fuera de la transacción.
- **Módulo D (Consulta de perfil) completo:** `GET /api/v1/users/me` operativo — perfil del
  usuario autenticado con **mínimo privilegio** (solo 5 campos: nombre, fecha de nacimiento
  ISO, correo, username, avatar; nunca hash ni cédula), protegido por el proveedor JWT
  `session-jwt` (`verifier` con audiencia `era-app-session` + `validate` que rechaza tokens
  de reseteo). Cuenta en soft delete → 403 `ACCOUNT_INACTIVE`. Diseño aprobado en
  `docs/modulo-d-analisis.md`.
- **Módulo E (Eliminación de cuenta) completo:** `DELETE /api/v1/users/me` operativo — soft
  delete por estado (`estado = 'eliminado'`, nunca borrado físico, REQ-FUN-05) con
  reverificación de contraseña (bcrypt **fuera de transacción**) y guarda anti-carrera
  (segunda transacción con relock `FOR UPDATE` + comprobación de estado activo). Errores:
  401 `INVALID_CREDENTIALS` (contraseña incorrecta) y 403 `ACCOUNT_INACTIVE` (ya eliminada).
  El correo queda bloqueado para nuevos registros (REQ-FUN-05 CA6).
- **Módulo F (Cierre de sesión) completo:** `POST /api/v1/auth/logout` operativo —
  **logout stateless** (ARQUITECTURA_BASE §5.4 #2): la invalidación del token es
  responsabilidad del cliente Android; el backend solo **confirma formalmente** el cierre
  (200 `MensajeResponseDto`) y registra el evento en el log INFO con `idUsuario` (nunca el
  token ni datos personales). Sin BD, sin blacklist, idempotente; único endpoint de
  `auth/*` protegido por `session-jwt`. Diseño aprobado en `docs/modulo-f-analisis.md`.
- **Módulo G (Sincronización de progreso) completo:** `GET`/`POST /api/v1/progress/sync`
  operativos — CU-12/REQ-FUN-10/11/12 con **solo agregados por nivel** (`estadoNivel`,
  `intentosTotales`, `intentosFallidosConsecutivos`; sin filas de `intento`, sin pausas).
  **Merge hacia adelante** (el estado usa precedencia `bloqueado < disponible < completado`;
  contadores = `max(cliente, servidor)`; `completadoEn` lo fija el servidor una sola vez),
  **POST atómico** vía `TransactionRunner` (400 `VALIDATION_ERROR` con **cero escrituras**
  si un `orden` no existe en el catálogo `nivel`). `totalReintentos = SUM(intentos_totales)`
  y `nivelesCompletados` calculados **en el servidor**; `totalNiveles = 20`. El POST
   responde el snapshot mergeado y persistido (un solo round-trip, CU-12 paso 3). Sin token
   → 401 `UNAUTHORIZED`; cuenta eliminada → 403 `ACCOUNT_INACTIVE`. El backend **no sirve**
   el catálogo de trivia. Diseño aprobado en `docs/modulo-g-analisis.md`.
- **Módulo H (Comentarios) completo:** `POST /api/v1/feedback/comments` operativo —
  CU-10/REQ-FUN-14 con **solo escritura** (`contenido`, máx. 2000 caracteres). El
  `id_usuario` se resuelve **siempre** del `SesionPrincipal` (nunca del body; una clave
  desconocida → 400 `INVALID_REQUEST`). Validación de forma en el controller (`isBlank()` y
  `length > 2000` → 400 `VALIDATION_ERROR` con `details` por campo), `.trim()` antes de
  persistir, inserción dentro de `TransactionRunner` y confirmación 200
  `MensajeResponseDto`. Sin token / token de reseteo → 401 `UNAUTHORIZED`; cuenta eliminada
  → 403 `ACCOUNT_INACTIVE`. **Regla de oro:** el contenido del comentario nunca se loguea;
  la auditoría usa solo `idComentario` e `idUsuario`. Diseño aprobado en
  `docs/modulo-h-analisis.md`.
- **Módulo I (Avatar personalizado) implementado (wiring completo; tests automáticos
  pendientes):** `PUT`/`GET /api/v1/users/me/avatar` operativos — subida y servido de la
  foto personalizada **post-verificación y solo con sesión autenticada** (misma barrera
  `session-jwt` de los Módulos D/E). PUT multipart (`avatar`, hasta **2 MB**) con
  **whitelist `jpeg/png/webp`** y doble validación (magic bytes + concordancia con el
  `Content-Type` declarado); RAM acotada (lectura del stream en fragmentos de 8 KB que
  aborta al superar el límite). El archivo se persiste en disco local (`AVATAR_STORAGE_DIR`,
  fail-fast al arrancar), con **clave `custom:<uuid>`**, escritura atómica, sidecar de MIME
  y retención ante soft delete (REQ-FUN-05: nunca se borra). GET responde el binario con
  `Cache-Control: private, no-store`, `X-Content-Type-Options: nosniff` y
  `Content-Disposition`; **404** si el perfil no tiene foto `custom:*`. **Compensación:** si
  la actualización de BD falla tras escribir el archivo, este se elimina. Logs de auditoría
  con `idUsuario`, nunca la clave ni el path. Errores de forma (sin parte, archivo ausente,
  tamaño, formato, MIME) → 400 `VALIDATION_ERROR` con `details`; sin token / token de
  reseteo → 401 `UNAUTHORIZED`; cuenta eliminada → 403 `ACCOUNT_INACTIVE`. Diseño aprobado
  en `docs/modulo-i-analisis.md`.
- **Módulo D — `PATCH /api/v1/users/me` (actualización de username, implementado):**
  segunda parte editable de REQ-FUN-06 CA5/CU-06/HU-06. **Decisiones
  aprobadas (2026-08-13):**
  1. Respuesta de éxito = `200 OK` con el `UsuarioPerfilDto` actualizado (el cliente
     muestra el username nuevo sin round-trip extra).
  2. Alcance restringido a `nombreUsuario`; cualquier otro campo del body se **ignora**
     (CA5). No toca `avatar` (el reset a preset queda fuera de alcance).
  3. Unicidad → `409 CONFLICT` si el nuevo username ya existe en `usuario` (cuentas
     activas **y** eliminadas, espejo de V1) o está **reservado en `registro_pendiente`**
     (espejo del alta); el propio usuario se excluye del chequeo y el UNIQUE de BD queda
     como backstop anti-carrera.
  4. La regla V3 (contraseña ≠ username) **no se revalida** en el cambio: aplica solo en
     alta y reset de contraseña (REQ-FUN-01/07). Consecuencia conocida y aceptada: el
     usuario podría fijar un username igual a su contraseña; endurecerlo sería añadir una
     revalidación solo en este endpoint.
  Protegido por `session-jwt` (401 `UNAUTHORIZED` sin sesión/token de reseteo; 403
  `ACCOUNT_INACTIVE` en cuenta eliminada; 400 `VALIDATION_ERROR` con `details` por forma;
  404 defensivo).
- **Autenticación de sesión:** proveedor JWT `session-jwt` instalado en el arranque
  (`plugins/AuthenticationConfig.kt`) con `challenge` que responde 401 `UNAUTHORIZED`
  estándar; compartido por los Módulos D/E/F/G/H/I.
- **Capa de datos:** Exposed/Flyway (esquema 12 tablas, V1+V2+V3 aplicadas).
- **Prueba de humo E2E verificada:** `scripts/smoke_test.ps1` pasa Register → Verify con
  persistencia real en `usuario` / `acudiente` / `configuracion` (ver "Pruebas de Humo").
- **Pruebas manuales verificadas en terminal (Módulos D/E):** flujo completo
  register → verify → login → `GET /me` (5 campos) → `DELETE /me` (soft delete) contra
  MySQL real, más los casos de error: 401 sin token, 401 `INVALID_CREDENTIALS` con
  contraseña incorrecta, 400 `VALIDATION_ERROR` con `details`, 403 `ACCOUNT_INACTIVE`
  post-eliminación y 403 al reintentar login de la cuenta eliminada.

## Endpoints de la API (v1)

| Endpoint | Función |
|---|---|
| `POST /api/v1/auth/register` | Registro del menor y su acudiente + envío del OTP de 6 dígitos al correo. Valida la forma (V4–V9) y las reglas de negocio (unicidad de correo/usuario V1, limpieza lazy V2, política de contraseña CA2/V3), crea el registro pendiente con hash bcrypt (contraseña + OTP, vigencia 10 min) y responde `201 Created` con `{ "message": ... }`. |
| `POST /api/v1/auth/login` | Inicio de sesión por **usuario o correo** (B-1) con contraseña. Tras 5 intentos fallidos consecutivos bloquea la cuenta 2 min (423 `ACCOUNT_LOCKED`); emite un **JWT de sesión de 30 días** (`sub` = id del usuario, `iss`/`aud`/`jti` configurables) y responde `200 OK` con `{ "token": ... }`. Errores genéricos (401 `INVALID_CREDENTIALS`) que no revelan qué campo falló ni si la cuenta existe; cuentas en soft delete con credenciales válidas → 403 `ACCOUNT_INACTIVE`. |
| `POST /api/v1/auth/password-reset/request` | Paso 1 de la recuperación: solicita un OTP de 6 dígitos (vigencia 10 min) para el correo. Responde **siempre** `200 OK` con el mismo mensaje genérico exista o no la cuenta (anti-enumeración C-1, REQ-FUN-07 CA4); reenvíos antes de 60 s → 429 `OTP_RESEND_THROTTLED` (C-2). |
| `POST /api/v1/auth/password-reset/verify` | Paso 2: verifica el OTP (máx. 3 fallos P1, single-use del código) y, si es correcto, responde `200 OK` con un **token puente JWT de 10 min single-use** (`{ "resetToken": ... }`, C-3). Errores → 401 `OTP_INVALID_OR_EXPIRED` genérico. |
| `POST /api/v1/auth/password-reset/confirm` | Paso 3: valida el token puente (firma/iss/aud/purpose/vigencia/single-use/doble vínculo `jti`+`sub`, C-3), exige la política compartida de contraseña (400, C-6) y el veto a repetir la anterior (409 `PASSWORD_REUSED`, REQ-FUN-07 CA5). Consume el token y responde `200 OK`; 401 `RESET_TOKEN_INVALID` genérico ante token inválido/vencido/usado. |
| `GET /api/v1/users/me` | Consulta del perfil del usuario autenticado (Módulo D, REQ-FUN-06). Requiere `Authorization: Bearer <token-sesión>`; responde `200 OK` con **solo 5 campos** (`nombreMenor`, `fechaNacimiento` ISO `yyyy-MM-dd`, `correo`, `nombreUsuario`, `avatar`). Sin token / token de reseteo → 401 `UNAUTHORIZED`; cuenta eliminada → 403 `ACCOUNT_INACTIVE`; fila inexistente (defensivo) → 404 `NOT_FOUND`. |
| `DELETE /api/v1/users/me` | Eliminación de la propia cuenta (Módulo E, REQ-FUN-05). **Soft delete** por estado con reverificación de contraseña (`contrasena` en el body); responde `200 OK` con `{ "message": ... }`. Contraseña incorrecta → 401 `INVALID_CREDENTIALS`; cuenta ya eliminada → 403 `ACCOUNT_INACTIVE`; forma inválida (vacía / > 72) → 400 `VALIDATION_ERROR` con `details`. Nunca borra filas físicamente. |
| `POST /api/v1/auth/logout` | Cierre de sesión (Módulo F, REQ-FUN-04). Requiere `Authorization: Bearer <token-sesión>`; responde `200 OK` con `{ "message": "Sesión cerrada." }`. **Stateless:** la invalidación del token es local del cliente (REQ-FUN-04 CA2); el backend solo confirma formalmente y registra el cierre en el log INFO con `idUsuario` (nunca el token). Sin body, sin BD, idempotente. Sin token / token de reseteo → 401 `UNAUTHORIZED`. |
| `GET /api/v1/progress/sync` | Snapshot autoritativo del progreso del usuario (Módulo G, CU-12/REQ-FUN-10/11/12). Requiere `Authorization: Bearer <token-sesión>`; responde `200 OK` con `{ "progreso": [...], "resumen": { "nivelesCompletados", "totalNiveles": 20, "totalReintentos" } }`. Solo agregados por nivel (`orden`, `estadoNivel`, `intentosTotales`, `intentosFallidosConsecutivos`, `completadoEn`); sin filas de `intento` ni pausas. Sin token / token de reseteo → 401 `UNAUTHORIZED`; cuenta eliminada → 403 `ACCOUNT_INACTIVE`. El backend no sirve el catálogo de trivia. |
| `POST /api/v1/progress/sync` | Sube el estado local acumulado y lo **mergea hacia adelante** (Módulo G, CU-12). Requiere `Authorization: Bearer <token-sesión>`; valida la forma (`progreso` obligatorio, `orden` 1..20, `estadoNivel` ∈ `BLOQUEADO/DISPONIBLE/COMPLETADO`, contadores ≥ 0, sin `orden` duplicado → 400 `VALIDATION_ERROR` con `details`) y la integridad (todo `orden` debe existir en `nivel` → 400 con **cero escrituras**). Persiste **atómicamente**, fija `completadoEn` una sola vez y responde `200 OK` con el snapshot **mergeado y persistido** (un round-trip). Sin token → 401 `UNAUTHORIZED`; cuenta eliminada → 403 `ACCOUNT_INACTIVE`. |
| `POST /api/v1/feedback/comments` | Envío de un comentario/sugerencia (Módulo H, CU-10/REQ-FUN-14). Requiere `Authorization: Bearer <token-sesión>`; body `{ "contenido": "..." }` (**solo** ese campo, máx. 2000 caracteres; el `id_usuario` proviene del token). Responde `200 OK` con `{ "message": "Comentario enviado con éxito." }`. `contenido` vacío o > 2000 → 400 `VALIDATION_ERROR` con `details`; claves desconocidas en el body (p. ej. `idUsuario`) → 400 `INVALID_REQUEST`; sin token / token de reseteo → 401 `UNAUTHORIZED`; cuenta eliminada → 403 `ACCOUNT_INACTIVE`. El contenido del comentario nunca se loguea. |
| `PUT /api/v1/users/me/avatar` | Sube/reemplaza la foto personalizada (Módulo I, REQ-FUN-06 CA4, CU-06 3a). Requiere `Authorization: Bearer <token-sesión>`; `multipart/form-data` con la parte `avatar` (binario, máx. 2 MB). Valida **dos veces** (controller lee el stream con RAM acotada; service re-verifica tamaño + **magic bytes** + concordancia MIME declarada) y solo acepta `jpeg`/`png`/`webp`. Persiste en `AVATAR_STORAGE_DIR` con clave `custom:<uuid>` (escritura atómica + sidecar de MIME), actualiza `usuario.avatar` en transacción y responde `200 OK` con `{ "message": "Avatar actualizado con éxito." }`. **Compensación:** si la BD falla tras escribir el archivo, este se elimina. Forma inválida → 400 `VALIDATION_ERROR` con `details`; sin token / token de reseteo → 401 `UNAUTHORIZED`; cuenta eliminada → 403 `ACCOUNT_INACTIVE`. |
| `GET /api/v1/users/me/avatar` | Sirve el binario de la foto personalizada (Módulo I, CU-06). Requiere `Authorization: Bearer <token-sesión>`; responde `200 OK` con la imagen (Content-Type real, `Cache-Control: private, no-store`, `X-Content-Type-Options: nosniff`, `Content-Disposition: attachment`). **404 `NOT_FOUND`** si el perfil usa avatar preestablecido (`preset:*`) o el archivo no existe. Sin URL pública: siempre requiere sesión. Sin token / token de reseteo → 401 `UNAUTHORIZED`; cuenta eliminada → 403 `ACCOUNT_INACTIVE`. |

Respuestas de error (formato estándar `ErrorDto`): `400` `VALIDATION_ERROR` (con
`details` por campo) / `INVALID_REQUEST`, `401` `INVALID_CREDENTIALS` /
`OTP_INVALID_OR_EXPIRED` / `RESET_TOKEN_INVALID` / `UNAUTHORIZED`, `403`
`ACCOUNT_INACTIVE`, `404` `NOT_FOUND`, `409` `EMAIL_ALREADY_REGISTERED`, `EMAIL_LOCKED`,
`PASSWORD_REUSED` o `CONFLICT`, `423` `ACCOUNT_LOCKED`, `429` `OTP_RESEND_THROTTLED` y
`500` `INTERNAL_ERROR`.
