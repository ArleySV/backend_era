# ERA — Mapa del Repositorio

Guía de navegación del backend de **ERA (Educación, Repaso y Aprendizaje)**.
Objetivo: que una persona sin conocimiento previo entienda qué contiene cada carpeta y
cada archivo, y cómo se conectan entre sí.

> Lectura previa obligatoria: [`CLAUDE.md`](../CLAUDE.md) (reglas permanentes y alcance
> cerrado). Vista general y endpoints: [`README.md`](../README.md).

---

## 1. Cómo leer este mapa

- **Notación de trazabilidad:** `(Módulo X)` remite al análisis funcional en
  `docs/modulo-x-analisis.md`; `(REQ-FUN-xx / CU-xx / HU-xx)` remite a los requisitos,
  casos de uso e historias de usuario.
- **Convención del proyecto:** cada clase de `src/main/kotlin` lleva un KDoc de cabecera
  con el *porqué* de su diseño. Este mapa **enlaza** esos KDoc, no los repite.
- **Regla de oro:** el backend es *offline-first* y solo expone los seis grupos de
  funcionalidad del §2 de CLAUDE.md (Módulos A–I). No sirve el catálogo de trivia, ni
  FAQ, ni ranking en línea.

---

## 2. Árbol completo anotado

```
BACKEND_ERA/
│
├─ CLAUDE.md                       # Reglas permanentes y alcance cerrado (obligatorio)
├─ README.md                       # Vista general: stack, comandos, endpoints, estado
├─ module.yaml                     # Declaración del módulo Amper (dependencias, mainClass)
├─ libs.versions.toml              # Versiones de librerías externas
├─ kotlin                          # Wrapper de build (Unix)
├─ kotlin.bat                      # Wrapper de build (Windows)
├─ .env.example                    # Plantilla de env vars (placeholders, SÍ versionado)
├─ .env                            # Env real del desarrollador (NO versionado)
├─ .gitignore                      # build/, .env, .idea/, .tools/, test-results/…
├─ .gitattributes                  # * text=auto eol=lf
├─ .editorconfig                   # Estilo Kotlin acordado con ktlint
│
├─ docs/                           # Documentación oficial completa (§4.2)
│  ├─ ARQUITECTURA_BASE.md         # Arquitectura en capas y decisiones §5.4
│  ├─ requisitos-funcionales.md    # REQ-FUN-01 … 14
│  ├─ requisitos-no-funcionales.md # REQ-NF-01 … 06
│  ├─ casos-de-uso.md              # CU-01 … 12
│  ├─ historias-de-usuario.md      # HU-01 … 15
│  ├─ DICCIONARIO_DATOS.md         # Diccionario de datos del esquema
│  ├─ fase2-tests-mysql-diseno.md  # Diseño técnico de los tests MySQL/concurrencia
│  ├─ modulo-a-analisis.md         # Módulo A (Registro)
│  ├─ modulo-b-analisis.md         # Módulo B (Login)
│  ├─ modulo-c-analisis.md         # Módulo C (Recuperación de contraseña)
│  ├─ modulo-d-analisis.md         # Módulos D/E (Perfil, Edición, Eliminación)
│  ├─ modulo-f-analisis.md         # Módulo F (Cierre de sesión)
│  ├─ modulo-g-analisis.md         # Módulo G (Sincronización de progreso)
│  ├─ modulo-h-analisis.md         # Módulo H (Comentarios)
│  ├─ modulo-i-analisis.md         # Módulo I (Avatar personalizado)
│  └─ MAPA_DEL_REPOSITORIO.md      # Este documento
│
├─ resources/                      # Configuración y migraciones (§4.3)
│  ├─ application.yaml             # Config Ktor: ${VAR} resueltas de env
│  ├─ logback.xml                  # Logs a consola, nivel INFO
│  └─ db/migration/
│     ├─ V1__init_schema.sql       # Esquema aprobado (12 tablas)
│     ├─ V2__otp_resend_tracking.sql
│     ├─ V3__codigo_verificacion_ultimo_envio.sql
│     └─ .gitkeep
│
├─ scripts/                        # PowerShell de dev y QA (§4.4)
│  ├─ dev.ps1                      # Servidor dev con recarga automática
│  ├─ lint.ps1                     # ktlint (descarga el jar a .tools/)
│  ├─ migrate.ps1                  # Flyway sin levantar el servidor
│  ├─ init_schema.sql              # DDL autocontenido para MySQL Workbench
│  ├─ smoke_test.ps1               # E2E register→verify con persistencia real
│  ├─ password_reset_test.ps1      # E2E del flujo de recuperación
│  ├─ integration_test.ps1         # Tests de integración contra era_db_test
│  ├─ load_probe.ps1               # Sonda de carga REQ-NF-01 (gated por env)
│  └─ config_smoke.ps1             # Fail-fast de las 13 variables críticas
│
├─ src/main/kotlin/com/era/backend/   # Código de producción (§5)
│  ├─ Application.kt               # main + wiring completo (§5.1)
│  ├─ config/                      # AppConfig + loader con fail-fast
│  ├─ plugins/                     # AuthenticationConfig, StatusPages, DatabaseFactory
│  ├─ routes/                      # Contrato HTTP (4 archivos)
│  ├─ controllers/                 # Validación de forma (5 archivos)
│  ├─ services/                    # Reglas de negocio (13 archivos)
│  ├─ repositories/                # 9 interfaces + 9 impls Exposed + TransactionRunner
│  ├─ models/
│  │  ├─ SesionPrincipal.kt        # Principio de mínimo privilegio
│  │  ├─ dto/                      # 23 contratos JSON (§5.9)
│  │  └─ entities/                 # 9 tablas Exposed + rows + enums (§5.8)
│  ├─ exceptions/                  # DomainException, ErrorDto, catálogo
│  ├─ storage/                     # AvatarStorage + LocalDiskAvatarStorage
│  ├─ database/                    # DatabaseMigrator (Flyway) + MigrateRunner
│  └─ utils/                       # Validators, PasswordPolicy, AvatarValidador, AvatarPreset
│
└─ test/                           # Suite completa (§6)
   ├─ ConfigLoadTest.kt            # Carga de config real
   └─ com/era/backend/
      ├─ JsonAssertions.kt         # Helper de aserciones JSON
      ├─ config/ConfigMissingVarTest.kt   # Fail-fast de config
      ├─ db/                       # MySqlTestPool + Integration + Concurrencia
      ├─ load/LoadProbeTest.kt     # Sonda de carga (ERA_LOAD_PROBE=true)
      ├─ repositories/Fake*.kt     # 9 fakes en memoria de las interfaces
      ├─ routes/                   # 10 suites de endpoints
      ├─ services/                 # 10 suites de negocio + FakeOtpNotifier
      ├─ storage/                  # FakeAvatarStorage + LocalDiskAvatarStorageTest
      └─ utils/AvatarValidadorTest.kt
```

