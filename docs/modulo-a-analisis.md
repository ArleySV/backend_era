# ERA — Módulo A (Registro) — Análisis funcional

> Documentación oficial del sistema ERA. Ver [`../CLAUDE.md`](../CLAUDE.md) para las reglas
> permanentes y la matriz de trazabilidad.
>
> **Base normativa:** [`requisitos-funcionales.md`](./requisitos-funcionales.md)
> (REQ-FUN-01), [`casos-de-uso.md`](./casos-de-uso.md) (CU-01, CU-11),
> [`historias-de-usuario.md`](./historias-de-usuario.md) (HU-01, HU-15),
> [`ARQUITECTURA_BASE.md`](./ARQUITECTURA_BASE.md) (§1, §2, §5.2, §5.3, §5.4) y
> [`DICCIONARIO_DATOS.md`](./DICCIONARIO_DATOS.md).
>
> Este documento registra el **análisis funcional aprobado** del Módulo A. La estructura
> de archivos (routes → controllers → services → repositories → models) se define en una
> iteración posterior, siguiendo `ARQUITECTURA_BASE.md` §3.

---

## 1. Alcance y actores

**Alcance:** Módulo A (Registro). Cubre el endpoint `POST /api/v1/auth/register` y el ciclo
de vida del registro hasta la verificación (paso 3). Los contratos de A.1 (`verify-email`,
`resend-otp`) se documentan en §3A; aquí se definen las decisiones que los afectan
(P1/P2/P3 y el diseño de `OtpService`).

**Actor principal:** menor de edad, con datos aportados por el acudiente (CU-01, HU-15).

**Precondición:** el usuario no cuenta con una cuenta activa en el sistema.

**Flujo (CU-01):**
1. El menor (con el acudiente) ingresa los datos personales del menor y del acudiente
   (paso 1).
2. Configura correo, usuario, avatar y contraseña (paso 2).
3. El sistema envía un código de verificación de 6 dígitos al correo (paso 3, CU-11
   incluido).
4. El usuario ingresa el código y el sistema activa la cuenta (endpoint A.1
   `verify-email`).

**Postcondición (del flujo completo):** la cuenta queda registrada y activa en la base de
datos.

---

## 2. Reglas de persistencia del registro

De acuerdo con `DICCIONARIO_DATOS.md` (tabla `registro_pendiente`):

- `POST /register` **no crea** una fila en `usuario`. Crea únicamente una fila en
  `registro_pendiente` con: datos del paso 1 + paso 2, hash bcrypt de la contraseña, hash
  bcrypt del OTP, `expira_en = now + 10 min` e `intentos_fallidos = 0`.
- Al verificar el código (A.1), el service crea `usuario` + `acudiente` (y `configuracion`
  con defaults) en una **única transacción** y elimina la fila de `registro_pendiente`.
- **Unicidad:** el service valida correo y username contra `usuario` **y**
  `registro_pendiente` a la vez (evita reservas paralelas); las constraints `UNIQUE` de
  MySQL son el respaldo anti-TOCTOU.
- **Avatar en el registro:** el paso 2 permite elegir uno de los 3 avatares
  **preestablecidos** del cliente (REQ-FUN-06); el servidor persiste solo el
  **identificador de preset** (`preset:1|2|3`, V6), nunca una foto. La imagen personalizada
  **no se sube durante el registro**: el cliente la retiene en su dispositivo y se sube
  **después** de la verificación (`PUT /api/v1/users/me/avatar`, Módulo I), cuando el
  usuario ya existe — evita imágenes huérfanas si el `registro_pendiente` expira sin
  verificar (mismo criterio de limpieza de V2, aplicado a archivos). En
  `registro_pendiente.avatar` nunca hay una foto personalizada.

---

## 3. Contrato de `POST /api/v1/auth/register`

Ruta: `/api/v1/auth/register` (nomenclatura §5.1 de ARQUITECTURA_BASE).

### 3.1 Request — `RegisterRequestDto`

