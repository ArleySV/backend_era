# ERA — Módulo B (Login) — Análisis funcional

> Documentación oficial del sistema ERA. Ver [`../CLAUDE.md`](../CLAUDE.md) para las reglas
> permanentes y la matriz de trazabilidad.
>
> **Base normativa:** [`requisitos-funcionales.md`](./requisitos-funcionales.md) (REQ-FUN-02),
> [`requisitos-no-funcionales.md`](./requisitos-no-funcionales.md) (REQ-NF-02),
> [`casos-de-uso.md`](./casos-de-uso.md) (CU-04),
> [`historias-de-usuario.md`](./historias-de-usuario.md) (HU-02),
> [`ARQUITECTURA_BASE.md`](./ARQUITECTURA_BASE.md) (§2.3, §5.3, §5.4) y
> [`DICCIONARIO_DATOS.md`](./DICCIONARIO_DATOS.md).
>
> Este documento registra el **análisis funcional aprobado** del Módulo B (aprobación
> 2026-08-08). La estructura de archivos y el orden de trabajo se definen en §10 y se
> implementan capa por capa siguiendo `ARQUITECTURA_BASE.md` §3.

---

## 1. Alcance y actores

**Alcance:** Módulo B (Login). Un solo endpoint: `POST /api/v1/auth/login`.

**Actor principal:** menor de edad con cuenta registrada, correo verificado y estado
`activo` (HU-15 CA2: la cuenta no existe en `usuario` hasta la verificación; un
`registro_pendiente` no puede iniciar sesión).

**Precondición:** la cuenta está activa en la base de datos.

**Flujo (CU-04):**
1. El usuario ingresa su nombre de usuario **o** correo electrónico y su contraseña.
2. El sistema valida las credenciales contra `usuario`.
3. Si son válidas y la cuenta está activa, se emite un token de sesión JWT (30 días) y se
   responde 200.
4. El cliente persiste el token localmente (sesión persistente, REQ-FUN-02 CA1 /
   HU-02 CA1).

**Postcondición:** el usuario queda autenticado con un token de sesión. **No se crea
estado de sesión en el servidor** (ARQUITECTURA_BASE §2.3, REQ-FUN-04): el login solo lee
`usuario` y actualiza el contador de intentos y la ventana de bloqueo.

---

## 2. Regla de bloqueo (REQ-FUN-02 CA3, REQ-NF-02)

Las columnas `usuario.intentos_login_fallidos` (TINYINT, default 0) y
`usuario.bloqueado_hasta` (DATETIME NULL) ya existen en V1: **no se requiere migración**
(B-7).

**Regla:** tras 5 intentos fallidos **consecutivos**, la cuenta queda bloqueada durante
2 minutos. Los mensajes de error de credenciales **nunca** indican qué campo falló
(REQ-FUN-02, REQ-NF-02).

**Semántica de la ventana (B-2):**
- `bloqueado_hasta > now` → cuenta bloqueada; toda petición responde 423 sin verificar
  bcrypt ni incrementar el contador.
- `bloqueado_hasta <= now` → ventana expirada: el siguiente intento limpia el estado
  (contador = 0, ventana = NULL) y el contador parte de 0 (limpieza lazy, mismo criterio
  que V2).

---

## 3. Contrato de `POST /api/v1/auth/login`

Ruta: `/api/v1/auth/login` (nomenclatura §5.1 de ARQUITECTURA_BASE).

### 3.1 Request — `LoginRequestDto`

| Campo | Tipo | Presencia | Regla |
|---|---|---|---|
| `usuarioOCorreo` | String | obligatorio | identificador de login: nombre de usuario **o** correo (B-1); no blanco; ≤ 255 |
| `contrasena` | String | obligatorio | contraseña del usuario; no blanco; ≤ 72 (tope técnico de bcrypt) |

### 3.2 Response de éxito — 200 OK

```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
}
```

Solo el token (mínimo privilegio, CLAUDE.md §6). Los datos del perfil (username, avatar,
correo) se consultan por `GET /api/v1/users/me` (Módulo D); no se duplican aquí.