---

## 3. Flujo de una petición

```
Cliente Android (Room + MVVM)
   │  POST /api/v1/... + Authorization: Bearer <JWT de sesión>
   ▼
Routing  (AuthRoutes | UserRoutes | ProgressRoutes | FeedbackRoutes)
   │  path + verbo; sin lógica de negocio. Registra la ruta en el proveedor session-jwt
   ▼
AuthenticationConfig  (proveedor "session-jwt", JWT HS256)
   │  verifica firma + aud "era-app-session"
   │  rechaza tokens de reseteo ("era-app-reset", purpose PASSWORD_RESET)
   │  sin token / inválido → challenge 401 UNAUTHORIZED
   ▼
Controller  (primera barrera: valida la FORMA del input → 400 VALIDATION_ERROR)
   │  convierte DTO JSON (kotlinx) en datos tipados; resuelve idUsuario de SesionPrincipal
   ▼
Service  (reglas de NEGOCIO; sin Ktor, sin SQL)
   │  valida contra estado persistido → lanza DomainException ante conflicto
   ▼
Repository (interfaz) → impl Exposed + TransactionRunner
   │  única frontera con la BD; transacciones atómicas
   ▼
MySQL  era_db (Flyway V1–V3, InnoDB, utf8mb4)
   │
   ▼ (retorno)
Service → Controller → 200 + DTO / binario (avatar)
   │
   └─ ante DomainException → StatusPagesConfig (ÚNICO traductor dominio→HTTP)
        → ErrorDto estándar: 400/401/403/404/409/423/429/500
```

**Reglas de trazado:**
- `routes/` **no** conoce `repositories/`; `controllers/` **no** conoce `repositories/`;
  `services/` no conoce Ktor ni SQL. Las dependencias se inyectan desde
  `Application.kt` por constructor (interfaces, nunca implementaciones concretas; salvo
  la excepción documentada del Módulo I, donde `LocalDiskAvatarStorage` se tipa como
  `AvatarStorage`).
- `StatusPagesConfig` es el único traductor de excepciones de dominio a HTTP.
- El JWT de sesión no se valida contra BD: es *stateless* (30 días). El estado de cuenta
  (activa/eliminada) sí se consulta en el service cuando el negocio lo exige.

---

## 4. Diccionario por capa de archivos

### 4.1 Raíz

