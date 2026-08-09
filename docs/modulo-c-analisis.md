# ERA — Módulo C (Recuperación de contraseña) — Análisis funcional

> Documentación oficial del sistema ERA. Ver [`../CLAUDE.md`](../CLAUDE.md) para las reglas
> permanentes y la matriz de trazabilidad.
>
> **Base normativa:** [`requisitos-funcionales.md`](./requisitos-funcionales.md) (REQ-FUN-07),
> [`requisitos-no-funcionales.md`](./requisitos-no-funcionales.md) (REQ-NF-02),
> [`casos-de-uso.md`](./casos-de-uso.md) (CU-03, CU-11),
> [`historias-de-usuario.md`](./historias-de-usuario.md) (HU-07),
> [`ARQUITECTURA_BASE.md`](./ARQUITECTURA_BASE.md) (§2.3, §5.3, §5.4) y
> [`DICCIONARIO_DATOS.md`](./DICCIONARIO_DATOS.md).
>
> Este documento registra el **análisis funcional aprobado** del Módulo C y su
> **implementación** (capa por capa, `ARQUITECTURA_BASE.md` §3). Las decisiones
> C-1…C-6 de §7 son vinculantes; la sección §5 recoge además la regla transaccional
> aprobada en auditoría tras las pruebas manuales.

---

## 1. Alcance y actores

**Alcance:** Módulo C (Recuperación de contraseña, REQ-FUN-07, CU-03, HU-07). Tres
endpoints, un flujo en 3 pasos:

| Paso | Endpoint | Función |
|---|---|---|
| 1 | `POST /api/v1/auth/password-reset/request` | Solicita un OTP de 6 dígitos (vigencia 10 min) para el correo. |
| 2 | `POST /api/v1/auth/password-reset/verify` | Verifica el OTP (P1) y emite un **token puente JWT** de 10 min single-use (C-3). |
| 3 | `POST /api/v1/auth/password-reset/confirm` | Valida el token puente (C-3), la política de contraseña (C-6) y el veto a repetir la anterior (CA5), y cambia el hash. |

**Actor principal:** el acudiente (o el menor con mediación) que olvidó la contraseña de una
cuenta registrada, con correo **verificado** y estado `activo`. La recuperación es el espejo
inverso del alta de cuenta: vuelve a demostrar la posesión del correo mediante un OTP
(igual que CU-11 en el registro) antes de permitir el cambio.

**Flujo (CU-03):**
1. El usuario solicita la recuperación con su correo.
2. El sistema emite un OTP de 6 dígitos con vigencia de 10 minutos y lo envía por correo.
3. El usuario ingresa el código; el sistema lo verifica (máx. 3 intentos, P1).
4. Con el OTP verificado, el sistema entrega un token puente de corta vida (10 min,
   single-use, C-3).
5. El usuario define la nueva contraseña (política C-6; no puede repetir la anterior, CA5).
6. El sistema cambia el hash y responde 200; el usuario vuelve a iniciar sesión.

**Postcondición:** la contraseña fue reemplazada en `usuario.contrasena_hash` (bcrypt),
el OTP y el token puente quedan consumidos. **Nunca** se expone el OTP, el hash ni el token
en respuestas ni logs (mínimo privilegio, CLAUDE.md §6).

---

## 2. Reglas del flujo (REQ-FUN-07)

| # | Regla | Detalle |
|---|---|---|
| R1 | OTP de 6 dígitos numéricos | `^\d{6}$`; vigencia **10 minutos** (mismo estándar que el registro, REQ-FUN-01 CA4). |
| R2 | P1 — máx. 3 intentos fallidos | Al 3.er fallo el OTP queda permanentemente invalidado hasta un nuevo envío (misma política que el registro/verificación). |
| R3 | P2 — throttle de 60 s | Reenvíos antes de 60 s → 429 `OTP_RESEND_THROTTLED` (solo cuando hay un OTP previo; filas previas a la migración V3 se tratan como permitidas). |
| R4 | CA4 — anti-enumeración | El endpoint de solicitud responde **siempre** 200 con el mismo mensaje genérico, exista o no el correo (C-1). |
| R5 | CA5 — veto a repetir la contraseña | La nueva contraseña no puede ser igual a la anterior (bcrypt contra el hash vigente). |
| R6 | Cuentas `eliminado` = inexistentes | Un correo en soft delete (REQ-FUN-05) no puede recuperar la contraseña y se trata como inexistente (C-1). |

