# ERA — Arquitectura Base del Backend

> Documento de referencia del equipo. Describe la arquitectura en capas de `BACKEND_ERA`
> antes de implementar cualquier módulo (A…H). Las reglas permanentes, el alcance cerrado
> y la trazabilidad REQ/CU/HU viven en [`CLAUDE.md`](../CLAUDE.md); los detalles de cada
> módulo se derivan de `docs/` (requisitos, casos de uso, historias de usuario).
>
> **Regla de construcción:** ningún módulo se implementa completo de una sola vez; cada
> iteración trabaja capa por capa (routes → controllers → services → repositories →
> models) y se documenta antes de codificar.

## 0. Nota sobre el gestor de build: Amper, no Gradle

Este proyecto **usa Amper** (`module.yaml` + `libs.versions.toml`, wrapper `kotlin.bat`),
**no Gradle**. No existen `build.gradle.kts` ni `settings.gradle.kts`, y el comando
`./gradlew` no aplica.

Cualquier prompt futuro que mencione `build.gradle.kts`, `./gradlew` o dependencias en
estilo Gradle **debe reinterpretarse para Amper**:

| Concepto Gradle | Equivalente en Amper |
|---|---|
| `build.gradle.kts` (bloque `dependencies`) | `module.yaml` (bloque `dependencies:`) |
| `libs.versions.toml` (catálogo) | `libs.versions.toml` (mismo archivo) |
| `implementation("io.ktor:…:x.y.z")` | `- $ktor.<feature>` (catálogo de Ktor de Amper) |
| `implementation("org.foo:bar:x.y.z")` | `- $libs.<alias>` (alias en `libs.versions.toml`) |
| `./gradlew build/run/test` | `./kotlin build` / `./kotlin run` / `./kotlin test` |

Esta nota no reescribe ni reinterpreta prompts anteriores: queda aquí para que se tenga
en cuenta de aquí en adelante al proponer dependencias o comandos.

---

## Índice de módulos y endpoints

| Módulo | Requisitos / CU / HU | Endpoints |
|---|---|---|
| **A** Registro | REQ-FUN-01, CU-01, HU-01, HU-15 | `POST /api/v1/auth/register` |
| **A.1** Verificación de correo (OTP) | REQ-FUN-01 (paso 3), CU-11 | `POST /api/v1/auth/verify-email`, `POST /api/v1/auth/resend-otp` |
| **B** Login | REQ-FUN-02, REQ-NF-02, CU-04, HU-02 | `POST /api/v1/auth/login` |
| **C** Recuperación de contraseña | REQ-FUN-07, CU-03, HU-07 | `POST /api/v1/auth/password-reset/request`, `…/verify`, `…/confirm` |
| **D** Cuenta | REQ-FUN-06, CU-06, HU-06 | `GET /api/v1/users/me`, `PATCH /api/v1/users/me` |
| **E** Eliminación de cuenta | REQ-FUN-05, CU-07, HU-05 | `DELETE /api/v1/users/me` *(soft delete, requiere contraseña)* |
| **F** Cierre de sesión | REQ-FUN-04, CU-05, HU-04 | `POST /api/v1/auth/logout` |
| **G** Sincronización de progreso | REQ-FUN-12, REQ-FUN-10/11, CU-08, CU-12 | `GET /api/v1/progress/sync`, `POST /api/v1/progress/sync` |
| **H** Comentarios | REQ-FUN-14, CU-10, HU-14 | `POST /api/v1/feedback/comments` |

> Nota sobre **E**: el verbo HTTP es `DELETE` por convención REST, pero la
> implementación es **siempre soft delete** por estado (`estado = inactivo`), según
> REQ-FUN-05. Nunca se ejecuta eliminación física (CLAUDE.md §7).

---

## 1. Estructura de carpetas propuesta

Paquete base: `com.era.backend` (el actual `com.example` se renombra al primer
commit de estructura). Dependencias entre capas: **siempre hacia abajo**
(routes → controllers → services → repositories → models); nunca al revés.