| Archivo | Qué es | Por qué existe |
|---|---|---|
| `module.yaml` | Declaración del módulo Amper: dependencias `$ktor.*` / `$libs.*`, `mainClass: com.era.backend.ApplicationKt` | Build **Amper, no Gradle**. Nunca crear `build.gradle.kts` ni usar `./gradlew`. |
| `libs.versions.toml` | Versiones de librerías externas | Centraliza versiones para `module.yaml`. |
| `kotlin` / `kotlin.bat` | Wrapper de build (Amper) | `./kotlin build`, `./kotlin run`, `./kotlin test`. |
| `.env.example` | Plantilla de env vars con placeholders (`<EMAIL_API_KEY>`, `<DB_PASSWORD>`) | Se versiona a propósito; `.env` real nunca. |
| `.editorconfig` | Reglas de estilo (ktlint 1.8.0) | Consistencia de formato. |

### 4.2 `docs/`

Documentación oficial completa. Ver tabla en [`README.md`](../README.md) → *Documentación
del proyecto* y el índice de trazabilidad REQ↔CU↔HU en CLAUDE.md §8.2. Los
`modulo-*-analisis.md` son la **especificación vinculante** de cada endpoint.

### 4.3 `resources/`

| Archivo | Contenido |
|---|---|
| `application.yaml` | Config Ktor. Variables `\${DB_*}` (host, puerto, base, usuario, contraseña), `\${JWT_SECRET}`, `\${SMTP_*}`, `\${AVATAR_STORAGE_DIR}`, `\${PORT}`; issuer/audience/expiración del JWT. |
| `logback.xml` | Logs a consola, nivel INFO. `jdbc:sqlserver` evita URLs en logs. |
| `db/migration/V1__init_schema.sql` | Esquema aprobado en revisión conjunta: **12 tablas** (`usuario`, `acudiente`, `registro_pendiente`, `codigo_verificacion`, `tokens_reseteo`, `configuracion`, `comentario`, `nivel`, `pregunta`, `opcion_respuesta`, `progreso_usuario`, `intento`). FK `ON DELETE RESTRICT` hacia `usuario`, catálogo de trivia con `CASCADE`. |
| `V2__otp_resend_tracking.sql` | Tracking de reenvíos de OTP (columna `ultimo_envio_en` en `registro_pendiente`). |
| `V3__codigo_verificacion_ultimo_envio.sql` | Política de reenvío para OTP de recuperación (`codigo_verificacion.ultimo_envio_en`). |

### 4.4 `scripts/`

| Script | Acción |
|---|---|
| `dev.ps1` | Levanta el servidor dev con recarga automática. |
| `lint.ps1` | Ejecuta ktlint (descarga el jar a `.tools/`, no versionado). |
| `migrate.ps1` | Flyway sin levantar el servidor. |
| `init_schema.sql` | `V1__init_schema.sql` + `CREATE DATABASE IF NOT EXISTS` + `USE era_db` (única diferencia). Para MySQL Workbench. |
| `smoke_test.ps1` | E2E register→verify con persistencia real (requiere `APP_DEV_MODE=true`). |
| `password_reset_test.ps1` | E2E del flujo completo de recuperación. |
| `integration_test.ps1` | Tests de integración contra `era_db_test` (preflight + evidencia en `test-results/`). |
| `load_probe.ps1` | Sonda de carga REQ-NF-01 (hard-assert p95 < 3000 ms). |
| `config_smoke.ps1` | Fail-fast de las 13 variables críticas + `PORT` que `application.yaml` requiere. |

---

## 5. Código de producción (`src/main/kotlin/com/era/backend/`)

### 5.1 `Application.kt` — wiring central

`main` → `embeddedServer(Netty, port = PORT)` → `module()`. En `module()`:
`configureAuthentication(config.jwt)` **antes** de `routing {}`, `configurePlugins`
(ContentNegotiation, StatusPages, DatabaseFactory), luego `userRoutes`, `authRoutes`,
`progressRoutes` y `feedbackRoutes`. El Módulo I se inyecta por capas
Repository → Storage → Service → Controller. **Todo el grafo de dependencias vive aquí.**

### 5.2 `config/`

| Archivo | Qué es |
|---|---|
| `AppConfig.kt` | Data classes de configuración (database, jwt, mail, storage, devMode). |
| `AppConfigLoader.kt` | Carga desde `application.yaml` con **fail-fast**: `JWT_SECRET` vacía o `AVATAR_STORAGE_DIR` ausente abortan el arranque (defensa en profundidad). |

### 5.3 `plugins/`