---

## 3. Contrato de los endpoints

Ruta base: `/api/v1/auth/password-reset` (nomenclatura §5.1 de ARQUITECTURA_BASE).

### 3.1 `POST /password-reset/request` — `PasswordResetRequestDto`

| Campo | Tipo | Presencia | Regla |
|---|---|---|---|
| `correo` | String | obligatorio | formato email (≤ 255); se normaliza a minúsculas (V5) en el controller antes de delegar |

**Response 200 OK (idéntica exista o no la cuenta, C-1):**

```json
{ "message": "Si el correo está registrado, recibirás un código de verificación." }
```

### 3.2 `POST /password-reset/verify` — `PasswordResetVerifyRequestDto`

| Campo | Tipo | Presencia | Regla |
|---|---|---|---|
| `correo` | String | obligatorio | formato email (≤ 255), normalizado a minúsculas (V5) |
| `codigo` | String | obligatorio | exactamente 6 dígitos (`^\d{6}$`) |

**Response 200 OK:**

```json
{ "resetToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." }
```

El token puente es JWT HS256 de 10 minutos, single-use, con doble vínculo `jti`+`sub`
(C-3). El OTP verificado no se reexpone.

### 3.3 `POST /password-reset/confirm` — `PasswordResetConfirmRequestDto`

| Campo | Tipo | Presencia | Regla |
|---|---|---|---|
| `resetToken` | String | obligatorio | token puente del paso 2; no blanco |
| `nuevaContrasena` | String | obligatorio | cumple la política compartida (C-6); ≤ 72 (tope técnico de bcrypt) |
| `confirmarContrasena` | String | obligatorio | coincide con `nuevaContrasena` (espejo del alta de cuenta) |

**Response 200 OK:**

```json
{ "message": "Contraseña actualizada. Ya puedes iniciar sesión." }
```

### 3.4 Códigos de estado (formato `ErrorDto` de §5.2 de ARQUITECTURA_BASE)

| Status | `error` | Cuándo |
|---|---|---|
| 200 | — | Éxito de los tres pasos (mensaje o `resetToken`) |
| 400 | `VALIDATION_ERROR` | Falla de forma (controller) **o** política de contraseña (C-6), con `details` por campo |
| 400 | `INVALID_REQUEST` | JSON malformado o body ausente (lo produce StatusPages) |
| 401 | `OTP_INVALID_OR_EXPIRED` | `/verify`: OTP incorrecto, vencido, usado o sin cuenta activa; mensaje genérico (C-1) |
| 401 | `RESET_TOKEN_INVALID` | `/confirm`: token puente inválido, vencido, consumido o con vínculo roto; mensaje genérico |
| 409 | `PASSWORD_REUSED` | `/confirm`: la nueva contraseña repite la anterior (REQ-FUN-07 CA5) |
| 429 | `OTP_RESEND_THROTTLED` | `/request`: reenvío antes de 60 s (C-2) |
| 500 | `INTERNAL_ERROR` | Error inesperado; stack trace solo en servidor |

---

## 4. Separación de validaciones: controller vs service

**Controller (forma/tipo/presencia — primera barrera, ARQUITECTURA_BASE §2.2):**
- `correo`: no blanco, ≤ 255, regex email, **normalización a minúsculas (V5)** antes de delegar.
- `codigo`: no blanco, regex `^\d{6}$`.
- `resetToken`: no blanco.
- `nuevaContrasena`: no blanco, ≤ 72; `confirmarContrasena`: no blanco y **coincidente** con
  `nuevaContrasena`.
- **NO decide** anti-enumeración, throttle, single-use, política ni veto a repetir.

