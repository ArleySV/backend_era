# ERA — Instrucciones permanentes del backend

Este archivo es de lectura obligatoria para cualquier asistente de IA que trabaje en
este repositorio. Las reglas de este documento **prevalecen** sobre cualquier
comportamiento por defecto del asistente.

---

## 1. Qué es ERA

**ERA (Educación, Repaso y Aprendizaje)** es una app Android nativa de trivia
educativa para niños de básica primaria (**7 a 11 años**), compatible desde
**Android 8.0**. Su objetivo es redirigir el tiempo de ocio digital pasivo hacia
aprendizaje activo y seguro, con mediación parental.

### Actores del sistema

| Actor | Rol |
|---|---|
| **El menor de edad** | Usuario final que juega. |
| **El acudiente** | Responsable del registro y de autorizar la eliminación de la cuenta. |
| **El servidor / API** | Este backend (`BACKEND_ERA`). |

Este repositorio es **solo el backend**. El cliente Android (Kotlin, arquitectura en
capas con MVVM) no se desarrolla aquí, pero consume esta API vía REST.

---

## 2. Arquitectura y alcance cerrado

La arquitectura es **offline-first**: el cliente Android usa Room/SQLite para jugar y
consultar el FAQ sin conexión. **El backend NO sirve el contenido del juego.**

El backend expone **únicamente** estos seis grupos de funcionalidad:

1. Autenticación (registro, login, logout).
2. Verificación de correo por OTP.
3. Recuperación de contraseña.
4. Gestión y eliminación de cuenta.
5. Sincronización de progreso y comentarios.
6. Almacenamiento y servicio de la imagen de avatar personalizado (decisión explícita del
   propietario, 2026-08-05; amplía el alcance cerrado original, ver §8.1).

> Si un pedido implica un endpoint fuera de estos seis grupos (por ejemplo: servir
> preguntas, ranking en línea, FAQ remota), **señalarlo antes de escribir código**.

---

## 3. Stack técnico

- **Lenguaje:** Kotlin
- **Framework:** Ktor
- **Base de datos:** MySQL (administrada con MySQL Workbench)
- **Acceso a datos:** Exposed

### Sistema de build: Amper, NO Gradle

El proyecto fue generado con el Ktor Project Generator y usa **Amper**:

| Archivo | Propósito |
|---|---|
| `module.yaml` | Declaración del módulo y sus dependencias (`$ktor.server.core`, `$libs.<alias>`) |
| `libs.versions.toml` | Versiones de librerías externas |
| `kotlin` / `kotlin.bat` | Wrapper de build |

| Comando | Acción |
|---|---|
| `./kotlin build` | Compilar |
| `./kotlin run` | Levantar el servidor |
| `./kotlin test` | Ejecutar los tests |

> **Nunca crear archivos Gradle** (`build.gradle.kts`, `settings.gradle.kts`) ni usar
> `./gradlew`. Es un error frecuente por costumbre en proyectos Kotlin/Ktor.

---

## 4. Reglas de negocio aprobadas

Estas reglas están trazadas a requisitos formales (`REQ-*`, `CU-*`) de la
documentación entregada. **No son renegociables sin autorización explícita del
propietario del proyecto.**

| # | Regla | Requisito |
|---|---|---|
| 1 | **Cuenta única por correo:** un mismo correo no puede tener más de una cuenta activa. | REQ-FUN-01 |
| 2 | **Contraseña:** mínimo 8 caracteres, con mayúsculas, minúsculas, números y símbolos. No debe contener datos personales ni ser igual al username. Se almacena con **hashing de un solo sentido: bcrypt**. Queda expresamente descartado el cifrado reversible (AES) para contraseñas. | REQ-FUN-01, REQ-NF-02 |
| 3 | **OTP:** 6 dígitos numéricos, vigencia de 10 minutos, con opción de reenvío. Se usa tanto en la verificación de registro como en la recuperación de contraseña. | REQ-FUN-01, REQ-FUN-07 |
| 4 | **Login:** tras 5 intentos fallidos consecutivos, bloqueo temporal de 2 minutos. Los mensajes de error de credenciales **nunca** indican cuál campo falló. | REQ-FUN-02, REQ-NF-02 |
| 5 | **Recuperación de contraseña:** si el correo no existe se responde con un mensaje de error genérico (no se confirma ni se niega la existencia de la cuenta). La nueva contraseña no puede repetir la anterior. | REQ-FUN-07 |
| 6 | **Eliminación de cuenta:** es un **soft delete** por estado (inactivo/eliminado). Los datos **nunca** se borran físicamente. Una cuenta eliminada no puede volver a iniciar sesión y su correo queda bloqueado para nuevos registros hasta liberación administrativa. | REQ-FUN-05 |
| 7 | **Cierre de sesión:** invalida el token localmente; el progreso y los datos del usuario se conservan. | REQ-FUN-04 |
| 8 | **Sincronización:** sin conexión el cliente opera offline y pospone; si la sincronización falla, se reintenta en la próxima conexión disponible; los datos locales y remotos deben quedar consistentes al finalizar. | CU-12 |
| 9 | **Rendimiento:** respuesta a acciones del usuario en menos de 3 segundos. | REQ-NF-01 |
| 10 | **Disponibilidad:** al menos 95% del tiempo. | REQ-NF-04 |