| Archivo | Qué es |
|---|---|
| `AuthenticationConfig.kt` | Instala el proveedor JWT **`session-jwt`**: `verifier` con audiencia `era-app-session` + `validate` que **rechaza tokens de reseteo** (`era-app-reset`, `purpose: PASSWORD_RESET`) + `challenge` → 401 `UNAUTHORIZED`. |
| `StatusPagesConfig.kt` | **Único traductor dominio→HTTP.** Convierte `DomainException` en `ErrorDto`; 400 genérico para entrada malformada y 500 genérico sin detalles. |
| `DatabaseFactory.kt` | Pool Hikari único, compartido por Flyway y Exposed. |

### 5.4 `routes/` (contrato HTTP, 4 archivos)

| Archivo | Rutas |
|---|---|
| `AuthRoutes.kt` | `/api/v1/auth/register`, `verify-email`, `resend-otp`, `login`, `logout` (único protegido de `auth/*`), `password-reset/request|verify|confirm`. |
| `UserRoutes.kt` | `/api/v1/users/me` (GET, PATCH), `DELETE /users/me`, `PUT|GET /users/me/avatar`. Todo protegido por `session-jwt`. |
| `ProgressRoutes.kt` | `GET|POST /api/v1/progress/sync`. Protegido. |
| `FeedbackRoutes.kt` | `POST /api/v1/feedback/comments`. Protegido. |

### 5.5 `controllers/` (validación de forma, 5 archivos)

| Archivo | Responsabilidad |
|---|---|
| `AuthController.kt` | register, verifyEmail, resendOtp, login, password-reset ×3, logout. |
| `UsuarioController.kt` | obtenerPerfil, actualizarPerfil (solo `nombreUsuario`), eliminarCuenta. |
| `ProgressController.kt` | getSync, postSync. |
| `FeedbackController.kt` | enviarComentario (valida `isBlank()` y `length > 2000` → 400 con `details`). |
| `AvatarController.kt` | subirAvatar (multipart, RAM acotada 8 KB, aborta > 2 MB), obtenerAvatar. |

**Contrato del layer:** valida forma (tipos, longitudes, formato) → 400 `VALIDATION_ERROR`;
resuelve el `idUsuario` del `SesionPrincipal` (nunca del body); delega en el service.

### 5.6 `services/` (reglas de negocio, 13 archivos)

| Archivo | Módulo | Responsabilidad |
|---|---|---|
| `RegistrationService.kt` | A | Unicidad de correo/username (V1–V3), política de contraseña, pendiente + OTP hash, transacción anti-TOCTOU. |
| `OtpService.kt` | A/C | OTP 6 dígitos, hash bcrypt, vigencia 10 min, P1 (3 fallos invalidan), modo determinista en dev (`APP_DEV_MODE`). |
| `OtpNotifier.kt` (interfaz) | A/C | Abstracción del envío de correo. |
| `SimpleJavaMailOtpNotifier.kt` | A/C | Envío SMTP real; No-Op en dev. |
| `VerificationService.kt` | A.1 | Conversión transaccional `registro_pendiente` → `usuario` + `acudiente` + `configuracion`, P2 (60 s entre reenvíos), anti-enumeración. |
| `LoginService.kt` | B | bcrypt (coste 11), bloqueo 2 min tras 5 fallos, error genérico anti-enumeración, `HASH_DUMMY` para paridad de timing (B-4), soft delete solo tras contraseña correcta. |
| `JwtTokenService.kt` | B/C | Emisión de JWT: sesión (30 días, `era-app-session`) y reseteo (10 min single-use, `era-app-reset`). |
| `PasswordResetService.kt` | C | 3 pasos, respuesta genérica ante correo inexistente (C-1), throttle de reenvío, token puente single-use, veto a repetir contraseña anterior. |
| `LogoutService.kt` | F | Stateless: confirma formalmente + log INFO con `idUsuario` (nunca token/correo/cédula). Sin BD. |
| `UsuarioService.kt` | D/E | Perfil mínimo privilegio (5 campos), actualizar `username` con unicidad (409), soft delete con reverificación. |
| `ProgressSyncService.kt` | G | Merge hacia adelante (`max` por precedencia de estado y contadores), atomicidad, validación contra catálogo `nivel`, resumen calculado en el servidor. |
| `ComentarioService.kt` | H | Solo escritura, `.trim()`, nunca loguea contenido (auditoría con `idComentario`/`idUsuario`). |
| `AvatarService.kt` | I | Magic bytes + MIME (doble validación), límite 2 MB, ciclo de vida con compensación (si `usuario.avatar` no se actualiza, el archivo se elimina), retención ante soft delete. |

### 5.7 `repositories/` (frontera con la BD)