```
src/main/kotlin/com/era/backend/
├── Application.kt              # Punto de entrada (EngineMain) + función módulo Ktor
├── config/                     # Carga y tipado de configuración (application.yaml):
│                               #   puerto, DB (URL/usuario/placeholder), JWT (secreto
│                               #   <JWT_SECRET>), SMTP (<EMAIL_API_KEY>). Sin literales reales.
├── plugins/                    # Instalación de plugins Ktor transversales:
│                               #   ContentNegotiation, Authentication (JWT), StatusPages,
│                               #   CallLogging, DatabaseFactory (Hikari/Exposed init)
├── routes/                     # Definición de endpoints: path + verbo + delegación al
│                               #   controller. Sin lógica de negocio ni SQL.
├── controllers/                # Recepción de request, validación básica de input,
│                               #   orquestación del service y mapeo de respuesta.
├── services/                   # Reglas de negocio (la parte "reutilizable" y testeable).
├── repositories/               # Acceso a datos vía Exposed/JDBC. Aísla el SQL.
├── models/
│   ├── entities/               # Tablas Exposed + filas (mapeo 1:1 con el diccionario de datos).
│   └── dto/                    # Contratos request/response. Únicos objetos que cruzan la API.
├── exceptions/                 # Excepciones de dominio (ver sección 5.3).
└── utils/                      # Helpers sin estado: validadores (correo, contraseña,
                                #   cédula, fecha), generador de OTP (SecureRandom),
                                #   mappers Entity↔DTO.
```

```
src/test/kotlin/com/era/backend/
├── services/                   # Tests de reglas de negocio (sin MySQL, mock de repositorio)
└── routes/                     # Tests de endpoints con Ktor TestHost
```

---

## 2. Descripción de cada capa

### 2.1 Routing
**Declara** el contrato público: `route("/api/v1/auth")`, verbos HTTP, paths y, como
máximo, la deserialización al DTO y la llamada al método del controller.
**NO contiene** validaciones de negocio, llamadas a repositorios, ni la construcción de
entidades. Es la "tabla de contenidos" de la API: se lee de un vistazo y se modifica
solo cuando cambia el contrato.

### 2.2 Controller / Handler
**Valida input básico** antes de tocar cualquier otra capa: formato de correo, longitud,
tipo, enum válido, cuerpo presente. Es la primera barrera del principio de mínimo
privilegio (CLAUDE.md §6): un input malformado no debe llegar a base de datos.
**Delega** en el service las reglas de negocio y mapea el resultado a DTO de respuesta.
**NO decide** políticas de negocio (vigencia, bloqueos, genericidad de errores).

### 2.3 Service
**Aquí viven las reglas de negocio**, trazadas a REQ/CU/HU:

- Unicidad de correo (REQ-FUN-01).
- Contraseña: mínimo 8 caracteres, mayúsculas/minúsculas/números/símbolos, no igual al
  username (REQ-FUN-01). Hash bcrypt de un solo sentido.
- OTP: 6 dígitos, vigencia 10 min, single-use, reenvío (REQ-FUN-01, REQ-FUN-07).
- Login: 5 intentos fallidos → bloqueo 2 min; error de credenciales **genérico**
  (REQ-FUN-02, REQ-NF-02).
- Recuperación: correo inexistente → respuesta genérica; veto a repetir contraseña
  anterior (REQ-FUN-07).
- **Token puente de reseteo (Módulo C):** `POST …/password-reset/verify` consume el OTP
  (single-use, esto no cambia) y **además emite un token de reseteo** de corta vida
  (~10 min), de un solo uso y con `purpose = PASSWORD_RESET`, que se devuelve al cliente
  en la respuesta. El siguiente paso `POST …/password-reset/confirm` recibe **ese token +
  la nueva contraseña**, valida el token (nunca vuelve a tocar el OTP), actualiza la
  contraseña y consume el token. *Por qué:* sin este token puente, `/confirm` no tendría
  cómo autorizar la actualización tras el OTP consumido en `/verify`; el token cierra el
  ciclo de forma segura y sin estado de sesión en el servidor.
- Soft delete por estado (REQ-FUN-05).
- Sin sesión en el servidor: el logout invalida el token del cliente (REQ-FUN-04).

El service es **puro de Ktor y de SQL**: recibe DTOs y devuelve resultados tipados.
Eso permite testear las reglas con repositorios simulados, sin levantar MySQL.

### 2.4 Repository
**Aísla el acceso a datos** mediante Exposed (DSL tipado) sobre el driver JDBC MySQL.
Cada repositorio encapsula: transacciones, queries y mapeo a entidades.
El service **nunca ve SQL**; el repositorio **nunca ve reglas de negocio**. Si mañana
cambia el motor o el ORM, solo se toca esta capa. `DatabaseFactory` (plugin) crea el
pool (HikariCP) y registra las tablas una sola vez.