---

## 5. Reglas permanentes de trabajo

1. **Nunca generar el proyecto completo de una sola vez.** Trabajar módulo por módulo,
   capa por capa.
2. **Nunca pedir ni procesar contraseñas, tokens o secretos reales.** Usar siempre
   placeholders: `<EMAIL_API_KEY>`, `<DB_PASSWORD>`.
3. **Antes de crear o modificar más de un archivo, presentar un plan y esperar
   confirmación explícita.**
4. **No agregar dependencias sin explicar para qué sirve cada una y sin aprobación
   previa.** En este repo eso significa editar `module.yaml` y `libs.versions.toml`.
5. **No ejecutar `git commit` ni `git push`.** Se puede *sugerir* el mensaje de commit;
   el propietario del proyecto lo ejecuta.
6. **Explicar el porqué de cada decisión de arquitectura, no solo el qué.**
7. **Priorizar siempre seguridad y validación de datos.** Este backend atiende a una
   app usada por niños, con datos de sus acudientes.
8. **Datos personales con rigor nivel autenticación.** Ver sección 6.
9. **Nunca implementar eliminación física.** Ver sección 7.
10. **Avisar antes de codificar si un pedido contradice una regla aprobada.** No asumir
    una reinterpretación silenciosa: plantear el conflicto en una o dos frases y
    esperar la decisión. Si no hay conflicto, implementar sin pedir confirmación extra.

---

## 6. Manejo de datos personales

Este backend maneja datos de **menores de edad y de sus acudientes**: nombres, fecha
de nacimiento, cédula del acudiente y correo electrónico.

Cualquier endpoint que exponga o modifique estos datos se trata con el **mismo nivel
de exigencia que la autenticación**:

- **Validación estricta de input** (formato, longitud, tipo) antes de tocar la base de
  datos.
- **Principio de mínimo privilegio:** cada endpoint devuelve solo los campos
  estrictamente necesarios.
- **Nunca loguear datos sensibles en texto plano:** correo, cédula, fecha de
  nacimiento, contraseñas, OTP ni tokens.
- Seguridad y validación por encima de conveniencia o brevedad del código.

**Por qué:** son datos de menores y documentos de identidad de adultos. Una fuga o un
log descuidado tiene consecuencias legales y de privacidad reales, no solo un bug.

---

## 7. Prohibición de eliminación física

**Nunca implementar `DELETE` físico** de usuarios ni de datos asociados. Toda
"eliminación" en este proyecto es un **soft delete por estado**, según REQ-FUN-05.

Si un prompt pide un `DELETE` real sobre la tabla `usuarios` u otra tabla con datos de
usuario, **advertirlo antes de generarlo** y esperar la decisión del propietario del
proyecto.

---

## 8. Documentación del proyecto

La documentación oficial completa (requisitos funcionales, no funcionales, casos de
uso e historias de usuario) vive en `docs/`. Este archivo contiene el resumen que debe
estar siempre en contexto; `docs/` es la fuente detallada que se consulta bajo demanda.

| Archivo | Contenido |
|---|---|
| [`docs/requisitos-funcionales.md`](docs/requisitos-funcionales.md) | REQ-FUN-01 … REQ-FUN-14 |
| [`docs/requisitos-no-funcionales.md`](docs/requisitos-no-funcionales.md) | REQ-NF-01 … REQ-NF-06 |
| [`docs/casos-de-uso.md`](docs/casos-de-uso.md) | CU-01 … CU-12 |
| [`docs/historias-de-usuario.md`](docs/historias-de-usuario.md) | HU-01 … HU-15 |

