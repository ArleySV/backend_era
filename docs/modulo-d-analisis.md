# ERA — Módulo D/E (Perfil y Eliminación de cuenta) — Análisis funcional

> Documentación oficial del sistema ERA. Ver [`../CLAUDE.md`](../CLAUDE.md) para las reglas
> permanentes y la matriz de trazabilidad.
>
> **Base normativa:** [`requisitos-funcionales.md`](./requisitos-funcionales.md)
> (REQ-FUN-05, REQ-FUN-06), [`requisitos-no-funcionales.md`](./requisitos-no-funcionales.md)
> (REQ-NF-02), [`casos-de-uso.md`](./casos-de-uso.md) (CU-06, CU-07),
> [`historias-de-usuario.md`](./historias-de-usuario.md) (HU-05, HU-06),
> [`ARQUITECTURA_BASE.md`](./ARQUITECTURA_BASE.md) (§2.2, §2.3, §3, §5.3, §5.4) y
> [`DICCIONARIO_DATOS.md`](./DICCIONARIO_DATOS.md).
>
> Este documento registra el **análisis funcional aprobado** de los Módulos D (Consulta de
> perfil, REQ-FUN-06) y E (Eliminación de cuenta, REQ-FUN-05) y su **diseño técnico por
> capas** (`ARQUITECTURA_BASE.md` §3). Las decisiones D-1…D-8 de §8 son vinculantes; D-6,
> D-7 y D-8 fueron **ratificadas por el Auditor** (visto bueno al diseño de los Pasos 1 y 2).

---

## 1. Alcance y actores

**Alcance:** dos endpoints, ambos protegidos por **JWT de sesión** (Módulo B):

| Endpoint | Función |
|---|---|
| `GET /api/v1/users/me` | Consulta del perfil del usuario autenticado (Módulo D, REQ-FUN-06). |
| `DELETE /api/v1/users/me` | Eliminación (soft delete) de la propia cuenta, con reverificación de contraseña (Módulo E, REQ-FUN-05). |

**Actor principal:** el menor de edad autenticado (con mediación del acudiente), que ya
inició sesión (CU-04 / Módulo B). Ambos endpoints son **de sesión**: el recurso sobre el que
actúan es el propio usuario autenticado (`/me`), nunca otro usuario.

**Precondición:** el cliente posee un JWT de sesión válido y vigente (login, Módulo B).

**Flujo (CU-06 / CU-07):**
1. El usuario entra a "Mi cuenta" (sidebar, REQ-FUN-08) y consulta su perfil (`GET /me`).
2. Para eliminar la cuenta, el usuario solicita la eliminación, el sistema **reverifica su
   contraseña** (REQ-FUN-05 CA2) y, si es correcta, pasa la cuenta a `eliminado` (soft
   delete, REQ-FUN-05).
3. La cuenta eliminada no puede volver a iniciar sesión y su correo queda bloqueado para
   nuevos registros hasta liberación administrativa (REQ-FUN-05, regla 6 de CLAUDE.md §4).

**Postcondición (del flujo de E):** `usuario.estado = 'eliminado'`. **Nunca** se borra
físicamente ninguna fila (CLAUDE.md §7); los datos de `acudiente`, `configuracion`,
`codigo_verificacion`, `tokens_reseteo`, `progreso_usuario` e `intento` permanecen intactos.

---

## 2. Autenticación de sesión — infraestructura (Paso 1)

Ambos endpoints se registran dentro de un bloque `authenticate("session-jwt")`; sin token
válido no se puede llegar al controller. La infraestructura se compone de:

### 2.1 `SesionPrincipal` — `src/main/kotlin/com/era/backend/models/SesionPrincipal.kt`

```kotlin
data class SesionPrincipal(val idUsuario: Long)
```