- **9 interfaces:** `UsuarioRepository`, `RegistroPendienteRepository`, `AcudienteRepository`,
  `ConfiguracionRepository`, `CodigoVerificacionRepository`, `TokensReseteoRepository`,
  `NivelRepository`, `ProgresoRepository`, `ComentarioRepository`.
- **9 implementaciones `Exposed*Repository`:** SQL tipado con Exposed. Detalles clave:
  `ExposedUsuarioRepository` (login `FOR UPDATE`, unicidad, soft delete),
  `ExposedProgressRepository` (agregados, merge concurrente con fila única),
  `ExposedNivelRepository` (resolución `orden ↔ id_nivel`).
- **`TransactionRunner.kt`:** abstracción de transacciones; `ExposedTransactionRunner` =
  `transaction {}`. Los flujos que exigen atomicidad (registro, conversión, merge de
  progreso) corren dentro de un único runner.

> Nota: el esquema SQL tiene 12 tablas, pero Exposed mapea **9** (`usuario`, `acudiente`,
> `registro_pendiente`, `codigo_verificacion`, `tokens_reseteo`, `configuracion`,
> `comentario`, `nivel`, `progreso_usuario`). `pregunta`, `opcion_respuesta` e `intento`
> pertenecen al catálogo/registro local de juego (offline-first): el backend no las sirve
> ni las persiste.

### 5.8 `models/entities/` — las 9 tablas Exposed, una por una

| Tabla (object Exposed) | Propósito | Notas |
|---|---|---|
| `UsuarioTable` | Cuenta del menor: datos, hash bcrypt, estado y bloqueo de login. | `correo` y `nombre_usuario` **UNIQUE**; `estado` ENUM `ACTIVO`/`ELIMINADO` (soft delete, REQ-FUN-05); `avatar` nullable (`preset:N` o `custom:<uuid>`); `intentos_login_fallidos` + `bloqueado_hasta` (regla de 5 fallos → 2 min). |
| `AcudienteTable` | Datos del acudiente (1:1 con `usuario`). | `id_usuario` UNIQUE; `numero_cedula` = dato personal sensible. |
| `RegistroPendienteTable` | Alta temporal previa a verificación (REQ-FUN-01). | `correo`/`nombre_usuario` UNIQUE; `codigo_hash` (bcrypt), `intentos_fallidos`, `expira_en`, `ultimo_envio_en` (V2). La fila se **convierte** en `usuario`+`acudiente`+`configuracion` al verificar. |
| `CodigoVerificacionTable` | OTP de recuperación de contraseña (Módulo C). | `codigo_hash`, `intentos_fallidos`, `expira_en`, `ultimo_envio_en` (V3), `usado`. |
| `TokensReseteoTable` | Tokens puente single-use de recuperación. | `jti` UNIQUE; se marca `consumido` al usarlo (anti-replay). |
| `ConfiguracionTable` | Preferencias persistidas (1:1). | `sonido`, `musica`, `tema_visual`, `tamano_texto`. Se crea con valores por defecto en la conversión de registro. |
| `ComentarioTable` | Comentarios enviados por el menor (REQ-FUN-14/CU-10). | Solo escritura; `contenido` TEXT (máx. 2000). |
| `NivelTable` | Catálogo de 20 niveles (REQ-FUN-10). | `orden` 1..20 UNIQUE; única tabla de catálogo que el backend consulta (validación del merge). |
| `ProgresoUsuarioTable` | Estado de progreso por nivel (REQ-FUN-12, CU-12). | `estado_nivel` ENUM con precedencia `BLOQUEADO < DISPONIBLE < COMPLETADO`; `completado_en` fijado una sola vez por el servidor; `ultima_interaccion`. |

Enums en el mismo paquete: `EstadoUsuario` (`ACTIVO`/`ELIMINADO`), `EstadoNivel`
(`BLOQUEADO`/`DISPONIBLE`/`COMPLETADO`). Rows (`UsuarioRow`, `AcudienteRow`, …) tipan los
resultados de Exposed.

### 5.9 `models/dto/` — los 23 DTOs, uno por uno

**Módulo A — Registro**

| # | DTO | Tipo | Endpoint | Campos |
|---|---|---|---|---|
| 1 | `RegisterRequestDto` | entrada | `POST /auth/register` | `nombreMenor` (≤120), `fechaNacimiento` (ISO, no futura), `nombreAcudiente` (≤120), `cedulaAcudiente` (6–20), `correo` (email, lowercase), `nombreUsuario` (3–60 sin espacios), `avatar` (opcional, `1\|2\|3`), `contrasena` + `confirmarContrasena` (política CA2). |
| 2 | `RegisterResponseDto` | salida | `POST /auth/register` | `message` (instrucción de verificar). |