> **Instrucción obligatoria:** antes de implementar cualquier módulo, **leer el
> requisito, el caso de uso y la historia de usuario correspondientes** en `docs/`.
> No implementar a partir del resumen de la sección 4 de este archivo: ese resumen
> existe para detectar contradicciones, no para derivar el contrato de un endpoint.
> Los criterios de aceptación de `docs/` son la especificación vinculante.

### 8.1 Matriz de relevancia para el backend

De los 14 requisitos funcionales, **solo 7 generan endpoints**. El resto es lógica de
cliente Android o datos que llegan al backend únicamente a través de la sincronización
(CU-12).

| Requisito | Relevancia para el backend | Qué implica en el servidor |
|---|---|---|
| REQ-FUN-01 Registro | **Backend** | Alta de usuario, unicidad de correo, validación de contraseña, hash bcrypt, generación y envío de OTP |
| REQ-FUN-02 Login | **Backend** | Validación de credenciales, error genérico, contador de intentos y bloqueo de 2 min, emisión de token |
| REQ-FUN-03 Pantalla de carga | Solo cliente | Ninguno directo; impone el techo de 3 s de REQ-NF-01 a las respuestas |
| REQ-FUN-04 Logout | **Backend** | Invalidación del token; los datos se conservan |
| REQ-FUN-05 Eliminar cuenta | **Backend** | Reverificación de contraseña, soft delete por estado, bloqueo del correo |
| REQ-FUN-06 Cuenta del usuario | **Backend** | Lectura del perfil y actualización **restringida a `avatar` y `username`**; el resto de campos se ignora |
| REQ-FUN-07 Recuperación | **Backend** | OTP de recuperación, respuesta genérica ante correo inexistente, veto a repetir la contraseña anterior |
| REQ-FUN-08 Sidebar | Solo cliente | Ninguno |
| REQ-FUN-09 Pantalla principal | Solo cliente | Ninguno |
| REQ-FUN-10 Niveles de trivia | Cliente + sincronización | Persistencia del estado de los 20 niveles vía CU-12 |
| REQ-FUN-11 Cronómetro y juego | Cliente + sincronización | Persistencia del conteo de reintentos vía CU-12 |
| REQ-FUN-12 Progreso | Cliente + sincronización | El porcentaje se calcula en el cliente; el backend solo almacena niveles y reintentos |
| REQ-FUN-13 Ajustes | Solo cliente | Las preferencias se guardan **solo en el dispositivo**; "Sincronizar ahora" dispara CU-12 |
| REQ-FUN-14 FAQ y comentarios | Parcial | Las FAQ son locales y offline; **solo el envío de comentarios** llega al backend |

**Alcance del avatar (ampliado 2026-08-05 por decisión del propietario):** el avatar
personalizado cargado desde la galería del dispositivo **no se sincroniza** en el
sentido de CU-12 (la sincronización de progreso/comentarios no incluye imágenes). Sin
embargo, el backend **sí almacena y sirve** la imagen del avatar personalizado como
parte del Módulo I (ver §2 y `docs/ARQUITECTURA_BASE.md` §5.4 decisión 7): subida y
servido **post-verificación**, solo con sesión autenticada, hasta 2 MB, whitelist
`jpeg/png/webp` con doble validación (Content-Type + magic bytes) y retención ante soft
delete. Los avatares preestablecidos de la app (3 opciones) siguen siendo lógica local
del cliente (REQ-FUN-06).

### 8.2 Índice de trazabilidad REQ ↔ CU ↔ HU

| Requisito | Caso de uso | Historia de usuario |
|---|---|---|
| REQ-FUN-01 Registro | CU-01 Registrarse `<<include>>` CU-11 | HU-01, HU-15 |
| REQ-FUN-02 Login | CU-04 Iniciar sesión | HU-02 |
| REQ-FUN-03 Pantalla de carga | (referenciado por CU-04, paso 4) | HU-03 |
| REQ-FUN-04 Cierre de sesión | CU-05 Cerrar sesión | HU-04 |
| REQ-FUN-05 Eliminar cuenta | CU-07 Eliminar cuenta | HU-05 |
| REQ-FUN-06 Cuenta del usuario | CU-06 Editar cuenta | HU-06 |
| REQ-FUN-07 Recuperación | CU-03 Recuperar contraseña `<<include>>` CU-11 | HU-07 |
| REQ-FUN-08 Sidebar | (transversal a CU-05, CU-06, CU-08, CU-09, CU-10) | HU-08 |
| REQ-FUN-09 Pantalla principal | (punto de entrada tras CU-04) | HU-09 |
| REQ-FUN-10 Niveles de trivia | CU-02 Jugar nivel de trivia | HU-10 |
| REQ-FUN-11 Cronómetro y juego | CU-02 Jugar nivel de trivia | HU-11 |
| REQ-FUN-12 Progreso | CU-08 Consultar progreso | HU-12 |
| REQ-FUN-13 Ajustes | CU-09 Configurar ajustes `<<extend>>` CU-12 | HU-13 |
| REQ-FUN-14 FAQ y comentarios | CU-10 Consultar FAQ / enviar comentario | HU-14 |
| REQ-NF-01 Rendimiento | (transversal) | HU-03 |
| REQ-NF-02 Seguridad | CU-04, CU-07, CU-11 | HU-02, HU-15 |
| REQ-NF-03 Usabilidad | (transversal) | HU-13, HU-14 |
| REQ-NF-04 Confiabilidad | CU-12 Sincronizar datos | — |
| REQ-NF-05 Mantenibilidad | (transversal) | — |
| REQ-NF-06 Portabilidad | (transversal) | — |