### 2.5 DTO vs Entity — por qué nunca se exponen entidades
- **Entity**: mapeo 1:1 con la fila (`usuarios`, `codigos_verificacion`, `intentos`…).
  Contiene campos internos que jamás deben salir por la API: `contrasena_hash`, intentos
  fallidos, `cedula`, timestamps internos.
- **DTO**: contrato público del endpoint; expone **solo** lo que el cliente necesita
  (principio de mínimo privilegio).

Exponer una entidad directamente filtra campos sensibles y acopla el esquema de la DB
al contrato de la API. La conversión vive en `utils/` (mappers) y es explícita.

---

## 3. Plugins de Ktor transversales

| Plugin | Responsabilidad | Detalle |
|---|---|---|
| **ContentNegotiation** (+ kotlinx.serialization) | Leer/escribir cuerpos JSON | Deserializa el request al DTO de entrada y serializa la respuesta. Registrado una vez en `plugins/`. |
| **Authentication (JWT)** | Proteger rutas autenticadas | Firma HS256 con secreto desde configuración (`<JWT_SECRET>`). Se aplica a D, E, F, G y H; **no** a A, A.1, B, C (pre-autenticación). Logout invalida localmente el token (REQ-FUN-04). |
| **StatusPages** | Manejo centralizado de errores | Convierte excepciones de dominio en un único formato de error (sección 5.2). Evita respuestas inconsistentes y filtra stack traces. |
| **CallLogging** | Logging de requests | Registra `método, path, status, duración`. |

### 3.1 CallLogging — qué se loguea y qué nunca
**Sí se loguea:** método HTTP, path, status code, duración, ID de request.

**Nunca se loguea (ni en texto plano ni cifrado):**
- contraseñas y `contrasena_hash`,
- códigos **OTP**,
- tokens JWT / Authorization,
- cédula del acudiente,
- correo electrónico,
- fecha de nacimiento.

Regla de CLAUDE.md §6: son datos de menores y documentos de adultos; un log descuidado
es una fuga real. El cuerpo del request/response se descarta del log por defecto.

---

## 4. Flujo de una request típica

Ejemplo ilustrativo: **`POST /api/v1/auth/password-reset/verify`** (Módulo C, reutiliza
la verificación OTP de A.1). Solo ilustra la arquitectura; no es la implementación.

```
 Cliente Android
      │  HTTP POST /api/v1/auth/password-reset/verify
      │  JSON: {"email": "…", "code": "123456"}
      ▼
┌─ Ktor Plugins ─────────────────────────────────────────────────┐
│ CallLogging      → registra método/path/status, NUNCA el cuerpo │
│ StatusPages      → intercepta excepciones que salten más abajo  │
│ ContentNegotiation → deserializa JSON a OtpVerifyRequestDto     │
└────────────────────────┬────────────────────────────────────────┘
                         ▼
┌─ Routing ──────────────────────────────────────────────────────┐
│ route("/api/v1/auth/password-reset") { post("/verify") {      │
│   authController.verifyOtp(call) } }                          │
└────────────────────────┬────────────────────────────────────────┘
                         ▼
┌─ Controller ──────────────────────────────────────────────────┐
│ Valida input básico: formato de correo, code = 6 dígitos,     │
│ purpose = PASSWORD_RESET. Si falla → 400 (StatusPages).       │
│ Delega en PasswordResetService.verify(request)                 │
└────────────────────────┬────────────────────────────────────────┘
                         ▼
┌─ Service ─────────────────────────────────────────────────────┐
│ Reglas de negocio (CU-03 / CU-11):                            │
│  1. Buscar registro OTP en codigos_verificacion por correo +  │
│     purpose no consumido.                                     │
│  2. No existe / incorrecto / vencido → excepción de dominio   │
│     con mensaje GENÉRICO (no confirma si el correo existe).   │
│  3. Verificar bcrypt(code, code_hash).                        │
│  4. Vigencia ≤ 10 min.                                        │
│  5. Consumir el OTP (single-use, consumed_at).                │
│  6. EMITIR token de reseteo (corta vida ~10 min, single-use,  │
│     purpose=PASSWORD_RESET) — ver nota de arquitectura.       │
└────────────────────────┬────────────────────────────────────────┘
                         ▼
┌─ Repository ──────────────────────────────────────────────────┐
│ Exposed/JDBC en transacción:                                  │
│  SELECT * FROM codigos_verificacion                           │
│    WHERE correo = ? AND purpose = ? AND consumed_at IS NULL   │
│  UPDATE codigos_verificacion SET consumed_at=NOW() WHERE id=? │
│  Registrar jti del token emitido (single-use)                 │
│    — esquema en "Decisiones pendientes".                      │
│ Devuelve entidades; nunca SQL hacia arriba.                   │
└────────────────────────┬────────────────────────────────────────┘
                         ▼
                       MySQL  (MySQL Connector/J + HikariCP pool)
                         │
                         ▼
┌─ Respuesta ───────────────────────────────────────────────────┐
│ Controller mapea a OtpVerifyResponseDto que INCLUYE el        │
│ reset_token (corta vida) → 200 OK. NO es solo un mensaje      │
│ genérico de éxito: el token es lo que autoriza /confirm.      │
│ Si saltó excepción → StatusPages devuelve ErrorDto genérico.  │
└───────────────────────────────────────────────────────────────┘
```