| Campo | Tipo | Presencia | Regla |
|---|---|---|---|
| `nombreMenor` | String | obligatorio | nombres completos del menor; ≤ 120 |
| `fechaNacimiento` | String ISO `yyyy-MM-dd` | obligatorio | parseable a `LocalDate`, no futura (V9) |
| `nombreAcudiente` | String | obligatorio | nombre completo del acudiente; ≤ 120 |
| `cedulaAcudiente` | String | obligatorio | 6–20 caracteres alfanuméricos (V8) |
| `correo` | String | obligatorio | formato email; ≤ 255; normalizado a minúsculas (V5) |
| `nombreUsuario` | String | obligatorio | 3–60, sin espacios (V4) |
| `avatar` | String? | opcional | si viene: debe ser uno de los 3 identificadores de preset (`preset:1\|2\|3`, V6); **no acepta foto personalizada** — esa solo existe post-verificación (Módulo I) |
| `contrasena` | String | obligatorio | política REQ-FUN-01 (se valida en el service) |
| `confirmarContrasena` | String | obligatorio | debe ser idéntica a `contrasena`; la validan cliente y servidor → 400 `VALIDATION_ERROR` con `details` señalando el campo (regla de forma → controller, §4) |

### 3.2 Response de éxito — 201 Created

```json
{
  "message": "Código de verificación enviado al correo."
}
```

Solo el mensaje (mínimo privilegio): no se devuelve correo, ni datos del menor, ni el OTP.

### 3.3 Códigos de estado (éxito y errores)

Formato de error: `ErrorDto` de §5.2 (`timestamp`, `status`, `error`, `message`, `path`,
`details`).

| Status | `error` | Cuándo |
|---|---|---|
| 201 | — | Éxito: `registro_pendiente` creado y OTP enviado (V7) |
| 400 | `VALIDATION_ERROR` | Falla de forma (controller) o de regla (service: contraseña, fecha, etc.); con `details` por campo |
| 400 | `INVALID_REQUEST` | JSON malformado o body ausente (lo produce StatusPages) |
| 409 | `EMAIL_ALREADY_REGISTERED` | Correo activo en `usuario` o pendiente no expirado en `registro_pendiente` |
| 409 | `EMAIL_LOCKED` | Correo de cuenta en soft delete (`estado = 'eliminado'`), bloqueado hasta liberación administrativa |
| 409 | `CONFLICT` | `nombreUsuario` en uso (activo o pendiente) |
| 500 | `INTERNAL_ERROR` | Error inesperado; stack trace solo en servidor |

---

## 3A. Contrato del Módulo A.1 — verificación de correo y reenvío de OTP

**Base normativa:** `requisitos-funcionales.md` REQ-FUN-01 (paso 3, CA4), `casos-de-uso.md`
CU-11, `historias-de-usuario.md` HU-01 CA3 y HU-15 CA2. A.1 cierra el ciclo de registro
iniciado por §3: el pendiente se convierte en la cuenta activa. Las políticas P1 (3 fallos →
código invalidado), P2 (mín. 60 s entre envíos) y P3 (migración V2/V3) se definen en §6.1 y
en `ARQUITECTURA_BASE.md` §5.4; aquí solo se fija su efecto en el contrato.

Formato de error: `ErrorDto` de §5.2 (`timestamp`, `status`, `error`, `message`, `path`,
`details`).

### 3A.1 `POST /api/v1/auth/verify-email`

#### 3A.1.1 Request — `VerifyEmailRequestDto`

| Campo | Tipo | Presencia | Regla |
|---|---|---|---|
| `correo` | String | obligatorio | formato email (§4); ≤ 255; normalizado a minúsculas (V5) |
| `codigo` | String | obligatorio | exactamente 6 dígitos (`^\d{6}$`, REQ-FUN-01 CA4) |

#### 3A.1.2 Response de éxito — 200 OK

```json
{
  "message": "Correo verificado. Cuenta activada."
}
```

Solo el mensaje (mínimo privilegio, CLAUDE.md §6): no se devuelve correo, datos del menor,
ni el OTP. El OTP correcto y vigente dispara la **conversión transaccional** (§2): se crean
`usuario` + `acudiente` + `configuracion` (con defaults) y se consume el pendiente en una
única transacción con `FOR UPDATE` (anti-TOCTOU). La cuenta queda `estado = ACTIVO` (HU-15
CA2).