**Service (`PasswordResetService` — reglas de negocio, ARQUITECTURA_BASE §2.3):**
- Anti-enumeración con mensaje genérico y `HASH_DUMMY` (C-1).
- Throttle de 60 s (C-2) y políticas P1/P2.
- Single-use del OTP y emisión del token puente con doble vínculo (C-3).
- Veto a repetir la contraseña anterior (CA5) y política compartida (C-6).
- Cambio atómico de hash + consumo del token.

El service es **puro de Ktor y de SQL** (recibe DTOs, lanza excepciones de dominio, delega
la BD en repositorios y el JWT en `JwtTokenService`); testeable con fakes.

---

## 5. Flujo del `PasswordResetService` y regla transaccional

```
solicitarReseteo(request):
  transactionRunner.run { usuario = findByEmail(correo) }   // ← TAMBIÉN en transacción (§5.1)
  si usuario == null o estado != ACTIVO:
    otpService.hash(generate())                             // igualar timing (C-1)
    return 200 genérico                                     // sin insertar ni enviar

  code = otpService.generate()
  hash = otpService.hash(code); exp = now + 10min           // FUERA de la transacción (§5.1)
  transactionRunner.run {
    deleteExpiradosPorUsuario(usuario.id)                   // limpieza lazy de tokens puente (auditoría)
    ultimo = codigoRepository.findUltimoPorUsuarioForUpdate(usuario.id)  // FOR UPDATE
    si últimoEnvío < 60 s: throw OTP_RESEND_THROTTLED       // C-2 (sin escrituras previas)
    insert o actualizarEnvio(hash, exp, now)                // reinicia P1, registra P2
  }
  otpService.send(correo, code)                             // SMTP FUERA de la transacción
  return 200 genérico

verificarReseteo(request):
  transactionRunner.run { usuario = findByEmail(correo) }   // ← TAMBIÉN en transacción (§5.1)
  si usuario == null o estado != ACTIVO:
    otpService.verificar(codigo, HASH_DUMMY, 0, now+10min)  // igualar timing (C-1)
    throw OTP_INVALID_OR_EXPIRED

  transactionRunner.run {
    codigo = findUltimoPorUsuarioForUpdate(usuario.id)
    si sin código o usado: resultado = OTP_INVALIDO
    si verify falla:
      actualizarIntentosFallidos(min(actual+1, 3))          // P1; commit del contador
      resultado = OTP_INVALIDO
    si verify ok:
      marcarUsado(codigo.id)                                // single-use del OTP
      insert(tokens_reseteo(jti=UUID, usuario.id, exp 10 min))  // mismo tx
      resultado = VERIFICADO(jti)
  }
  emitirReseteo(id, jti)                                    // JWT FUERA de la transacción
  si VERIFICADO → 200 { resetToken }; si no → 401 genérico

confirmarReseteo(request):
  (idUsuario, jti) = validarTokenPuente(token)              // JWT.require manual (firma/iss/aud/purpose/exp)
  nuevoHash = otpService.hash(nuevaContrasena)
  transactionRunner.run {
    token = findByJtiForUpdate(jti)                         // FOR UPDATE
    si token nulo/consumido/vencido/idUsuario != sub: resultado = TOKEN_INVALIDO
    usuario = findByIdForUpdate(idUsuario)
    si usuario nulo: resultado = TOKEN_INVALIDO
    PasswordPolicy.validar(nuevaContrasena, username, nombre)  // C-6 → 400
    si bcrypt.verify(nueva, hash actual): resultado = REUTILIZADA  // CA5 → 409
    actualizarContrasena(id, nuevoHash); marcarConsumido(token.id)  // atómicos
    resultado = CAMBIADA
  }
  200 mensaje | 401 RESET_TOKEN_INVALID | 409 PASSWORD_REUSED
```

### 5.1 Regla transaccional (auditoría — corrección de `IllegalStateException`)

> **Toda interacción con la base de datos —incluidas las búsquedas iniciales de
> `findByEmail` en `solicitarReseteo` y `verificarReseteo`— debe ocurrir dentro de un
> bloque `transactionRunner.run { ... }`.** Exposed exige contexto transaccional incluso
> para un `SELECT`; ejecutar una lectura fuera del bloque produce
> `java.lang.IllegalStateException: No transaction in context`. Este error fue detectado
> en las pruebas manuales del flujo completo y corregido envolviendo las lecturas iniciales.