**Nota de arquitectura — token de reseteo:** se emite como **JWT** firmado con el mismo
secreto del plugin Authentication (`purpose=PASSWORD_RESET`, `sub = id_usuario`,
`exp ≈ 10 min`, `jti` único). El single-use se garantiza registrando el `jti` consumido.
`POST …/password-reset/confirm` (no ilustrado en detalle) recibe `reset_token` + nueva
contraseña → valida el token (no vuelve a tocar `codigos_verificacion`) → valida
contraseña (REQ-FUN-07: ≥8, mayúsculas/minúsculas/números/símbolos; no repetir la
anterior) → actualiza `contrasena_hash` (bcrypt) → consume el token.

Este JWT es de propósito específico y vida corta (reset token), **no** el mismo mecanismo
del plugin Authentication(JWT) que protege D, E, F, G y H. Se valida explícitamente
dentro del controller/service de `/confirm`, no mediante el plugin de autenticación
global — `/confirm` sigue siendo una ruta pre-sesión, solo que ahora exige el
`reset_token` en vez del OTP.

---

## 5. Convenciones obligatorias para cualquier módulo futuro

### 5.1 Nomenclatura de rutas
- Prefijo común: **`/api/v1`** (versionado desde el inicio).
- `auth/*` para identidad: registro, verificación, login, logout, recuperación.
- `users/*` para datos de cuenta (autenticado).
- `progress/*` para sincronización (autenticado).
- `feedback/*` para comentarios (autenticado).
- Verbos REST estándar: `POST` (crear/acción), `GET` (leer), `PATCH` (editar parcial),
  `DELETE` (soft delete por estado).
- Identificadores en el path solo cuando el recurso no sea "el usuario de la sesión"
  (`/users/me` en lugar de `/users/{id}`).

### 5.2 Formato estándar de respuesta de error
Toda respuesta de error (generada por StatusPages) tiene la misma forma:

```json
{
  "timestamp": "2026-08-02T12:00:00Z",
  "status": 401,
  "error": "OTP_INVALID_OR_EXPIRED",
  "message": "Código inválido o vencido.",
  "path": "/api/v1/auth/password-reset/verify"
}
```

- `error`: código máquina (UPPER_SNAKE_CASE), trazable en tests.
- `message`: mensaje seguro para el usuario. Las reglas que exigen respuesta **genérica**
  (credenciales, correo inexistente) se cumplen aquí: el mensaje **no** revela qué campo
  falló ni si una cuenta existe.
- Nunca se incluyen stack traces, detalles internos ni datos sensibles.

### 5.3 Dónde vive cada tipo de excepción de dominio
Todas en `com.era.backend.exceptions`, subclases de una base `DomainException` que
transporta `status` y `errorCode`:

| Excepción | Status | Uso |
|---|---|---|
| `ValidationException` | 400 | Input válido en forma pero inválido en regla (contraseña débil, OTP malformado). |
| `ConflictException` | 409 | Correo o username ya en uso (REQ-FUN-01). |
| `InvalidCredentialsException` | 401 | Login fallido → se mapea a mensaje **genérico**. |
| `AccountLockedException` | 423 | Bloqueo de 2 min tras 5 intentos fallidos. |
| `AccountInactiveException` | 403 | Cuenta en soft delete intenta loguearse. |
| `OtpInvalidException` | 401 | OTP incorrecto o vencido → mensaje genérico. |
| `ResetTokenInvalidException` | 401 | Token de reseteo inválido, expirado (~10 min) o ya usado (single-use) → mensaje genérico; nunca indica a qué correo pertenece. |
| `NotFoundException` | 404 | Recurso inexistente; solo se usa donde es seguro informar. |