**Módulo A.1 — Verificación**

| # | DTO | Tipo | Endpoint | Campos |
|---|---|---|---|---|
| 3 | `VerifyEmailRequestDto` | entrada | `POST /auth/verify-email` | `correo`, `codigo` (6 dígitos). |
| 4 | `VerifyEmailResponseDto` | salida | `POST /auth/verify-email` | `message` (activación correcta). |
| 5 | `ResendOtpRequestDto` | entrada | `POST /auth/resend-otp` | `correo`. |
| 6 | `ResendOtpResponseDto` | salida | `POST /auth/resend-otp` | `message` idéntico al inicial (anti-enumeración). |

**Módulo B — Login**

| # | DTO | Tipo | Endpoint | Campos |
|---|---|---|---|---|
| 7 | `LoginRequestDto` | entrada | `POST /auth/login` | `usuarioOCorreo` (≤255), `contrasena` (≤72). |
| 8 | `LoginResponseDto` | salida | `POST /auth/login` | `token` (JWT de sesión, 30 días). |

**Módulo C — Recuperación**

| # | DTO | Tipo | Endpoint | Campos |
|---|---|---|---|---|
| 9 | `PasswordResetRequestDto` | entrada | `POST /auth/password-reset/request` | `correo`. |
| 10 | `PasswordResetResponseDto` | salida | request y confirm | `message` (genérico: no confirma la existencia). |
| 11 | `PasswordResetVerifyRequestDto` | entrada | `POST /auth/password-reset/verify` | `correo`, `codigo`. |
| 12 | `PasswordResetVerifyResponseDto` | salida | `POST /auth/password-reset/verify` | `resetToken` (JWT puente, 10 min single-use). |
| 13 | `PasswordResetConfirmRequestDto` | entrada | `POST /auth/password-reset/confirm` | `resetToken`, `nuevaContrasena` + `confirmarContrasena`. |

**Módulos D/E — Perfil y eliminación**

| # | DTO | Tipo | Endpoint | Campos |
|---|---|---|---|---|
| 14 | `UsuarioPerfilDto` | salida | `GET\|PATCH /users/me` | `nombreMenor`, `fechaNacimiento`, `correo`, `nombreUsuario`, `avatar` (**5 campos**, mínimo privilegio). |
| 15 | `ActualizarUsuarioRequestDto` | entrada | `PATCH /users/me` | `nombreUsuario` (único editable; claves desconocidas → 400). |
| 16 | `EliminarCuentaRequestDto` | entrada | `DELETE /users/me` | `contrasena` (reverificación CA2, fuera de transacción). |

**Módulo F — Logout / genéricos**

| # | DTO | Tipo | Endpoint | Campos |
|---|---|---|---|---|
| 17 | `MensajeResponseDto` | salida | logout, DELETE /me, avatar PUT, comments | `message`. |

**Módulo G — Progreso (CU-12)**

| # | DTO | Tipo | Endpoint | Campos |
|---|---|---|---|---|
| 18 | `ProgresoSyncItemDto` | entrada | `POST /progress/sync` (elemento) | `orden` (1..20), `estadoNivel` (literal), `intentosTotales`, `intentosFallidosConsecutivos`. |
| 19 | `ProgresoSyncRequestDto` | entrada | `POST /progress/sync` | `progreso: List<ProgresoSyncItemDto>?` (nullable). |
| 20 | `NivelProgresoDto` | salida | GET y POST sync (elemento) | `orden`, `estadoNivel`, `intentosTotales`, `completadoEn?`, `ultimaInteraccion`. |
| 21 | `ResumenProgresoDto` | salida | GET y POST sync | `nivelesCompletados`, `totalNiveles` (= 20), `totalReintentos`. |
| 22 | `ProgresoSyncResponseDto` | salida | GET y POST sync | `progreso` + `resumen` (snapshot mergeado y persistido). |

**Módulo H — Comentarios**

| # | DTO | Tipo | Endpoint | Campos |
|---|---|---|---|---|
| 23 | `ComentarioRequestDto` | entrada | `POST /feedback/comments` | `contenido` (≤2000). |

### 5.10 `exceptions/`

| Archivo | Qué es |
|---|---|
| `DomainException.kt` | Base abstracta: `status` HTTP + `errorCode`. |
| `ErrorDto.kt` | Formato estándar de error (`error`, `message`, `details`, `timestamp`). |
| `CoreExceptions.kt` | Catálogo común: `Validation`, `InvalidCredentials`, `OtpInvalid`, `ResetTokenInvalid`, `AccountInactive`, `NotFound`, `Conflict`, `AccountLocked`. |
| `ModuleExtensions.kt` | Extensiones por módulo: `GuardianNotFound`, `EmailAlreadyRegistered`, `EmailLocked`, `SyncConflict`, `PasswordReuse`, `OtpResendThrottled`, `EmailAlreadyVerified`. |