Principal mínimo (mínimo privilegio): **solo** el `id_usuario`. No porta correo, ni
username, ni claims; el perfil se lee de la BD en cada petición (evita un token obsoleto
como fuente de verdad y reduce la superficie expuesta). Se construye con
`credential.payload.subject?.toLongOrNull()`; si el `subject` no es un Long → rechazo
(`validate` devuelve `null`).

### 2.2 `configureAuthentication(jwtConfig)` — `src/main/kotlin/com/era/backend/plugins/AuthenticationConfig.kt`

Nueva función `fun Application.configureAuthentication(jwtConfig: JwtConfig)` que instala el
proveedor JWT con nombre **`session-jwt`**:

- **`verifier`** (barrera principal): `JWT.require(Algorithm.HMAC256(secret))`
  `.withAudience(jwtConfig.sessionAudience)` `.build()`. Los tokens de **reseteo** usan la
  audiencia `era-app-reset` (Módulo C) → fallan aquí con `AudienceClaimException` (401)
  **antes** de llegar a `validate`.
- **`validate`** (barrera de refuerzo, defensa en profundidad): rechaza con `null` si el
  payload **contiene el claim `purpose`** (los de sesión nunca lo llevan; los de reseteo
  llevan `purpose = PASSWORD_RESET`). Luego construye `SesionPrincipal(subject.toLongOrNull())`;
  si `subject` es nulo o no numérico → `null` (rechazo).
- **`challenge`**: responde 401 `UNAUTHORIZED` (D-6) con el cuerpo estándar `ErrorDto`. Sin
  `challenge`, el 401 del verifier sale sin cuerpo y rompe el contrato de errores §5.2 de
  ARQUITECTURA_BASE (el verifier falla antes de que `StatusPages` traduzca `DomainException`).

### 2.3 Punto de instalación

En `Application.module()`, **inmediatamente después** de `loadAppConfig()` y **siempre antes**
de `routing {}` (un `authenticate` sobre un proveedor no instalado lanza en arranque):

```kotlin
fun Application.module() {
    val config = loadAppConfig()
    configureAuthentication(config.jwt)   // Paso 1
    configurePlugins(config)
    routing { ... }                        // Paso 5: userRoutes dentro de authenticate("session-jwt")
}
```

**Sin cambios de dependencias:** `module.yaml` ya incluye `$ktor.server.auth.jwt` y
`$ktor.server.auth` (verificado); no se edita `module.yaml` ni `libs.versions.toml`.

---

## 3. Contrato de `GET /api/v1/users/me`

Ruta: `/api/v1/users/me` (nomenclatura §5.1 de ARQUITECTURA_BASE).

### 3.1 Request

Sin body. Autenticación vía header `Authorization: Bearer <token-sesion>` (JWT HS256,
`aud = era-app-session`).

### 3.2 Response de éxito — 200 OK — `UsuarioPerfilDto`

| Campo | Tipo | Fuente (tabla `usuario`) |
|---|---|---|
| `nombreMenor` | String | `nombre_menor` |
| `fechaNacimiento` | String ISO `yyyy-MM-dd` | `fecha_nacimiento` (D-8) |
| `correo` | String | `correo` |
| `nombreUsuario` | String | `nombre_usuario` |
| `avatar` | String? | `avatar` (NULL = preset del cliente, DICCIONARIO_DATOS) |

```json
{
  "nombreMenor": "Laura Pérez",
  "fechaNacimiento": "2018-04-12",
  "correo": "laura.perez@example.com",
  "nombreUsuario": "laura2026",
  "avatar": "preset:1"
}
```

**Mínimo privilegio (D-4):** el perfil expone **solo** estos 5 campos. **Nunca** se devuelve
la cédula del acudiente, el nombre del acudiente, el `contrasena_hash`, contadores de
intentos ni ningún claim del token (CLAUDE.md §6).

### 3.3 Códigos de estado (formato `ErrorDto` de §5.2 de ARQUITECTURA_BASE)