> El **mapeo** excepción → `ErrorDto` vive en el plugin StatusPages, único lugar que
> traduce dominio a HTTP. Los controllers nunca capturan para reformatear; dejan que
> StatusPages haga su trabajo (consistencia garantizada).

### 5.4 Decisiones de arquitectura

**Resueltas (aprobadas en esta iteración):**

1. **Config de entorno — se mantiene `resources/application.yaml`, NO se migra a HOCON
   `application.conf`.** Sustitución de variables vía `${VAR}` (sin el símbolo `?`;
   comportamiento verificado en `ktor-server-config-yaml` 3.4.3: un valor que empieza por
   `${` se resuelve primero con `System.getProperty` y luego con `System.getenv`). **No se
   toca `module.yaml`, no se borra ningún archivo y no se agrega dependencia nueva.**
   Variables gestionadas: `PORT`, `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`,
   `DB_PASSWORD`, `JWT_SECRET`, `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD`,
   `SMTP_FROM`.
   **`env.example`:** el `.env.example` del repo es **solo referencia** de qué variables
   deben existir en el entorno (como system property o como variable de entorno). La JVM
   **no** carga `.env` automáticamente; las variables se definen en el run configuration
   del IDE o en el shell. El `JWT_SECRET` es **un único secreto compartido** para los dos
   propósitos (sesión y reset token); la diferenciación vive en los claims
   (`audience`: `era-app-session` vs `era-app-reset`), no en el secreto.

2. **Duración de sesión — Opción A aprobada.** Token de sesión JWT de vida larga,
   `exp = 30 días` (`jwt.session.expirationMinutes = 43200`), **sin refresh token**.
   Logout = invalidación local del token (REQ-FUN-04): no hay infraestructura de sesiones
   server-side en el diccionario oficial. Cumple HU-02 CA1 (sesión persistente si no hubo
   logout explícito).
   **RIESGO ACEPTADO (decisión consciente, no implícita):** si el dispositivo del menor
   se pierde o es robado, el token de sesión sigue siendo válido hasta 30 días **sin que
   el backend tenga forma de revocarlo**, porque el logout solo invalida el token
   localmente. Se acepta este trade-off dado el perfil de riesgo del proyecto (app
   educativa infantil, sin datos financieros ni backend de sesiones). Queda registrado
   para que el equipo lo reconsidere si el perfil de riesgo cambia (p. ej. si se agregan
   datos más sensibles en el futuro).

**Pendientes de aprobación:**

3. **Tabla de OTP (`codigos_verificacion`) — PROPUESTA, aún no aprobada.** El diccionario
   de datos oficial no define ninguna tabla para códigos OTP (es un vacío del diccionario,
   igual que ya se marcó en el mapa de módulos). Este documento la nombra
   `codigos_verificacion` (snake_case en español, consistente con `usuarios`, `acudientes`,
   `progreso_usuario`, `ajustes_usuario`, `comentarios`) **solo para unificar la
   referencia**; el esquema final y su aprobación quedan pendientes.
4. **Límite de intentos fallidos de verificación de OTP — sin resolver.** Ningún REQ-FUN lo
   exige explícitamente, pero un código de 6 dígitos es vulnerable a fuerza bruta dentro de
   la ventana de 10 minutos. Debe resolverse (p. ej. N intentos fallidos → invalidar el
   código y exigir reenvío) **antes de implementar el Módulo C**.
5. **Almacenamiento del token puente de reseteo — PROPUESTA.** El `jti` consumido para
   garantizar single-use necesita persistencia; dónde vive (tabla nueva o columna) se
   decidirá junto con el esquema de `codigos_verificacion`.

---

Este documento es la referencia previa a la implementación. Cuando se apruebe la
construcción, se trabaja módulo por módulo (A → A.1 → B → C → D/E/F → G → H) y, para
cada uno, se leen antes los REQ/CU/HU correspondientes en `docs/`.