### 5.11 `storage/` (Módulo I)

| Archivo | Qué es |
|---|---|
| `AvatarStorage.kt` | Interfaz: guardar/leer/eliminar + `ContenidoAvatar` + `AvatarStorageException`. |
| `LocalDiskAvatarStorage.kt` | Disco local: clave opaca base64url (`custom:<uuid>`), escritura atómica, sidecar de MIME, anti path-traversal. Solo `Application.kt` conoce la implementación concreta. |

### 5.12 `database/`

| Archivo | Qué es |
|---|---|
| `DatabaseMigrator.kt` | Flyway con `baselineOnMigrate(true)` reutilizando el pool Hikari; nunca borra datos. |
| `MigrateRunner.kt` | `main` standalone para migrar sin levantar el servidor (usado por `scripts/migrate.ps1`). |

### 5.13 `utils/`

| Archivo | Qué es |
|---|---|
| `Validators.kt` | Formato de correo, username, cédula y fecha de nacimiento. |
| `PasswordPolicy.kt` | Política de contraseña compartida (≥8, mayúscula, minúscula, número, símbolo; ≠ username; sin datos personales). |
| `AvatarValidador.kt` | Magic bytes JPEG/PNG/WebP + límite 2 MB (doble validación con Content-Type). |
| `AvatarPreset.kt` | Avatares preestablecidos (`preset:1\|2\|3`). |

### 5.14 `models/SesionPrincipal.kt`

Porta **solo** `idUsuario` (principio de mínimo privilegio). Se construye al validar el
JWT de sesión y se usa para resolver el usuario dueño de cada operación.

---

## 6. Tests — cómo leer la suite

```
test/
├─ ConfigLoadTest.kt                       # Carga real de application.yaml (exige env vars)
└─ com/era/backend/
   ├─ JsonAssertions.kt                    # Helper de comparación JSON
   ├─ config/ConfigMissingVarTest.kt       # Fail-fast: JWT_SECRET vacía, AVATAR_STORAGE_DIR ausente
   ├─ db/MySqlTestPool.kt                  # Pool para tests de integración (era_db_test)
   ├─ db/MySqlIntegrationTest.kt           # 3: idempotencia Flyway, UNIQUE correo, FK RESTRICT
   ├─ db/MySqlConcurrenciaTest.kt          # 6: TOCTOU registro, FOR UPDATE login, merge paralelo
   ├─ load/LoadProbeTest.kt                # Sonda REQ-NF-01 (skipped salvo ERA_LOAD_PROBE=true)
   ├─ repositories/Fake*.kt                # 9 fakes en memoria de las interfaces de repositorio
   ├─ routes/                              # 10 suites de endpoints (validan capa HTTP completa)
   ├─ services/                            # 10 suites de negocio + FakeOtpNotifier
   ├─ storage/                             # FakeAvatarStorage + LocalDiskAvatarStorageTest
   └─ utils/AvatarValidadorTest.kt
```

- **Conteo actual:** 283 unitarios en verde en el runner normal + 9 de integración MySQL
  + 1 sonda de carga + 1 de config = **294 tests** (verificado con `.\kotlin test`,
  2026-08-14).
- **¿Por qué fakes?** Los tests de services/routes usan `Fake*Repository` en memoria para
  correr sin MySQL y sin red; los tests de integración (`era_db_test`) validan el
  comportamiento real de la BD (constraints, `FOR UPDATE`, rollback atómico).
- **Gate de la sonda de carga:** solo corre con `ERA_LOAD_PROBE=true` vía
  `scripts/load_probe.ps1` (preflight + seed de 500 usuarios + evidencia en
  `test-results/`).

---

## 7. Mapa de módulos A–I y sus artefactos