Convenciones resultantes (espejo de Módulos A/B, **obligatorias** en este service):
- Las lecturas se capturan en una variable `var usuario: UsuarioRow?` asignada **dentro** del
  bloque y se consumen **fuera** como `val activo` (o `usuario ?: return`), para no retener
  referencias "perezosas" a objetos de Exposed fuera de la transacción.
- El **SMTP** (`otpService.send`) y la **emisión del JWT** (`emitirReseteo`) ocurren
  **siempre fuera** de la transacción: no se mantiene la conexión durante latencia ni
  cómputo puro.
- Los `throw` de excepciones de dominio se lanzan **fuera** del bloque tras marcar el
  resultado en una variable, para que la escritura previa (P1/P2) commitee.
- El hash bcrypt nuevo se calcula **antes** de la transacción en `/confirm` **y en
  `/request`** (no retener la conexión durante el coste bcrypt; auditoría sobre
  `solicitarReseteo`).
- **Limpieza lazy de `tokens_reseteo`:** cada solicitud de reseteo (`/request`) purga los
  tokens puente expirados del usuario dentro de la misma transacción (patrón V2); el
  `deleteExpiradosPorUsuario` solo commitea si el envío procede.
- **Zero logs:** nunca se loguea correo, OTP, hash, jti ni token (CLAUDE.md §6).

---

## 6. Token puente — `JwtTokenService.emitirReseteo(idUsuario, jti)`

Mismo `JwtTokenService` que el login (Módulo B), con los claims de **reseteo**:

| Claim | Valor |
|---|---|
| `alg` | HS256 (`Algorithm.HMAC256(secret)`) |
| `sub` | `id_usuario` (String) |
| `iss` | `jwt.reset.issuer` = `era-backend` |
| `aud` | `jwt.reset.audience` = `era-app-reset` |
| `purpose` | `PASSWORD_RESET` (custom claim; diferenciación de sesión) |
| `exp` | now + `jwt.reset.ttlMinutes` = **10 min** |
| `jti` | UUID **persistido en `tokens_reseteo`** (single-use, C-3) |

**Doble vínculo (C-3):** el single-use no depende solo de la firma. En `/confirm`,
`validarTokenPuente` verifica firma/iss/aud/purpose/exp y extrae `(sub, jti)`; dentro de la
transacción, `findByJtiForUpdate(jti)` exige que la fila exista, no esté consumida, no esté
vencida **y** que su `id_usuario` coincida con el `sub` (rechaza un JWT válido de la cuenta A
apuntando a la fila de la cuenta B). El secreto se resuelve solo de `${JWT_SECRET}` (misma
regla que B; sin hardcode ni derivación).

---

## 7. Decisiones aprobadas C-1…C-6

**C-1 — Anti-enumeración total (request y verify).** El endpoint de solicitud responde
**siempre** el mismo 200 genérico, exista o no el correo, con `HASH_DUMMY` (hash bcrypt
pre-calculado en tiempo de compilación) verificado en el camino "sin cuenta" para igualar el
timing del camino real. Una cuenta `eliminado` (REQ-FUN-05) se trata como inexistente: no
puede recuperar su contraseña. En `/verify`, un correo sin cuenta activa verifica el código
contra `HASH_DUMMY` y lanza el mismo 401 genérico que un código incorrecto. *Por qué:* el
flujo de recuperación es un oráculo clásico de enumeración de cuentas; ninguna respuesta ni
timing debe revelar si el correo existe (REQ-FUN-07 CA4, REQ-NF-02).

**C-2 — Throttle de 60 s solo con OTP previo.** El reenvío (`/request`) responde
429 `OTP_RESEND_THROTTLED` si el último envío del usuario fue hace menos de 60 s (columna
`codigo_verificacion.ultimo_envio_en`, migración V3). Solo aplica cuando hay un código
previo; filas previas a V3 (`ultimo_envio_en` NULL) se tratan como permitidas. No se envían
correos ni se sobrescribe el código al frenar. El reenvío permitido reinicia el contador P1.