### 3.3 Códigos de estado (éxito y errores)

Formato de error: `ErrorDto` de §5.2 (`timestamp`, `status`, `error`, `message`, `path`,
`details`).

| Status | `error` | Cuándo |
|---|---|---|
| 200 | — | Credenciales correctas y cuenta activa → token emitido |
| 400 | `VALIDATION_ERROR` | Falla de forma (controller): identificador/contraseña vacíos o demasiado largos |
| 400 | `INVALID_REQUEST` | JSON malformado o body ausente (lo produce StatusPages) |
| 401 | `INVALID_CREDENTIALS` | Credenciales incorrectas o identificador inexistente; **mensaje genérico** (no revela qué campo falló ni si la cuenta existe) |
| 403 | `ACCOUNT_INACTIVE` | Cuenta en soft delete (`estado = 'eliminado'`), solo tras contraseña correcta (B-5) |
| 423 | `ACCOUNT_LOCKED` | Cuenta bloqueada por 5 intentos fallidos (2 min) |
| 500 | `INTERNAL_ERROR` | Error inesperado; stack trace solo en servidor |

---

## 4. Separación de validaciones: controller vs service

**Controller (forma/tipo/presencia — primera barrera, ARQUITECTURA_BASE §2.2):**
- `usuarioOCorreo` y `contrasena` no blancos.
- `usuarioOCorreo` ≤ 255; `contrasena` ≤ 72.
- **NO decide** bloqueo, genericidad de errores ni estado de la cuenta.

**Service (reglas de negocio — ARQUITECTURA_BASE §2.3):**
- Resolución del identificador (correo vs username, B-1).
- Ventana de bloqueo y contador (B-2/B-3).
- Verificación bcrypt (hash de un solo sentido, REQ-NF-02).
- Estado de soft delete (B-5).
- Emisión del token de sesión (delegada en `JwtTokenService`, §6).
- Error de credenciales **genérico** (REQ-FUN-02).

El service es **puro de Ktor y de SQL** (recibe DTOs, lanza excepciones de dominio, delega
la BD en repositorios y el JWT en `JwtTokenService`); testeable con fakes.

---

## 5. Flujo del `LoginService` y atomicidad

```
login(request):
  identificador = trim(usuarioOCorreo)

  transactionRunner.run {                     // ← UNA transacción (auditoría #2)
    u = buscarPorIdentificador(identificador) // FOR UPDATE (anti-lost-update)
    si u == null: resultado = NO_ENCONTRADO; return@run

    // B-2: ventana activa → bloqueado, sin tocar bcrypt
    si bloqueadoHasta > now: resultado = BLOQUEADO; return@run
    // B-2: ventana expirada → limpieza lazy
    si bloqueadoHasta != null: actualizarEstadoLogin(id, 0, null)

    si !BCrypt.verify(contrasena, u.contrasenaHash):
      nuevos = intentosLoginFallidos + 1
      si nuevos >= 5:                         // B-3: el 5.º fallo abre la ventana
        actualizarEstadoLogin(id, 0, now + 2min); resultado = BLOQUEADO_TRAS_INTENTO
      si no:
        actualizarEstadoLogin(id, nuevos, null); resultado = CREDENCIALES_INVALIDAS
      return@run

    // Éxito: reset atómico del contador y la ventana, EN la misma transacción
    actualizarEstadoLogin(id, 0, null)
    resultado = (estado == ELIMINADO) ? CUENTA_INACTIVA : AUTENTICADO(u)  // B-5
  }

  según resultado:
    NO_ENCONTRADO        → BCrypt.verify(contrasena, HASH_DUMMY)  // B-4
                           throw INVALID_CREDENTIALS
    BLOQUEADO            → throw ACCOUNT_LOCKED
    BLOQUEADO_TRAS_INTENTO → throw ACCOUNT_LOCKED
    CREDENCIALES_INVALIDAS → throw INVALID_CREDENTIALS
    CUENTA_INACTIVA      → throw ACCOUNT_INACTIVE
    AUTENTICADO(u)       → LoginResponseDto(jwtService.emitir(u.idUsuario))
```