| Módulo | Funcionalidad | Analysis | Routes | Controller | Service(s) | Repositories | DTOs |
|---|---|---|---|---|---|---|---|
| A | Registro | `modulo-a` | `AuthRoutes` | `AuthController` | `RegistrationService`, `OtpService`, `OtpNotifier` | `RegistroPendienteRepository`, `UsuarioRepository` | 1, 2 |
| A.1 | Verificación OTP | `modulo-a` | `AuthRoutes` | `AuthController` | `VerificationService`, `OtpService` | `RegistroPendienteRepository`, `UsuarioRepository`, `AcudienteRepository`, `ConfiguracionRepository` | 3–6 |
| B | Login | `modulo-b` | `AuthRoutes` | `AuthController` | `LoginService`, `JwtTokenService` | `UsuarioRepository` | 7, 8 |
| C | Recuperación | `modulo-c` | `AuthRoutes` | `AuthController` | `PasswordResetService`, `OtpService`, `JwtTokenService` | `CodigoVerificacionRepository`, `TokensReseteoRepository`, `UsuarioRepository` | 9–13 |
| D | Perfil + username | `modulo-d` | `UserRoutes` | `UsuarioController` | `UsuarioService` | `UsuarioRepository`, `RegistroPendienteRepository` | 14, 15 |
| E | Eliminación | `modulo-d` | `UserRoutes` | `UsuarioController` | `UsuarioService` | `UsuarioRepository` | 16, 17 |
| F | Logout | `modulo-f` | `AuthRoutes` | `AuthController` | `LogoutService` | — (sin BD) | 17 |
| G | Progreso (CU-12) | `modulo-g` | `ProgressRoutes` | `ProgressController` | `ProgressSyncService` | `NivelRepository`, `ProgresoRepository` | 18–22 |
| H | Comentarios | `modulo-h` | `FeedbackRoutes` | `FeedbackController` | `ComentarioService` | `ComentarioRepository` | 23 |
| I | Avatar | `modulo-i` | `UserRoutes` | `AvatarController` | `AvatarService` | `UsuarioRepository`, `AvatarStorage` | 17 |

---

## 8. Endpoints de la API (resumen)

| Método | Path | Protegido `session-jwt` | Descripción |
|---|---|---|---|
| `POST` | `/api/v1/auth/register` | no | Alta pendiente + OTP (Módulo A) |
| `POST` | `/api/v1/auth/verify-email` | no | Verificación OTP (A.1) |
| `POST` | `/api/v1/auth/resend-otp` | no | Reenvío OTP con throttle 60 s (A.1) |
| `POST` | `/api/v1/auth/login` | no | Sesión JWT (B) |
| `POST` | `/api/v1/auth/password-reset/request` | no | Solicitud de recuperación (C) |
| `POST` | `/api/v1/auth/password-reset/verify` | no | Verifica OTP → token puente (C) |
| `POST` | `/api/v1/auth/password-reset/confirm` | no | Nueva contraseña (C) |
| `POST` | `/api/v1/auth/logout` | **sí** | Confirmación stateless (F) |
| `GET` | `/api/v1/users/me` | **sí** | Perfil, 5 campos (D) |
| `PATCH` | `/api/v1/users/me` | **sí** | Actualizar `nombreUsuario` (D) |
| `DELETE` | `/api/v1/users/me` | **sí** | Soft delete con reverificación (E) |
| `PUT` | `/api/v1/users/me/avatar` | **sí** | Subir avatar personalizado ≤2 MB (I) |
| `GET` | `/api/v1/users/me/avatar` | **sí** | Servir avatar (binario, no pública) (I) |
| `GET` | `/api/v1/progress/sync` | **sí** | Snapshot de progreso (G) |
| `POST` | `/api/v1/progress/sync` | **sí** | Merge hacia adelante, atómico (G) |
| `POST` | `/api/v1/feedback/comments` | **sí** | Enviar comentario (H) |

---

## 9. Reglas duras para quien llega nuevo

1. **Build = Amper.** `./kotlin build|run|test`. Nunca Gradle.
2. **Sin `DELETE` físico.** Toda baja es soft delete por `usuario.estado` (REQ-FUN-05);
   FK `ON DELETE RESTRICT` lo refuerzan.
3. **Errores = `ErrorDto` por código `error`, no por mensaje.** Mapear códigos, no textos.
4. **Nunca loguear datos personales:** correo, cédula, fecha de nacimiento, contraseñas,
   OTP, tokens ni contenido de comentarios.
5. **Contraseñas y OTP siempre bcrypt.** Nunca reversible.
6. **Capas estrictas:** routes → controllers (forma) → services (negocio) →
   repositories (BD). Solo `StatusPagesConfig` traduce excepciones a HTTP.
7. **El `idUsuario` siempre se resuelve de `SesionPrincipal`**, nunca del body.
8. **Añadir dependencias** requiere explicar el porqué y editar `module.yaml` +
   `libs.versions.toml` (y aprobación del propietario).
9. **Documentación vinculante:** antes de tocar un módulo, leer su
   `docs/modulo-*-analisis.md` y el requisito/caso de uso/historia asociados.
10. **No commits ni pushes** por parte del asistente: solo sugerir el mensaje.