**C-3 — Token puente single-use con doble vínculo.** El OTP verificado emite un JWT de
reseteo de 10 min registrado en `tokens_reseteo` (`jti` + `id_usuario` + `exp`, en la **misma
transacción** que marca el OTP como usado). El consumo exige el vínculo `jti`↔fila **y**
`sub`↔`id_usuario` (`FOR UPDATE`), con rechazo a tokens vencidos, consumidos, sin fila o con
fila ajena. *Por qué:* un token puente robado es útil solo si el atacante también posee el
correo y actúa dentro de los 10 min; el doble vínculo cierra el caso de reutilización
cross-account.

**C-4 — El OTP es single-use.** Un OTP ya usado se rechaza con el mismo 401 genérico
(`CodigoVerificacionRow.usado`), sin contar como fallo P1.

**C-5 — Cambio atómico hash + consumo.** `actualizarContrasena` y `marcarConsumido` ocurren
en una sola transacción: nunca queda una contraseña nueva con un token aún válido, ni un
token consumido sin el cambio. El hash bcrypt se calcula antes de la transacción.

**C-6 — Política compartida (`utils/PasswordPolicy`).** La validación de la nueva contraseña
usa la misma política que el registro (mayúsculas, minúsculas, números, símbolos, ≥ 8
caracteres, sin datos personales ni igual al username) extraída a un componente único
`utils/PasswordPolicy` y reutilizada por `RegistrationService` y `PasswordResetService`.
*Por qué:* una segunda política divergente en la recuperación rompería la regla REQ-FUN-01
CA2 y forzaría contraseñas que el registro no permitiría.

---

## 8. Excepciones de dominio

| Excepción | Status | Uso |
|---|---|---|
| `ValidationException` | 400 | Forma inválida (controller) o política de contraseña (C-6), con `details` |
| `OtpInvalidException` | 401 | OTP incorrecto/vencido/usado o sin cuenta activa → mensaje genérico |
| `ResetTokenInvalidException` | 401 | Token puente inválido/vencido/consumido/vínculo roto → mensaje genérico |
| `PasswordReuseException` | 409 | La nueva contraseña repite la anterior (CA5) |
| `OtpResendThrottledException` | 429 | Reenvío antes de 60 s (C-2) |

`OtpInvalidException` y `ResetTokenInvalidException` viven en el núcleo aprobado
(`exceptions/CoreExceptions.kt`, ARQUITECTURA_BASE §5.3); `PasswordReuseException` y
`OtpResendThrottledException` en `exceptions/ModuleExtensions.kt`. Todas se mapean
automáticamente por `StatusPages` vía `DomainException.status`.

---

## 9. Trazabilidad REQ-FUN-07 ↔ CU-03 ↔ HU-07

| Requisito / criterio | Caso de uso | Historia | Dónde se cumple |
|---|---|---|---|
| REQ-FUN-07 Recuperación de contraseña | CU-03, CU-11 | HU-07 | §1, §3, §5, §7 |
| REQ-FUN-07 CA1 (OTP de 6 dígitos, 10 min, reenvío) | CU-03 paso 2, flujo alt. | HU-07 | §2 R1, §5 |
| REQ-FUN-07 CA2 (código máximo de intentos) | CU-03 paso 3 | HU-07 | §2 R2 (P1, 3 fallos) |
| REQ-FUN-07 CA3 (nueva contraseña) | CU-03 paso 4 | HU-07 | §5 (confirm), §7 C-6 |
| REQ-FUN-07 CA4 (correo inexistente → mensaje genérico) | CU-03 flujo alt. | HU-07 | §2 R4, §7 C-1 |
| REQ-FUN-07 CA5 (no repetir contraseña anterior) | CU-03 paso 4 | HU-07 | §2 R5, §7 C-3, §8 |
| REQ-FUN-05 (cuenta eliminada no recupera contraseña) | CU-03 flujo alt. | HU-05 | §2 R6, §7 C-1 |
| REQ-NF-02 (seguridad: bcrypt, genericidad, bloqueo) | CU-03 | HU-07 | §5, §7, §8 |
| CU-11 Verificar código (`<<include>>`) | CU-03 | — | §5 (verify), §7 C-3 |

---

## 10. Migración y datos