#### 3A.1.3 Códigos de estado (éxito y errores)

| Status | `error` | Cuándo |
|---|---|---|
| 200 | — | Éxito: OTP correcto y vigente; conversión transaccional y consumo del pendiente |
| 400 | `VALIDATION_ERROR` | Falla de forma (controller): `correo` o `codigo`; con `details` por campo |
| 400 | `INVALID_REQUEST` | JSON malformado o body ausente (lo produce StatusPages) |
| 401 | `OTP_INVALID_OR_EXPIRED` | Código incorrecto, vencido o límite P1 alcanzado; mensaje genérico, no revela la causa (P1) |
| 404 | `NOT_FOUND` | Defensivo: sin `registro_pendiente` ni usuario para el correo |
| 409 | `EMAIL_ALREADY_VERIFIED` | Sin pendiente pero usuario activo: el correo ya fue verificado (decisión del propietario) |
| 500 | `INTERNAL_ERROR` | Error inesperado; stack trace solo en servidor |

### 3A.2 `POST /api/v1/auth/resend-otp`

#### 3A.2.1 Request — `ResendOtpRequestDto`

| Campo | Tipo | Presencia | Regla |
|---|---|---|---|
| `correo` | String | obligatorio | formato email (§4); ≤ 255; normalizado a minúsculas (V5) |

#### 3A.2.2 Response de éxito — 200 OK

```json
{
  "message": "Código de verificación enviado al correo."
}
```

Mensaje **idéntico** al del envío inicial del registro (§3.2) a propósito (anti-enumeración):
se responde 200 con este mensaje aunque no exista un pendiente, y en ese caso **no se envía
nada** — el endpoint nunca confirma si un correo está en proceso de registro. Cuando sí hay
pendiente, el OTP nuevo invalida el anterior (P2) y reinicia `expira_en` a `now + 10 min`.

#### 3A.2.3 Códigos de estado (éxito y errores)

| Status | `error` | Cuándo |
|---|---|---|
| 200 | — | Éxito: OTP regenerado y reenviado, o respuesta genérica sin envío si no hay pendiente |
| 400 | `VALIDATION_ERROR` | Falla de forma (controller): `correo`; con `details` por campo |
| 400 | `INVALID_REQUEST` | JSON malformado o body ausente (lo produce StatusPages) |
| 429 | `OTP_RESEND_THROTTLED` | Reenvío antes de 60 s desde `registro_pendiente.ultimo_envio_en` (P2) |
| 500 | `INTERNAL_ERROR` | Error inesperado; stack trace solo en servidor |

### 3A.3 Decisiones de diseño de A.1

- **A1-D1 — `verify-email` distingue 401/404/409 (tradeoff de anti-enumeración).** A
  diferencia de `resend-otp`, del login (401 genérico) y del Módulo C (respuesta idéntica),
  `verify-email` expone tres estados del correo: 401 (hay pendiente, código inválido), 409
  (cuenta activa ya verificada), 404 (nada). *Justificación:* el OTP es el verdadero gate —
  sin un código correcto y vigente no se activa nada; el estado revelado es de bajo valor y
  queda acotado a la ventana de 10 min del pendiente. El 409, además, evita que el cliente
  reintente un flujo ya consumido. Se mantiene a propósito, con 404 solo donde es seguro
  informar (decisión del propietario, 2026-08-12).
- **A1-D2 — Mensaje de éxito de verify distinto del de envío/reenvío.** "Correo verificado.
  Cuenta activada." comunica que el flujo terminó y no queda OTP pendiente; reutilizar el
  mensaje genérico de §3.2 induciría a error al confirmar un segundo envío.
- **A1-D3 — Contador P1 dentro de la transacción, throw fuera.** El incremento de
  `intentos_fallidos` se persiste dentro del bloque transaccional y la excepción genérica se
  lanza fuera, para que el incremento se commitee (un throw interno haría rollback). Al
  llegar a 3, `OtpService` rechaza cualquier verificación posterior: el código queda
  invalidado hasta un reenvío (P2).