**Casos de uso sin requisito funcional propio:**

| Caso de uso | Naturaleza | Relevancia para el backend |
|---|---|---|
| CU-11 Verificar código | `<<include>>` de CU-01 y CU-03 | **Backend** — actor principal es el servidor |
| CU-12 Sincronizar datos | `<<extend>>` de CU-09 y de los módulos con datos offline | **Backend** — actor principal es el servidor |

---

## 9. Estado actual del repositorio

**Esquema de base de datos**

- Migraciones Flyway en `resources/db/migration/`: `V1__init_schema.sql` (esquema
  aprobado en revisión conjunta, 12 tablas: `usuario`, `acudiente`,
  `registro_pendiente`, `codigo_verificacion`, `tokens_reseteo`, `configuracion`,
  `comentario`, `nivel`, `pregunta`, `opcion_respuesta`, `progreso_usuario`, `intento`),
  `V2__otp_resend_tracking.sql` (tracking de reenvíos de OTP) y
  `V3__codigo_verificacion_ultimo_envio.sql` (política de reenvío). Aplicadas al
  esquema real (`flyway_schema_history` con V1+V2+V3).
- `scripts/init_schema.sql`: DDL autocontenido para ejecución manual en MySQL
  Workbench; es `V1__init_schema.sql` más `CREATE DATABASE IF NOT EXISTS` y
  `USE era_db` iniciales (única diferencia).
- Motor InnoDB · utf8mb4 · FK con `ON DELETE RESTRICT` hacia `usuario` (segunda
  barrera contra borrado físico) · catálogo de trivia con `CASCADE` · soft delete
  vía `usuario.estado` · OTP hasheados con bcrypt.
- Base por defecto: `era_db` (ver `.env.example`). Si el esquema se crea a mano,
  Flyway lo toma como baseline (`baselineOnMigrate(true)`), nunca borra datos.
- Diccionario de datos en `docs/DICCIONARIO_DATOS.md`.

**Código**

- Proyecto Amper (no Gradle): `module.yaml` + `libs.versions.toml`, wrapper
  `kotlin`/`kotlin.bat`.
- `src/main/kotlin/com/era/backend/` con endpoints implementados de los Módulos
  A, A.1, B, C, D, E, F, G, H e I (ver detalle abajo):
  - `Application.kt` — wiring completo: `configureAuthentication(config.jwt)`
    antes de `routing {}`, `configurePlugins`, `userRoutes`, `authRoutes`,
    `progressRoutes` y `feedbackRoutes`; el Módulo I se inyecta por capas
    Repository → Storage → Service → Controller (`LocalDiskAvatarStorage`
    tipado como la interfaz `AvatarStorage`, nunca la implementación concreta).
  - `config/` (`AppConfig`, `AppConfigLoader` — con `StorageConfig` y fail-fast
    de `AVATAR_STORAGE_DIR`), `database/` (`DatabaseMigrator`, `MigrateRunner`),
    `plugins/` (`AuthenticationConfig`, `DatabaseFactory`, `StatusPagesConfig`).
  - `models/` (`SesionPrincipal`), `models/dto/` (15 DTOs: register, verify,
    resend, login, password-reset ×3, perfil, eliminar, mensaje, comentario, …),
    `models/entities/` (tablas Exposed, incluida `ComentarioTable`).
  - `exceptions/` (`CoreExceptions`, `DomainException`, `ErrorDto`,
    `ModuleExtensions`), `repositories/` (interfaces + impls Exposed, incluidos
    `ComentarioRepository`/`ExposedComentarioRepository`, + `TransactionRunner`),
    `services/` (`RegistrationService`, `OtpService`,
    `VerificationService`, `LoginService`, `PasswordResetService`,
    `JwtTokenService`, `UsuarioService`, `LogoutService`, `ComentarioService`,
    `AvatarService`, notificadores SMTP), `controllers/` (`AuthController`,
    `UsuarioController`, `ProgressController`, `FeedbackController`,
    `AvatarController`), `routes/` (`AuthRoutes`, `UserRoutes`,
    `ProgressRoutes`, `FeedbackRoutes`), `storage/` (`AvatarStorage` interfaz,
    `LocalDiskAvatarStorage` impl disco local, independiente: solo `Application.kt`
    conoce la implementación concreta), `utils/` (`Validators`, `PasswordPolicy`,
    `AvatarPreset`, `AvatarValidador`).