**Migración `V3__codigo_verificacion_ultimo_envio.sql`:** agrega
`codigo_verificacion.ultimo_envio_en DATETIME NULL` (C-2). Sin nuevas tablas: el OTP de
recuperación reutiliza `codigo_verificacion` (nueva por Módulo C) y el token puente se
persiste en `tokens_reseteo` (`jti`, `id_usuario`, `expira_en`, `consumido`, `creado_en`).
Diccionario de datos actualizado en `DICCIONARIO_DATOS.md`.

`PasswordPolicy` (C-6) se extrae a `utils/PasswordPolicy.kt` y pasa a ser la fuente única de
la regla de contraseña para registro y recuperación. **Sin dependencias nuevas** en
`module.yaml` / `libs.versions.toml` (bcrypt y java-jwt ya estaban declaradas).

---

## 11. Plan de implementación (ejecutado) y tests

Implementación capa por capa (cada capa aprobada; sin generar el proyecto de una vez):

1. **Migración + política + DTOs + emisión JWT:** `V3__*.sql`, `utils/PasswordPolicy.kt`,
   `PasswordReset{Request,VerifyRequest,ConfirmRequest,Response,VerifyResponse}Dto.kt`,
   `JwtTokenService.emitirReseteo(idUsuario, jti)`.
2. **Entidades y repositorios (Exposed + fakes):** `CodigoVerificacionRow`/
   `CodigoVerificacionRepository` (insert, `findUltimoPorUsuarioForUpdate`, `actualizarEnvio`,
   `actualizarIntentosFallidos`, `marcarUsado`), `TokensReseteoRow`/`TokensReseteoRepository`
   (insert, `findByJtiForUpdate`, `marcarConsumido`, `deleteExpiradosPorUsuario`) y
   `UsuarioRepository.actualizarContrasena`.
3. **Service:** `PasswordResetService` (§5) con `HASH_DUMMY` y `validarTokenPuente`.
4. **Controller + Routes + Wiring:** handlers en `AuthController`, rutas
   `password-reset/request|verify|confirm` en `AuthRoutes`, `jwtTokenService` compartido y
   repos Exposed en `Application.kt`.
5. **Tests:** `PasswordResetServiceTest` (unit, fakes; ~24 casos) y
   `AuthControllerPasswordResetTest` (HTTP TestHost; 17 casos: 400 de forma, anti-enumeración,
   429, single-use del token, 409, política y **E2E completo request → verify → confirm →
   login**). Los tests de rutas existentes (`AuthController{Test,LoginTest,VerificationTest}`)
   se actualizaron con el nuevo constructor del controller.

**Resultado:** suite completa **124/124 verdes** (sin MySQL; env vars del `.env.example`
requeridas para `ConfigLoadTest`).

### Pruebas manuales (curl, `APP_DEV_MODE=true`, OTP fijo `123456`)

Base: `http://localhost:8080/api/v1/auth`.

```powershell
$env:APP_DEV_MODE='true'; $env:JWT_SECRET='<secreto_dev>'; # + DB_* y SMTP_*
.\kotlin run
```

```powershell
# Paso 1
curl.exe -X POST http://localhost:8080/api/v1/auth/password-reset/request `
  -H "Content-Type: application/json" -d '{"correo":"laura.perez@example.com"}'
# Paso 2 (dev → OTP 123456)
curl.exe -X POST http://localhost:8080/api/v1/auth/password-reset/verify `
  -H "Content-Type: application/json" -d '{"correo":"laura.perez@example.com","codigo":"123456"}'
# Paso 3 (usar el resetToken devuelto)
curl.exe -X POST http://localhost:8080/api/v1/auth/password-reset/confirm `
  -H "Content-Type: application/json" `
  -d '{"resetToken":"<RESET_TOKEN>","nuevaContrasena":"Nueva#2026","confirmarContrasena":"Nueva#2026"}'
# Verificación con la nueva contraseña (Módulo B)
curl.exe -X POST http://localhost:8080/api/v1/auth/login `
  -H "Content-Type: application/json" -d '{"usuarioOCorreo":"laura.perez@example.com","contrasena":"Nueva#2026"}'
```