---

## 4. Separación de validaciones: controller vs service

**Controller (forma/tipo/presencia — primera barrera, ARQUITECTURA_BASE §2.2):**

- Presencia y tipos de todos los campos; longitudes máx (120/60/255/20).
- `correo`: regex `^[^@\s]+@[^@\s]+\.[^@\s]+$` y normalización a minúsculas (V5).
- `fechaNacimiento`: formato ISO, parseable a `LocalDate`, no futura (V9).
- `cedulaAcudiente`: 6–20 alfanuméricos (V8).
- `avatar`: si viene, debe ser un identificador de preset válido (`preset:1|2|3`, V6).
- `confirmarContrasena == contrasena`: si no coinciden → 400 `VALIDATION_ERROR` con
  `details = [{"field": "confirmarContrasena", "message": "No coincide con contrasena"}]`.
- **NO decide** unicidad ni fuerza de contraseña.

**¿Por qué `confirmarContrasena` valida en el controller y no en el service?** Por el
criterio de esta sección (forma vs regla de negocio): la coincidencia entre dos campos del
request es consistencia **de forma** del input, no una regla de negocio trazable a un REQ.
Validarla aquí la resuelve antes de tocar el service, evita un hash bcrypt de la contraseña
cuando el request ya es inconsistente, y deja el service concentrado en reglas con efecto de
negocio (unicidad, política de contraseña). El cliente también la valida (REQ-FUN-01 paso 2:
"confirmación de contraseña"), pero el servidor **nunca confía** en la validación del
cliente.

**Service (reglas de negocio — ARQUITECTURA_BASE §2.3):**

- Unicidad de correo contra `usuario` (activo → `EMAIL_ALREADY_REGISTERED`; eliminado →
  `EMAIL_LOCKED`) y `registro_pendiente` no expirado → `EMAIL_ALREADY_REGISTERED`, con
  limpieza lazy de filas expiradas (V2), en una transacción.
- Unicidad de `nombreUsuario` contra `usuario` y `registro_pendiente` → `CONFLICT`.
- Fuerza de contraseña (REQ-FUN-01 CA2): ≥8 caracteres, mayúscula, minúscula, número y
  símbolo; ≠ `nombreUsuario`; sin datos personales (V3) → `ValidationException` con
  `details`.
- Hash bcrypt de contraseña y de OTP; `expira_en = now + 10 min`.
- El service es **puro de Ktor y de SQL**: recibe DTOs, lanza excepciones de dominio y
  delega el OTP a `OtpService`; testeable con repositorios simulados y `OtpNotifier` fake.

---

## 5. OTP — `OtpService` reutilizable (A.1 y C)

El OTP se genera y gestiona en un **servicio independiente reutilizable**, no embebido en
`RegistrationService`:

| Responsabilidad | Detalle |
|---|---|
| `generate()` | 6 dígitos con `SecureRandom` |
| `hash(code)` | bcrypt del código (nunca en texto plano) |
| `send(correo, code)` | delega en la interfaz `OtpNotifier` (impl real `SimpleJavaMailOtpNotifier`; fake en tests que captura el código sin SMTP) |
| `verificar(...)` | bcrypt contra el hash + vigencia 10 min + contador de intentos (P1) |

- `RegistrationService` recibe `OtpService` por inyección: genera, hashea y delega el
  envío; persiste hash + expiración en `registro_pendiente`.
- A.1 (`verify-email`) y C (recuperación de contraseña) reutilizan el mismo `OtpService` /
  `OtpNotifier`. La política de intentos (P1) vive aquí, no en el repositorio.
- Nunca se loguea el OTP ni el correo (CLAUDE.md §6).

---

## 6. Decisiones de esquema y reglas

### 6.1 P1–P3 (aprobadas en `ARQUITECTURA_BASE.md` §5.4)