- **Módulos implementados y verificados** (tests automáticos + pruebas manuales):
  - **A (Registro):** `POST /api/v1/auth/register` — validaciones de forma (V4–V9)
    y negocio (V1–V3, política de contraseña), pendiente + OTP hasheado (10 min).
  - **A.1 (Verificación):** `POST /api/v1/auth/verify-email` y `resend-otp` —
    conversión transaccional, P1 (3 fallos invalidan), P2 (60 s), anti-enumeración.
  - **B (Login):** `POST /api/v1/auth/login` — JWT de sesión HS256 (30 días),
    bloqueo 2 min tras 5 fallos, error genérico anti-enumeración.
  - **C (Recuperación):** `password-reset/request|verify|confirm` — OTP + token
    puente JWT single-use (10 min), veto a repetir la contraseña anterior.
  - **D (Perfil):** `GET /api/v1/users/me` — mínimo privilegio (5 campos),
    protegido por el proveedor JWT `session-jwt` (`verifier` con audiencia
    `era-app-session` + `validate` que rechaza tokens de reseteo + `challenge`
    401 `UNAUTHORIZED`).
  - **E (Eliminación):** `DELETE /api/v1/users/me` — soft delete por estado con
    reverificación bcrypt fuera de transacción y guarda anti-carrera.
  - **F (Cierre de sesión):** `POST /api/v1/auth/logout` — **stateless**
    (ARQUITECTURA_BASE §5.4 #2): la invalidación del token es local del cliente;
    el backend solo confirma formalmente (200 `MensajeResponseDto`) y registra el
    cierre en el log INFO con `idUsuario` (nunca token/correo/cédula). Sin BD, sin
    blacklist, idempotente; único endpoint de `auth/*` protegido por `session-jwt`.
  - **G (Sincronización de progreso):** `GET`/`POST /api/v1/progress/sync` —
    CU-12/REQ-FUN-10/11/12, solo agregados por nivel (`estado_nivel`,
    `intentos_totales`, `intentos_fallidos_consecutivos`; sin filas de `intento`,
    sin pausas). **Merge hacia adelante** (`max` de estado por precedencia y de
    contadores; `completado_en` fijado una sola vez por el servidor), POST atómico
    vía `TransactionRunner` (400 `VALIDATION_ERROR` con **cero escrituras** si un
    `orden` no existe en `nivel`), `totalReintentos = SUM(intentos_totales)` y
    `nivelesCompletados` calculados **en el servidor**, `totalNiveles = 20`
    constante. POST responde el snapshot mergeado y persistido (un round-trip).
     403 `ACCOUNT_INACTIVE` en GET y POST; 401 `UNAUTHORIZED` sin sesión. El backend
     **no sirve el catálogo de trivia** (§2). Diseño aprobado en
     `docs/modulo-g-analisis.md`.
  - **H (Comentarios):** `POST /api/v1/feedback/comments` — CU-10/REQ-FUN-14 con
    **solo escritura** (`contenido`, máx. 2000 caracteres). El `id_usuario` se
    resuelve **siempre** del `SesionPrincipal` (nunca del body; claves desconocidas
    → 400 `INVALID_REQUEST`). Validación de forma en el controller (`isBlank()` y
    `length > 2000` → 400 `VALIDATION_ERROR` con `details`), `.trim()` antes de
    persistir, inserción dentro de `TransactionRunner`, confirmación 200
    `MensajeResponseDto`. Sin token / token de reseteo → 401 `UNAUTHORIZED`; cuenta
    eliminada → 403 `ACCOUNT_INACTIVE`. **Regla de oro:** el contenido del
    comentario nunca se loguea; la auditoría usa solo `idComentario` e `idUsuario`.
    Diseño aprobado en `docs/modulo-h-analisis.md`.
  - **I (Avatar personalizado):** `PUT`/`GET /api/v1/users/me/avatar` — subida y
    servido **post-verificación y solo con sesión autenticada** (misma barrera
    `session-jwt`; sin URL pública). PUT multipart (`avatar`, hasta 2 MB) con
    whitelist `jpeg/png/webp` y doble validación (magic bytes + Content-Type
    declarado), RAM acotada (fragmentos de 8 KB que abortan al superar el límite).
    Persistencia en disco local (`AVATAR_STORAGE_DIR`, fail-fast en la carga de
    config + creación del directorio en el `init`), clave `custom:<uuid>`,
    escritura atómica con sidecar de MIME, retención ante soft delete.
    **Compensación:** si `usuario.avatar` no se actualiza tras escribir el archivo,
    este se elimina. GET sirve el binario con `Cache-Control: private, no-store`,
    `nosniff` y `Content-Disposition`; 404 si no hay foto `custom:*`. Logs de
    auditoría con `idUsuario`, nunca la clave ni el path. Errores de forma → 400
    `VALIDATION_ERROR` con `details`; sin sesión / token de reseteo → 401
    `UNAUTHORIZED`; cuenta eliminada → 403 `ACCOUNT_INACTIVE`. Diseño aprobado en
    `docs/modulo-i-analisis.md`; **61 tests automáticos (Step 2) en verde**:
    `AvatarValidadorTest` (14), `LocalDiskAvatarStorageTest` (13),
    `AvatarServiceTest` (20) y `AvatarRoutesTest` (14).
- Dependencias declaradas: Ktor (server core/netty, content negotiation,
  kotlinx.json, auth JWT), Exposed (core/java.time/jdbc), HikariCP, logback,
  bcrypt, simpleJavaMail, Flyway (core + mysql), mysql-connector-j.
- Scripts auxiliares en `scripts/`: `dev.ps1` (recarga automática), `lint.ps1`
  (ktlint), `migrate.ps1` (Flyway), `smoke_test.ps1` (E2E register→verify),
  `password_reset_test.ps1` (E2E recuperación de contraseña) e
  `integration_test.ps1` (tests de integración contra `era_db_test`, con log de
  evidencia en `test-results/`).

**Tests**

- **265 tests automáticos** (`.\kotlin test`, 28 suites): service y route tests de
  registro, verificación, login, recuperación, cierre de sesión, perfil y eliminación
  de cuenta, sincronización de progreso y comentarios, **avatar personalizado (Módulo I)**,
  más manejo de errores y carga de configuración. Verificado con env vars placeholder de
  `.env.example` (`ConfigLoadTest` las exige). Conteo verificado con el runner completo
  (`.\kotlin test`, 2026-08-12): 265/265 en verde, 28 contenedores, 0 fallidos.
- **Tests de integración contra MySQL real** (`MySqlIntegrationTest` + `MySqlConcurrenciaTest`,
  6 tests): idempotencia de migraciones Flyway, constraint UNIQUE de correo, FK
  `ON DELETE RESTRICT` (soft delete como única vía de baja), rollback atómico de
  `verify-email`, unicidad anti-TOCTOU del registro y `FOR UPDATE` del login bajo
  concurrencia real. Corren sobre la base
  **`era_db_test`** (nunca `era_db`) vía `scripts/integration_test.ps1`, que valida el
  preflight (conexión activa = `era_db_test` + `flyway_schema_history` V1+V2+V3),
  escribe evidencia en `test-results/integration_*.log` (ignorado por git) y aborta
  con exit 1 si el preflight falla.
- **Pruebas E2E con servidor en ejecución** (`APP_DEV_MODE=true`, OTP fijo `123456`
  y SMTP No-Op): `smoke_test.ps1` (register→verify con persistencia en BD) y
  `password_reset_test.ps1` (flujo completo de recuperación).
- **Pruebas manuales en terminal verificadas** para D/E contra MySQL real: flujo
  completo register→verify→login→`GET /me`→`DELETE /me`, más los casos de error
  (401 sin token, 401 `INVALID_CREDENTIALS`, 400 `VALIDATION_ERROR`, 403
  `ACCOUNT_INACTIVE`).

**Próximos pasos (sugeridos):**
- Actualización de `username` (parte editable de REQ-FUN-06, junto al avatar).