| Status | `error` | Cuándo |
|---|---|---|
| 200 | — | Éxito: perfil del usuario autenticado |
| 401 | `UNAUTHORIZED` | Token ausente, malformado, expirado, de audiencia `era-app-reset`, o con `purpose` (challenge del plugin, D-6) |
| 403 | `ACCOUNT_INACTIVE` | Cuenta en `eliminado` (D-1) |
| 404 | `NOT_FOUND` | Defensivo: token válido pero fila inexistente (solo posible por inconsistencia de datos) |
| 500 | `INTERNAL_ERROR` | Error inesperado; stack trace solo en servidor |

---

## 4. Contrato de `DELETE /api/v1/users/me`

Ruta: `/api/v1/users/me` (misma ruta, método `DELETE`).

### 4.1 Request — `EliminarCuentaRequestDto`

| Campo | Tipo | Presencia | Regla |
|---|---|---|---|
| `contrasena` | String | obligatorio | no blanco; ≤ 72 (tope técnico de bcrypt); **no** se valida política aquí — el único propósito es la reverificación (REQ-FUN-05 CA2) |

La reverificación de contraseña es el requisito explícito de REQ-FUN-05 CA2 ("el sistema
vuelve a pedir la contraseña antes de eliminar"): una sesión JWT válida **no es suficiente**
para la destrucción de la cuenta; se exige demostrar de nuevo la credencial.

### 4.2 Response de éxito — 200 OK — `MensajeResponseDto`

```json
{ "message": "Cuenta eliminada. Tus datos se conservan." }
```

`MensajeResponseDto` es un DTO genérico reutilizable (`message`) compartido con el resto de
módulos; no expone ningún dato personal (D-5).

### 4.3 Códigos de estado (formato `ErrorDto` de §5.2 de ARQUITECTURA_BASE)

| Status | `error` | Cuándo |
|---|---|---|
| 200 | — | Éxito: cuenta pasada a `eliminado` (soft delete) |
| 400 | `VALIDATION_ERROR` | Falla de forma del controller (`contrasena` no blanco / > 72), con `details` |
| 400 | `INVALID_REQUEST` | JSON malformado o body ausente (lo produce StatusPages) |
| 401 | `UNAUTHORIZED` | Token ausente/malformado/expirado/de audiencia reseteo o con `purpose` (challenge) |
| 401 | `INVALID_CREDENTIALS` | Contraseña de reverificación incorrecta (D-2) |
| 403 | `ACCOUNT_INACTIVE` | Cuenta ya en `eliminado` (D-1; no se puede eliminar dos veces) |
| 500 | `INTERNAL_ERROR` | Error inesperado; stack trace solo en servidor |

**Anti-enumeración por timing NO aplica aquí:** el llamador ya posee un token de sesión
válido (identidad demostrada en el login); no hay oráculo de enumeración que proteger, por
lo que la distinción 401 (credencial incorrecta) vs 200 es segura. La cuenta en soft delete
debe tratarse con el **mismo mensaje genérico de credenciales** en `/login` (Módulo B), pero
eso ya está cubierto en B.

---

## 5. Separación de validaciones: controller vs service

**Controller (forma/tipo/presencia — primera barrera, ARQUITECTURA_BASE §2.2):**
- DELETE: `contrasena` no blanco y ≤ 72 (tope bcrypt) → 400 `VALIDATION_ERROR` con
  `details = [{"field": "contrasena", "message": "..."}]`.
- GET: no hay body que validar; solo se lee el `SesionPrincipal`.
- **NO decide** estado de la cuenta, verificación de contraseña ni cambios en BD.

**Service (`UsuarioService` — reglas de negocio, ARQUITECTURA_BASE §2.3):**
- `consultarPerfil`: fila inexistente → `NotFoundException`; estado `eliminado` → 403 (D-1);
  mapeo del `UsuarioPerfilDto` (D-4, D-8).
- `eliminarCuenta`: estado inválido → 403 (D-1); reverificación bcrypt (D-2, D-3); soft
  delete con guarda anti-carrera (REQ-FUN-05).

El service es **puro de Ktor y de SQL**: recibe `idUsuario` + DTOs, lanza excepciones de
dominio y delega la BD en repositorios; testeable con fakes (patrón de Módulos A/B/C).

---

## 6. Soft delete e integridad referencial

La "eliminación" es un **`UPDATE usuario SET estado = 'eliminado'`**, jamás un `DELETE`
físico (CLAUDE.md §7, REQ-FUN-05). Garantías del esquema V1:

- **`ON DELETE RESTRICT`** en todas las FK hacia `usuario` (`acudiente`, `configuracion`,
  `codigo_verificacion`, `tokens_reseteo`, `progreso_usuario`, `intento`): segunda barrera
  física contra borrado accidental; las filas hijas permanecen intactas tras el soft delete.
- **`UNIQUE(correo)`** y **`UNIQUE(nombre_usuario)`** siguen bloqueando el reuso: el correo
  de una cuenta `eliminado` queda bloqueado para nuevos registros (REQ-FUN-05 CA6,
  `EmailLockedException` en el registro, Módulo A) y el username permanece ocupado (V1).
- El login (Módulo B) ya rechaza cuentas `eliminado`; por tanto una cuenta soft-deleted no
  puede iniciar sesión (regla 6 de CLAUDE.md §4).

**No requiere migración nueva:** `usuario.estado ENUM('activo','eliminado')`, `avatar`,
`fecha_nacimiento` y las UNIQUE ya existen en `V1__init_schema.sql`.

---

## 7. Flujo del `UsuarioService` y reglas transaccionales

```
consultarPerfil(idUsuario):
  var fila: UsuarioRow? = null
  transactionRunner.run { fila = usuarioRepository.findById(idUsuario) }   // lectura en tx (§7.1)
  si fila == null: throw NotFoundException()                               // defensivo, 404
  si fila.estado != ACTIVO: throw AccountInactiveException()               // D-1, 403
  return UsuarioPerfilDto(fila.nombreMenor, fila.fechaNacimiento.toString(),   // D-8: ISO yyyy-MM-dd
                          fila.correo, fila.nombreUsuario, fila.avatar)

eliminarCuenta(idUsuario, contrasena):
  var fila: UsuarioRow? = null
  transactionRunner.run {
    fila = usuarioRepository.findByIdForUpdate(idUsuario)                  // FOR UPDATE: serializa
  }
  si fila == null: throw NotFoundException()                               // defensivo, 404
  si fila.estado != ACTIVO: throw AccountInactiveException()               // D-1, 403
  val ok = bcrypt.verify(contrasena, fila.contrasenaHash)                  // D-3: FUERA de la tx
  si !ok: throw InvalidCredentialsException()                              // D-2, 401
  transactionRunner.run {                                                  // segunda transacción (D-3)
    val actual = usuarioRepository.findByIdForUpdate(idUsuario)            // guarda anti-carrera
    si actual != null && actual.estado == ACTIVO:
      usuarioRepository.actualizarEstado(idUsuario, EstadoUsuario.ELIMINADO)  // soft delete
  }
  return MensajeResponseDto("Cuenta eliminada. Tus datos se conservan.")
```

### 7.1 Regla transaccional (espejo de Módulo C §5.1)

> **Toda interacción con la base de datos —incluidas las lecturas de `findById`— debe
> ocurrir dentro de un bloque `transactionRunner.run { ... }`.** Exposed exige contexto
> transaccional incluso para un `SELECT`; fuera del bloque produce
> `java.lang.IllegalStateException: No transaction in context` (regla detectada en la
> auditoría del Módulo C).

Convenciones resultantes (obligatorias en `UsuarioService`):
- Las lecturas se capturan en `var` **dentro** del bloque y se consumen **fuera** como `val`,
  sin retener referencias "perezosas" a objetos de Exposed.
- La **verificación bcrypt** ocurre **fuera** de la transacción (D-3): no se retiene la
  conexión durante el coste bcrypt ni se mantiene el lock `FOR UPDATE` mientras se computa el
  hash (corrección de la auditoría del Módulo C §5.1, aplicada al DELETE).
- Los `throw` de excepciones de dominio se lanzan **fuera** del bloque transaccional.
- **Zero logs:** nunca se loguea contraseña, hash, correo ni cédula (CLAUDE.md §6).

### 7.2 Diseño aprobado (Paso 4) — `src/main/kotlin/com/era/backend/services/UsuarioService.kt`

```kotlin
class UsuarioService(
    private val usuarioRepository: UsuarioRepository,
    private val transactionRunner: TransactionRunner,
)
```

bcrypt se usa directo (`BCrypt.verifyer()`, mismo patrón que `LoginService`); no se inyecta
`JwtTokenService` ni `OtpService` (no los necesita).

```kotlin
fun consultarPerfil(idUsuario: Long): UsuarioPerfilDto {
    var fila: UsuarioRow? = null
    transactionRunner.run { fila = usuarioRepository.findById(idUsuario) }
    val usuario = fila ?: throw NotFoundException("Usuario no encontrado.")  // defensivo, 404
    if (usuario.estado != EstadoUsuario.ACTIVO) {
        throw AccountInactiveException(MENSAJE_CUENTA_INACTIVA)              // D-1, 403
    }
    return UsuarioPerfilDto(
        nombreMenor = usuario.nombreMenor,
        fechaNacimiento = usuario.fechaNacimiento.toString(),               // D-8: ISO yyyy-MM-dd
        correo = usuario.correo,
        nombreUsuario = usuario.nombreUsuario,
        avatar = usuario.avatar,                                             // D-4: solo 5 campos
    )
}

fun eliminarCuenta(idUsuario: Long, request: EliminarCuentaRequestDto): MensajeResponseDto {
    var fila: UsuarioRow? = null
    transactionRunner.run {
        fila = usuarioRepository.findByIdForUpdate(idUsuario)                // FOR UPDATE: serializa
    }
    val usuario = fila ?: throw NotFoundException("Usuario no encontrado.")  // defensivo, 404
    if (usuario.estado != EstadoUsuario.ACTIVO) {
        throw AccountInactiveException(MENSAJE_CUENTA_INACTIVA)              // D-1, 403
    }

    val credencialValida = BCrypt.verifyer()
        .verify(request.contrasena.toCharArray(), usuario.contrasenaHash).verified   // D-3: FUERA de la tx
    if (!credencialValida) {
        throw InvalidCredentialsException(MENSAJE_CREDENCIALES)             // D-2, 401
    }

    transactionRunner.run {                                                 // segunda transacción (D-3)
        val actual = usuarioRepository.findByIdForUpdate(idUsuario)         // guarda anti-carrera
        if (actual != null && actual.estado == EstadoUsuario.ACTIVO) {
            usuarioRepository.actualizarEstado(idUsuario, EstadoUsuario.ELIMINADO)  // soft delete, REQ-FUN-05
        }
    }
    return MensajeResponseDto(MENSAJE_ELIMINADA)                           // D-5, 200
}
```

**Constantes de mensaje (companion object):**

| Constante | Valor |
|---|---|
| `MENSAJE_CUENTA_INACTIVA` | `"La cuenta no está activa."` (mismo texto que `LoginService`) |
| `MENSAJE_CREDENCIALES` | `"Credenciales incorrectas."` (mismo texto genérico que el login, B) |
| `MENSAJE_ELIMINADA` | `"Cuenta eliminada. Tus datos se conservan."` (contrato §4.2, D-5) |

**Observaciones del diseño aprobado:**
- `fechaNacimiento.toString()` → `LocalDate.toString()` = `yyyy-MM-dd` (canónico de Java),
  mismo formato que V9 del registro, sin dependencias (D-8).
- La guarda anti-carrera (relockear y comprobar `ACTIVO` en la segunda transacción) evita
  que una eliminación concurrente deje un estado inconsistente; jamás se `DELETE`a
  físicamente (CLAUDE.md §7).
- Sin anti-enumeración por timing en DELETE (§4.3): el llamador ya tiene sesión válida.
- La forma de `contrasena` (no blanco, ≤ 72) ya fue validada en el controller (Paso 5); el
  service solo la usa para bcrypt y nunca la loguea.

---

## 8. Decisiones aprobadas D-1…D-8

**D-1 — Cuenta `eliminado` en `/me` → 403 `ACCOUNT_INACTIVE`.**
Tanto `GET /me` como `DELETE /me` responden 403 `ACCOUNT_INACTIVE` si la cuenta está en soft
delete (reutiliza `AccountInactiveException`, núcleo aprobado). *Por qué:* el llamador posee
un token válido; 401 sería engañoso (la credencial es válida), y 200 revelaría un estado que
no debe operarse. La cuenta eliminada no puede consultar su perfil ni eliminarse dos veces.

**D-2 — Contraseña incorrecta en DELETE → 401 `INVALID_CREDENTIALS`.**
La reverificación fallida reutiliza `InvalidCredentialsException` (401). *Por qué:* es la
misma semántica que el login (Módulo B); el llamador ya está autenticado, así que el 401
dice "credencial de reverificación inválida", no "sesión inválida" (eso es `UNAUTHORIZED` del
challenge).

**D-3 — Verify bcrypt fuera de la transacción, con segunda transacción de escritura.**
En `eliminarCuenta`, la lectura con `findByIdForUpdate` y el verify bcrypt se separan: el
verify ocurre **fuera** de cualquier transacción, y el soft delete se aplica en una
**segunda transacción** que relockea y verifica `estado == ACTIVO` (guarda anti-carrera)
antes del UPDATE. *Por qué:* espejo de la corrección de auditoría del Módulo C §5.1 — no
mantener la conexión ni el lock durante el coste bcrypt, y no committear si entre la lectura
y el UPDATE la cuenta cambió de estado.

**D-4 — `GET /me` devuelve solo 5 campos (mínimo privilegio).**
`nombreMenor`, `fechaNacimiento`, `correo`, `nombreUsuario`, `avatar`. *Por qué:* la vista
"Mi cuenta" de REQ-FUN-06/CU-06 necesita exactamente esos datos; cédula del acudiente,
nombre del acudiente, hash y contadores **nunca** salen del backend (CLAUDE.md §6, datos de
menores y documentos de identidad).

**D-5 — `DELETE /me` responde 200 con `MensajeResponseDto` reutilizable.**
El éxito de la eliminación devuelve un DTO genérico `{ message: ... }` compartido por la
aplicación, sin datos personales. *Por qué:* no hay recurso addressable que retornar tras un
soft delete y se evita un DTO específico de una sola respuesta.

**D-6 — Código de error del challenge de autenticación: `UNAUTHORIZED`.** *(ratificada)*
El `challenge` del plugin responde 401 con `error = "UNAUTHORIZED"` en el `ErrorDto` estándar.
*Por qué:* el verifier falla antes de que `StatusPages` traduzca una `DomainException`; sin
`challenge`, el 401 saldría sin cuerpo y rompería §5.2 de ARQUITECTURA_BASE.

**D-7 — `SesionPrincipal` en `models/`.** *(ratificada)*
`data class SesionPrincipal(val idUsuario: Long)` vive en
`src/main/kotlin/com/era/backend/models/SesionPrincipal.kt`, junto al resto de modelos de
dominio (no en `plugins/`). *Por qué:* es un modelo de dominio del contexto de
autenticación, no una configuración de Ktor.

**D-8 — Fecha de respuesta como `String` ISO `yyyy-MM-dd`.** *(ratificada)*
`fechaNacimiento` se devuelve como `String` ISO, mapeando `fila.fechaNacimiento.toString()`
(misma representación que `RegisterRequestDto` V9, verificada en el repo). *Por qué:*
consistencia con el contrato de entrada del registro y **cero dependencias nuevas** (evita
`kotlinx-datetime` o un serializador de `LocalDate`, regla §5.4 #4 de CLAUDE.md: toda
dependencia exige explicación y aprobación previa).

---

## 9. Excepciones de dominio

| Excepción | Status | Uso |
|---|---|---|
| `ValidationException` | 400 | Forma inválida del controller (DELETE: `contrasena`), con `details` |
| `InvalidCredentialsException` | 401 | Reverificación de contraseña incorrecta (D-2) |
| `AccountInactiveException` | 403 | Cuenta `eliminado` en GET o DELETE (D-1) |
| `NotFoundException` | 404 | Defensivo: fila inexistente con token válido |

Todas viven en el núcleo aprobado (`exceptions/CoreExceptions.kt`, ARQUITECTURA_BASE §5.3) y
se mapean automáticamente por `StatusPages` vía `DomainException.status`. El 401 del
`challenge` (D-6) lo produce el propio plugin de autenticación, no una excepción de dominio.

---

## 10. Trazabilidad

| Requisito / criterio | Caso de uso | Historia | Dónde se cumple |
|---|---|---|---|
| REQ-FUN-06 Cuenta del usuario (lectura de perfil) | CU-06 | HU-06 | §1, §3, §5, §7 |
| REQ-FUN-06 CA5 (solo `avatar` y `username` editables) | CU-06 | HU-06 | §3.2 (el GET solo lee; la edición restringida se resuelve en módulos posteriores: avatar = Módulo I, username = actualización fuera de esta iteración) |
| REQ-FUN-05 Eliminar cuenta | CU-07 | HU-05 | §1, §4, §6, §7 |
| REQ-FUN-05 CA2 (reverificación de contraseña) | CU-07 | HU-05 | §4.1, §5, §7 (`eliminarCuenta`) |
| REQ-FUN-05 CA5 (nunca se loguea ni expone datos) | CU-07 | HU-05 | §5, §7.1 (zero logs), §8 D-4/D-5 |
| REQ-FUN-05 CA6 (correo bloqueado tras eliminar) | CU-07 | HU-05 | §6 (UNIQUE + `EmailLockedException` en Módulo A) |
| REQ-FUN-05 (soft delete, no borrado físico) | CU-07 | HU-05 | §6, §7, CLAUDE.md §7 |
| REQ-NF-02 (seguridad: autenticación JWT, bcrypt, mínimo privilegio) | CU-06, CU-07 | HU-05, HU-06 | §2, §4.1, §6, §8 |

---

## 11. Migración y datos

**Sin migración nueva.** El esquema V1 ya contiene `usuario.estado ENUM('activo','eliminado')`,
`usuario.avatar`, `usuario.fecha_nacimiento`, `UNIQUE(correo)` y `UNIQUE(nombre_usuario)`, y
las FK con `ON DELETE RESTRICT`. No se crean tablas ni columnas para D/E.

**Sin dependencias nuevas.** `module.yaml` ya incluye `$ktor.server.auth.jwt`; no se edita
`module.yaml` ni `libs.versions.toml` (regla §5.4 #4).

---

## 12. Plan de implementación (capa por capa) y tests

Implementación capa por capa (cada capa se diseña y aprueba antes de codificar;
`ARQUITECTURA_BASE.md` §3):

| Paso | Contenido | Estado |
|---|---|---|
| 1 | **Infraestructura de autenticación:** `models/SesionPrincipal.kt` + `plugins/AuthenticationConfig.kt` + wiring en `Application.module()` | Diseño **aprobado** |
| 2 | **DTOs:** `UsuarioPerfilDto`, `EliminarCuentaRequestDto`, `MensajeResponseDto` | Diseño **aprobado** |
| 3 | **Repositorio:** `UsuarioRepository.findById` (D) y `actualizarEstado` (E) en interfaz + `ExposedUsuarioRepository` + `FakeUsuarioRepository` | Diseño **aprobado** |
| 4 | **Service:** `UsuarioService` (consultarPerfil, eliminarCuenta) | Diseño **aprobado** (§7.2) |
| 5 | **Controller + Routes:** `UsuarioController` + `UserRoutes.kt` dentro de `authenticate("session-jwt")` | Diseño **aprobado** e **implementado** (§12.1) |
| 6 | **Wiring final + tests:** repos Exposed en `Application.kt`, `UsuarioServiceTest`, `UserRoutesTest` | Diseño **aprobado** e **implementado** (§12.2) |

### 12.1 Paso 5 (implementado) — Controller + Routes

`controllers/UsuarioController.kt`:

- `consultarPerfil(principal: SesionPrincipal): UsuarioPerfilDto` — sin body que validar; solo
  delega en `usuarioService.consultarPerfil(principal.idUsuario)`.
- `eliminarCuenta(principal: SesionPrincipal, request: EliminarCuentaRequestDto): MensajeResponseDto` —
  validación de **forma** (primera barrera, §5): `contrasena` no blanco y ≤ 72, lanzando
  `ValidationException` con `details = [{"field": "contrasena", "message": "..."}]` (→ 400).
  No decide estado ni toca BD; delega en `usuarioService.eliminarCuenta(principal.idUsuario, request)`.

`routes/UserRoutes.kt`:

```kotlin
fun Application.userRoutes(usuarioController: UsuarioController) {
    routing {
        route("/api/v1/users") {
            authenticate("session-jwt") {
                get("/me") { ... }
                delete("/me") { ... }
            }
        }
    }
}
```

El bloque `authenticate("session-jwt")` garantiza que sin un JWT de sesión válido
(§2) **no se llega al controller**; el `SesionPrincipal` se extrae del contexto
(`call.principal<SesionPrincipal>()`) —nulo solo por inconsistencia, en cuyo caso el
`challenge` ya respondió 401— y se pasa al controller.

### 12.2 Paso 6 (implementado) — Wiring final + tests

**Wiring en `Application.module()`** (tras `authRoutes(authController)`):

```kotlin
val usuarioRepository = ExposedUsuarioRepository()
val usuarioService = UsuarioService(usuarioRepository, ExposedTransactionRunner())
val usuarioController = UsuarioController(usuarioService)
userRoutes(usuarioController)
```

**Tests implementados:**

| Suit | Cobertura |
|---|---|
| `services/UsuarioServiceTest.kt` (9 tests) | `consultarPerfil`: 200 (5 campos exactos, D-4/D-8), 404 defensivo, 403 `eliminado` (D-1). `eliminarCuenta`: 200 (estado verificado por `actualizarEstado`), 404, 403 ya eliminada, 401 contraseña incorrecta (D-2), verify fuera de tx + guarda anti-carrera (D-3), y la respuesta `MensajeResponseDto` (D-5). |
| `routes/UserRoutesTest.kt` (10 tests) | GET `/me` 200 (5 campos exactos); 401 sin token; 401 token de audiencia `era-app-reset`; 401 con `purpose`; 403 `eliminado`; 404 defensivo. DELETE `/me` 200 (soft delete); 400 forma `contrasena` en blanco / > 72 (con `details`); 401 sin token; 401 token reseteo; 401 `INVALID_CREDENTIALS`; 403 cuenta ya eliminada. |

**Verificación transversal:** el `contrasena_hash` y la cédula del acudiente **jamás**
aparecen en ningún body de respuesta; cero logs de correo, hash, token ni cédula (CLAUDE.md §6).

**Resultado:** `.\kotlin build` exitoso sin warnings; `.\kotlin test` → **144/144 tests
verdes** (antes 125; +19 de D/E). Env vars placeholder de `.env.example` requeridas para
`ConfigLoadTest`.