P1 (3 intentos fallidos → invalidar el código y exigir reenvío), P2 (throttle de reenvío de
60 s → `429 OtpResendThrottledException`) y P3 (migración `V2__otp_resend_tracking.sql`:
`ultimo_envio_en DATETIME NULL` en `registro_pendiente`) están registradas en
`ARQUITECTURA_BASE.md` §5.4 (puntos 3, 4 y 5). Se referencian aquí y se implementarán en
A.1; no se repiten.

### 6.2 Decisiones V1–V9

**V1 — Username de cuenta soft-deleted permanece ocupado.**
REQ-FUN-05 bloquea el *correo* de una cuenta eliminada, pero el username no se libera:
`usuario.nombre_usuario` es UNIQUE y la fila persiste. *Justificación:* la cuenta eliminada
se trata con el mismo criterio de unicidad y se evita la suplantación del nombre visible.
Si algún día se quisiera liberar, requeriría una política explícita (el esquema no cambia:
la fila sigue existiendo).

**V2 — Limpieza lazy de `registro_pendiente` expirado.**
El diccionario indica que el correo "libera automáticamente si expira sin verificar", pero
no existe job de limpieza. *Justificación:* la limpieza lazy ocurre **solo** en el check de
unicidad de `RegistrationService` (§4): si una fila tiene `expira_en` pasado, se elimina y
el nuevo registro se permite. En `verify-email` un pendiente vencido se trata como código
inválido → 401 `OTP_INVALID_OR_EXPIRED` (genérico, P1), y en `resend-otp` la fila vencida
regenera el código sin problema (P2). Ninguno de los dos borra la fila ni lo necesita: el
correo se libera en el siguiente intento de registro. Evita un scheduler para un caso borde
y mantiene la invariante de unicidad sin reservas muertas.

**V3 — Qué cuenta como "dato personal" en la contraseña (REQ-FUN-01 CA2).**
*Justificación:* la especificación no define los datos; se adopta la interpretación mínima
defendible: la contraseña no debe contener, *case-insensitive*, el `nombreMenor` (ni los
tokens del nombre) ni ser igual a `nombreUsuario`. No se valida contra correo ni cédula para
no sobrerestringir.

**V4 — Longitud del username: 3–60 sin espacios.**
*Justificación:* REQ-FUN-01 no fija mínimo; el esquema permite 60. Se adopta 3 como mínimo
visible y legible, 60 como máximo del esquema, y se prohíben espacios (nombre visible
único).

**V5 — Normalización del correo a minúsculas.**
*Justificación:* la collation `utf8mb4_unicode_ci` ya es case-insensitive en la UNIQUE, pero
normalizar en el service da consistencia programática (comparaciones, envíos SMTP, checks de
unicidad) y evita duplicados visuales.

**V6 — Avatar: identificador de preset, no foto personalizada.**
El backend define 3 identificadores **opacos** para los avatares preestablecidos de la app
(la imagen en sí vive solo en el cliente, REQ-FUN-06): constante/enum `AvatarPreset` con los
valores `preset:1`, `preset:2`, `preset:3`. El campo `avatar` del registro es opcional y, si
viene, **debe** ser uno de esos 3 valores; cualquier otro contenido se rechaza con 400
`VALIDATION_ERROR` (regla de forma → controller). *Por qué este formato:* ningún doc oficial
define el identificador; se propone el prefijo `preset:` como **prefijo reservado** que
distingue por construcción un preset (lógica local del cliente) de una foto personalizada
(Módulo I) — una foto **nunca** llega por este endpoint. **El registro ya no acepta foto
personalizada:** esa función es exclusiva del Módulo I (`PUT /api/v1/users/me/avatar`),
disponible solo después de verificar el correo (decisión del propietario 2026-08-05;
`ARQUITECTURA_BASE.md` §5.4 decisión 7; `DICCIONARIO_DATOS.md` `usuario.avatar`).

**V7 — Status HTTP de éxito: 201 Created.**
*Justificación:* el endpoint no crea un recurso addressable (el usuario no existe aún), pero
sí crea una fila en `registro_pendiente`; 201 comunica la creación pendiente. Se adopta 201
sobre la alternativa 200 OK.