**Atomicidad (auditoría #2):** la lectura con `FOR UPDATE`, la verificación bcrypt y la
**escritura del estado de login** (incremento por fallo, apertura de ventana o **reset tras
éxito: `intentos_login_fallidos = 0` y `bloqueado_hasta = NULL`**) ocurren en la misma
transacción. El `throw` de la excepción de dominio se hace **fuera** del bloque
`transactionRunner.run { }` para que la escritura commitee (mismo patrón que
`VerificationService.verificarEmail` con P1). Un login exitoso nunca deja un contador ni
una ventana residuales.

---

## 6. JWT de sesión — `JwtTokenService`

Servicio independiente e inyectado en `LoginService`, con una sola responsabilidad:
**emitir** el token de sesión. Su validación la hará el plugin `Authentication(JWT)` en los
Módulos D/F/G/H; aquí solo se genera.

**Algoritmo y claims:**

| Claim | Valor |
|---|---|
| `alg` | HS256 (`Algorithm.HMAC256(secret)`) |
| `sub` | `id_usuario` (String) |
| `iss` | `jwt.session.issuer` = `era-backend` |
| `aud` | `jwt.session.audience` = `era-app-session` |
| `iat` | now |
| `exp` | now + `jwt.session.expirationMinutes` = **43200 min (30 días)** (ARQ §5.4 #2) |
| `jti` | `UUID.randomUUID().toString()` — **único por emisión** (auditoría #3) |

**Origen del secreto (auditoría #3):** `JwtTokenService` recibe `JwtConfig` por inyección;
su `secret` se resuelve **exclusivamente** de `${JWT_SECRET}` en `resources/application.yaml`
vía `AppConfigLoader` (variable de entorno / system property). **No hay secretos
hardcodeados, ni fallback, ni derivación.** Un único secreto compartido para sesión y reset
token; la diferenciación vive en `audience` (ARQ §5.4 #1).

**Sin datos sensibles:** el token no lleva correo, cédula, fecha de nacimiento ni datos del
menor (mínimo privilegio, CLAUDE.md §6). Nunca se loguea el token ni su contenido.

---

## 7. Decisiones aprobadas B-1…B-7

**B-1 — Identificador único (`usuarioOCorreo`).** REQ-FUN-02 admite login por username **o**
correo. Un solo campo: si contiene `@` → se busca por correo (normalizado a minúsculas, V5)
con *fallback* a username; si no contiene `@` → se busca por username. Determinista: evita
la ambigüedad de que el correo de un usuario coincida con el username de otro (la unicidad
de cada columna es independiente).

**B-2 — Ventana de bloqueo con limpieza lazy.** `bloqueado_hasta` define la ventana; al
expirar, el siguiente intento resetea contador y ventana (mismo criterio que V2). Durante la
ventana no se verifica bcrypt ni se incrementa el contador.

**B-3 — El 5.º fallo responde 423.** El intento que alcanza 5 fallos consecutivos abre la
ventana (2 min) y responde `ACCOUNT_LOCKED`; los intentos dentro de la ventana también
responden 423. El contador vuelve a 0 al abrir la ventana, de modo que tras su expiración la
cuenta parte de cero fallos.

**B-4 — Anti-enumeración por timing: hash dummy pre-calculado (auditoría #1).** Cuando el
identificador no existe, se ejecuta `BCrypt.verify(contrasena, HASH_DUMMY)` con un **hash
bcrypt pre-calculado en tiempo de compilación** (`private const val`), del mismo coste (12)
que los hashes reales. *Por qué:* normaliza el tiempo de respuesta entre "usuario
inexistente" y "contraseña incorrecta" sin generar un hash en cada request (cero CPU extra,
cero `SecureRandom`); los tiempos son idénticos por construcción porque el coste del verify
es el mismo. El hash dummy se genera una sola vez (Paso 1 de la implementación) y se
embedde como constante; nunca se loguea.

**B-5 — Estado de soft delete evaluado solo tras contraseña correcta (privacidad de
estado).** Con credenciales erróneas, una cuenta `eliminado` responde el mismo 401 genérico
que cualquier otra (no se confirma ni la existencia ni el estado). Solo tras verificar la
contraseña se responde 403 `ACCOUNT_INACTIVE` (REQ-FUN-05 CA5). Evita que el endpoint de
login funcione como oráculo de enumeración.

**B-6 — Username case-insensitive en el login.** La collation `utf8mb4_unicode_ci` ya trata
el UNIQUE como case-insensitive; el login replica ese comportamiento consultando con
`lower(nombre_usuario) = lower(?)` para que "Maria" y "maria" sean el mismo usuario (mismo
espejo en el fake).

**B-7 — Sin migración ni dependencias nuevas.** Contador y ventana ya están en V1. El JWT
usa `com.auth0:java-jwt`, dependencia **transitiva** de `$ktor.server.auth.jwt` (ya
declarada en `module.yaml`); no se agrega nada a `module.yaml` ni a `libs.versions.toml`.

---

## 8. Excepciones de dominio

Se reutilizan las ya aprobadas en `exceptions/CoreExceptions.kt` (ARQUITECTURA_BASE §5.3);
**no se agrega ninguna nueva**:

| Excepción | Status | Uso |
|---|---|---|
| `ValidationException` | 400 | Forma inválida (controller), con `details` |
| `InvalidCredentialsException` | 401 | Credenciales incorrectas o usuario inexistente → mensaje genérico |
| `AccountInactiveException` | 403 | Cuenta en soft delete (tras contraseña correcta, B-5) |
| `AccountLockedException` | 423 | Bloqueo de 2 min tras 5 intentos fallidos |

---

## 9. Trazabilidad REQ-FUN-02 ↔ CU-04 ↔ HU-02

| Requisito / criterio | Caso de uso | Historia de usuario | Dónde se cumple |
|---|---|---|---|
| REQ-FUN-02 Login | CU-04 | HU-02 | §3, §4, §5, §6 |
| REQ-FUN-02 CA1 (sesión persistente si no hubo logout) | CU-04 flujo alt. 4a | HU-02 CA1 | §6 (token 30 días, ARQ §5.4 #2) |
| REQ-FUN-02 CA2 (validar usuario/correo y contraseña) | CU-04 paso 3 | HU-02 CA2 | §5 (lookup B-1 + bcrypt) |
| REQ-FUN-02 CA3 (5 fallos → bloqueo 2 min) | CU-04 flujo alt. 3b | HU-02 CA3 | §2, §5 (B-2/B-3) |
| REQ-NF-02 (bcrypt; error genérico; bloqueo) | CU-04 | HU-02 | §5, §8 |
| REQ-FUN-05 CA5 (cuenta eliminada no inicia sesión) | CU-04 flujo alt. | HU-05 CA4 | §5, §7 B-5 (403 `ACCOUNT_INACTIVE`) |

---

## 10. Plan de implementación (capa por capa)

Orden de trabajo (cada capa requiere su confirmación):

1. **DTOs + `JwtTokenService`** (esta iteración):
   - `models/dto/LoginRequestDto.kt`, `models/dto/LoginResponseDto.kt`.
   - `services/JwtTokenService.kt` (emite el JWT con `JwtConfig`; HS256; `jti` único por
     emisión; sin logs; constante `HASH_DUMMY` si se decide residir aquí o en `LoginService`).
2. **Repositorio** `UsuarioRepository` + `ExposedUsuarioRepository` + `FakeUsuarioRepository`:
   - `findByEmailForUpdate(correo)`, `findByUsernameForUpdate(nombreUsuario)`
     (case-insensitive, B-6),
   - `actualizarEstadoLogin(idUsuario, intentosLoginFallidos, bloqueadoHasta)`.
3. **Service** `LoginService` (flujo §5; `TransactionRunner`; `HASH_DUMMY` B-4;
   `JwtTokenService` inyectado).
4. **Controller + Routes**: `AuthController.login()` (forma §4) y `post("/login")` en
   `AuthRoutes`.
5. **Wiring** en `Application.kt` y **tests** (`LoginServiceTest` unit + `AuthControllerLoginTest`
   TestHost).