**V8 — Cédula del acudiente: 6–20 caracteres alfanuméricos.**
*Justificación:* REQ-FUN-01 no fija formato de la cédula; el esquema la limita a
`VARCHAR(20)`. Se adopta 6 como mínimo (documentos de identidad típicos no bajan de ese
tamaño), 20 como máximo del esquema y se permite alfanumérico (algunas cédulas llevan
sufijos o formato variado). Solo se valida formato y longitud, nunca el contenido. Es regla
de forma → controller. Dato sensible (CLAUDE.md §6): nunca se loguea ni se expone en
respuestas.

**V9 — Fecha de nacimiento: válida y no futura, sin rango de edad.**
*Justificación:* REQ-FUN-01 CA3 exige fecha válida con edad calculada dinámicamente; ningún
REQ define rango mínimo/máximo de edad. Se adopta: formato ISO `yyyy-MM-dd`, parseable a
`LocalDate` y no futura. La edad se calcula en el cliente y no se persiste; el backend no
aplica rango de edad (evita sobrerestringir y desincronizar con la edad real). Es regla de
forma → controller.

---

## 7. Excepciones de dominio usadas

(ARQUITECTURA_BASE §5.3 + `exceptions/ModuleExtensions.kt`):

| Excepción | Status | Uso |
|---|---|---|
| `ValidationException` | 400 | Forma y reglas de contraseña/fecha; con `details` |
| `EmailAlreadyRegisteredException` | 409 | Correo ya en uso activo o pendiente no expirado |
| `EmailLockedException` | 409 | Correo de cuenta en soft delete |
| `ConflictException` | 409 | `nombreUsuario` en uso |
| `OtpResendThrottledException` | 429 | Reenvío antes de 60 s (aplica en A.1) |
| `OtpInvalidException` | 401 | OTP incorrecto o vencido en `verify-email`; mensaje genérico (P1) |
| `EmailAlreadyVerifiedException` | 409 | Correo ya verificado: el pendiente fue consumido y la cuenta está activa |
| `NotFoundException` | 404 | Defensivo: no hay `registro_pendiente` para el correo (solo donde es seguro informar) |

---

## 8. Trazabilidad REQ-FUN-01 ↔ CU-01 ↔ HU-01/HU-15

| Requisito / criterio | Caso de uso | Historia de usuario | Dónde se cumple |
|---|---|---|---|
| REQ-FUN-01 Registro | CU-01 `<<include>>` CU-11 | HU-01, HU-15 | Secciones 3, 4, 5 |
| REQ-FUN-01 CA1 (correo único activo) | CU-01 | HU-01 CA1 | §2, §4 service |
| REQ-FUN-01 CA2 (contraseña: ≥8, may/min/núm/símbolo; ≠ username; sin datos personales) | CU-01 | HU-01 CA2, HU-15 CA3 | §4 service, V3 |
| REQ-FUN-01 paso 2 (avatar y confirmación de contraseña) | CU-01 | — | §3.1 (`avatar`, `confirmarContrasena`), §4 controller, V6 |
| REQ-FUN-01 CA3 (fecha válida; edad dinámica) | CU-01 | — | §3.1, §4 controller, V9 |
| REQ-FUN-01 CA4 (OTP 6 dígitos, 10 min, reenviable) | CU-11 | HU-01 CA3 | §5, P1–P3 |
| REQ-FUN-01 CA5 (fallo de conexión conserva datos) | CU-01 flujo alt. | HU-01 CA4 | Lado cliente (sin efecto backend) |
| REQ-FUN-01 CA6 (conexión requerida para registrarse) | CU-01 flujo alt. | — | Lado cliente (sin efecto backend) |
| HU-15 CA1 (datos del acudiente en paso 1) | CU-01 | HU-15 | §3.1 (`nombreAcudiente`, `cedulaAcudiente`, V8) |
| HU-15 CA2 (cuenta no activa hasta verificar) | CU-01 | HU-15 | §2 (pending + verify transaccional) |
| HU-15 CA3 (bcrypt, sin cifrado reversible) | CU-01 | HU-15 | §4 (hash bcrypt de contraseña y OTP) |
