# ERA — Módulo F (Cierre de sesión) — Análisis funcional

> Documentación oficial del sistema ERA. Ver [`../CLAUDE.md`](../CLAUDE.md) para las reglas
> permanentes y la matriz de trazabilidad.
>
> **Base normativa:** [`requisitos-funcionales.md`](./requisitos-funcionales.md)
> (REQ-FUN-04), [`casos-de-uso.md`](./casos-de-uso.md) (CU-05),
> [`historias-de-usuario.md`](./historias-de-usuario.md) (HU-04),
> [`ARQUITECTURA_BASE.md`](./ARQUITECTURA_BASE.md) (§1, §3, §5.1, §5.3, §5.4 #2) y
> [`DICCIONARIO_DATOS.md`](./DICCIONARIO_DATOS.md).
>
> Este documento registra el **análisis funcional aprobado** del Módulo F (Cierre de
> sesión, REQ-FUN-04) y su **implementación** (Paso 1: estructura y lógica; Paso 2: tests;
> Paso 3: wiring; Paso 4: esta documentación). La decisión central — el **logout
> stateless** — se detalla en §2 y §3 porque es la que explica *por qué* el endpoint hace
> lo que hace y, sobre todo, *por qué no* hace más (no hay blacklist, no hay estado).

---

## 1. Alcance y actores

**Alcance:** un único endpoint, protegido por **JWT de sesión** (Módulo B):

| Endpoint | Función |
|---|---|
| `POST /api/v1/auth/logout` | Cierre de sesión del usuario autenticado (Módulo F, REQ-FUN-04, CU-05, HU-04). |

**Actor principal:** el menor de edad autenticado, que decide finalizar su sesión desde el
sidebar de la app (REQ-FUN-08).

**Precondición:** el cliente posee un JWT de sesión válido y vigente (login, Módulo B). La
ruta vive dentro de `authenticate("session-jwt")` (ARQUITECTURA_BASE.md §3: el proveedor
JWT se aplica a los módulos D/E/F/G/H, no a A/A.1/B/C); sin token válido no se llega al
controller.

**Flujo (CU-05):**
1. El usuario elige "Cerrar sesión" en el sidebar.
2. El cliente Android llama a `POST /api/v1/auth/logout` enviando el token de sesión en el
   header `Authorization: Bearer <token-sesion>`.
3. El backend **verifica la sesión** (proveedor `session-jwt`), **registra el cierre en el
   log** con el `id_usuario` y responde **200 OK** con la confirmación formal.
4. El cliente **descarta el token localmente** y redirige al login (REQ-FUN-04 CA2).

**Postcondición:** la sesión queda finalizada **desde el punto de vista del cliente** (el
token deja de usarse). El servidor **no conserva ni modifica ningún estado**: ninguna
tabla se toca, el progreso y los datos del usuario se conservan intactos (REQ-FUN-04 CA4,
regla 7 de CLAUDE.md §4).

---

## 2. La decisión central: logout **stateless**

> **La responsabilidad de la invalidación del token es del cliente Android. El backend
> solo actúa como confirmación formal del cierre.**

Esta es la decisión que da forma a todo el módulo y conviene explicitarla una y otra vez
para que el próximo desarrollador no la "corrija" hacia un modelo con estado:

- **No existe sesión server-side.** El backend no mantiene una tabla de sesiones ni un
  caché de "sesiones activas" (ARQUITECTURA_BASE.md §5.4 #2). El único estado es el token
  JWT en poder del cliente.
- **Por lo tanto, "cerrar una sesión" no es algo que el servidor pueda hacer realmente**:
  no hay estado que invalidar en el servidor. El token sigue siendo criptográficamente
  válido hasta su `exp` (30 días) para quien lo conserve.
- **El endpoint NO revoca, CONFIRMA.** Su función es doble:
  1. **Confirmar formalmente el cierre** (`200 OK`): el cliente necesita la certeza de que
     el servidor reconoció la sesión para proceder con el descarte del token y la
     redirección al login sin ambigüedad.
  2. **Registrar el evento** en el log de aplicación (auditoría) con el `id_usuario` —
     nunca el token, el correo ni la cédula (CLAUDE.md §6).
- **El endpoint es idempotente:** repetir la petición con el mismo token vuelve a responder
  `200 OK`. No hay efecto observable en el servidor entre una llamada y otra.

**Qué NO hace el endpoint (y por qué):** no consulta la BD (no necesita saber quién es el
usuario más allá de su `id_usuario`, que ya viene firmado en el token), no comprueba el
estado de la cuenta (un token de una cuenta `eliminado` también recibe `200 OK`; el logout
no opera sobre la cuenta), no escribe nada y no emite ningún token nuevo.

---

## 3. Riesgo aceptado — por qué **no** hay blacklist

El diseño stateless asume un riesgo que fue **explícitamente aceptado** en la arquitectura
base y que este módulo hereda tal cual. Cita textual de `ARQUITECTURA_BASE.md` §5.4 #2:

> **RIESGO ACEPTADO (decisión consciente, no implícita):** si el dispositivo del menor
> se pierde o es robado, el token de sesión sigue siendo válido hasta 30 días **sin que
> el backend tenga forma de revocarlo**, porque el logout solo invalida el token
> localmente. Se acepta este trade-off dado el perfil de riesgo del proyecto (app
> educativa infantil, sin datos financieros ni backend de sesiones). Queda registrado
> para que el equipo lo reconsidere si el perfil de riesgo cambia (p. ej. si se agregan
> datos más sensibles en el futuro).

**Consecuencia directa: NO se implementa una blacklist de tokens revocados.** La opción
alternativa (Opción B) implicaba:

- una **tabla nueva** (`tokens_denegados` o similar) o un almacén tipo Redis — rompe el
  alcance cerrado de CLAUDE.md §2 (las 12 tablas del esquema son fijas) y agrega
  infraestructura;
- verificar cada petición protegida contra esa lista — coste y superficie de ataque
  adicionales;
- y contradice la decisión aprobada §5.4 #2, que **ya registró el riesgo y lo aceptó**.

Si el perfil de riesgo cambiara (nuevos datos sensibles), la reconsideración debe ocurrir
en la arquitectura (§5.4 #2), no como un parche silencioso dentro del logout.

---

## 4. Contrato del endpoint

Ruta: `POST /api/v1/auth/logout` (nomenclatura §5.1 de ARQUITECTURA_BASE, bajo el grupo
de autenticación).

### 4.1 Request

**Sin body.** Autenticación vía header `Authorization: Bearer <token-sesion>` (JWT HS256,
`aud = era-app-session`). No hay DTO de entrada ni campos que validar: la identidad proviene
del token (`SesionPrincipal.idUsuario`).

### 4.2 Response de éxito — 200 OK — `MensajeResponseDto`

```json
{ "message": "Sesión cerrada." }
```

`MensajeResponseDto` es el DTO genérico reutilizable (`message`) compartido con el resto de
módulos; no expone ningún dato personal ni el token (CLAUDE.md §6).

### 4.3 Códigos de estado (formato `ErrorDto` de §5.2 de ARQUITECTURA_BASE)

| Status | `error` | Cuándo |
|---|---|---|
| 200 | — | Éxito: cierre confirmado; el cliente descarta el token localmente |
| 401 | `UNAUTHORIZED` | Token ausente, malformado, expirado, de audiencia `era-app-reset` o con claim `purpose` (challenge del plugin, D-6 del Módulo D) |
| 500 | `INTERNAL_ERROR` | Error inesperado; stack trace solo en servidor |

No hay 400 ni 404: sin body que validar y sin recurso que buscar. Tampoco 403: el logout no
evalúa el estado de la cuenta (§2). Solo llegan a `logout` peticiones con un token de sesión
válido; el resto muere en el `challenge` del proveedor.

---

## 5. Seguridad y logs

- **Log INFO con el `id_usuario` únicamente** (`log.info("Cierre de sesión del usuario
  idUsuario={}", idUsuario)`). Nunca se loguea el token, el header `Authorization`, el
  correo ni la cédula (CLAUDE.md §6).
- **Mínimo privilegio en ambas direcciones:** sin body de entrada (menos superficie de
  validación/ataque) y respuesta sin datos personales (solo `message`).
- **El token jamás viaja en la respuesta ni en el log:** el descarte es responsabilidad
  exclusiva del cliente (REQ-FUN-04 CA2).

---

## 6. Diseño técnico por capas (Paso 1 — implementado)

### 6.1 `services/LogoutService.kt`

Service mínimo, **puro de Ktor y de SQL** (mismo patrón de capas que el resto de módulos,
ARQUITECTURA_BASE §3). Sin repositorios ni transacciones: no hay datos que tocar.

```kotlin
class LogoutService {

    private val log = LoggerFactory.getLogger(LogoutService::class.java)

    fun cerrarSesion(idUsuario: Long): MensajeResponseDto {
        log.info("Cierre de sesión del usuario idUsuario={}", idUsuario)
        return MensajeResponseDto(MENSAJE_SESION_CERRADA)
    }

    companion object {
        const val MENSAJE_SESION_CERRADA = "Sesión cerrada."
    }
}
```

### 6.2 `controllers/AuthController.kt` — handler `logout`

- Nuevo quinto parámetro del constructor: `logoutService: LogoutService` (afecta al wiring
  de `Application.kt` y a todos los tests que construyen el controller).
- `suspend fun logout(call: ApplicationCall)`: rescata el `SesionPrincipal` del contexto
  (`call.principal<SesionPrincipal>()`), delega en `logoutService.cerrarSesion(sesion.idUsuario)`
  y responde `200 OK` con el `MensajeResponseDto`.

### 6.3 `routes/AuthRoutes.kt` — `post("/logout")` bajo `authenticate("session-jwt")`

Único endpoint del grupo `auth/*` que exige sesión. Vive en su **propio bloque**
`authenticate("session-jwt")` dentro de `route("/api/v1/auth")`, separado de los endpoints
públicos (register/verify/login/password-reset), porque ARQUITECTURA_BASE §3 solo protege
D/E/F/G/H:

```kotlin
route("/api/v1/auth") {
    post("/register") { authController.register(call) }
    // ... verify-email, resend-otp, login, password-reset/request|verify|confirm
    authenticate("session-jwt") {
        post("/logout") { authController.logout(call) }
    }
}
```

### 6.4 `Application.kt` — wiring

```kotlin
val logoutService = LogoutService()
val authController = AuthController(
    registrationService, verificationService, loginService,
    passwordResetService, logoutService,
)
```

Sin cambios en `module.yaml` ni `libs.versions.toml`: se reutiliza `$ktor.server.auth.jwt`
(ya declarado), `SesionPrincipal` y `MensajeResponseDto` (ya existentes).

---

## 7. Tests (Paso 2 — implementado)

| Suit | Tests | Cobertura |
|---|---|---|
| `services/LogoutServiceTest.kt` | 2 | Contrato: devuelve la confirmación formal (`"Sesión cerrada."`); idempotencia y no dependencia del `idUsuario` en el resultado. |
| `routes/AuthControllerLogoutTest.kt` | 6 | 200 con token de sesión (y `MensajeResponseDto`); sin body; sin exposición de `idUsuario`/token en la respuesta; idempotencia; 401 sin token; 401 con token de reseteo (`era-app-reset`); 401 con token de sesión + claim `purpose` (defensa en profundidad del `validate`). |

**Ajuste estructural de los tests existentes:** al añadir un bloque `authenticate` a
`authRoutes`, los 4 suites que montan `routing { authRoutes(...) }` sin instalar el plugin
de autenticación lanzaban `MissingApplicationPluginException` (el `authenticate` exige el
proveedor instalado). Se añadió `configureAuthentication(JWT_CONFIG_TEST)` en
`AuthControllerTest`, `AuthControllerVerificationTest`, `AuthControllerLoginTest` y
`AuthControllerPasswordResetTest` (sin cambio de comportamiento: los endpoints públicos no
usan `authenticate`).

**Resultado:** `.\kotlin build` exitoso; `.\kotlin test` → **152/152 tests verdes** (144
previos + 8 nuevos). Env vars placeholder de `.env.example` requeridas para
`ConfigLoadTest` (`PORT`, `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`,
`JWT_SECRET`, `SMTP_*`).

---

## 8. Trazabilidad

| Requisito / criterio | Caso de uso | Historia | Dónde se cumple |
|---|---|---|---|
| REQ-FUN-04 Cierre de sesión | CU-05 | HU-04 | §1, §4 |
| REQ-FUN-04 CA2 (invalidación local del token) | CU-05 | HU-04 | §2 (decisión stateless; la invalidación la ejecuta el cliente) |
| REQ-FUN-04 CA3 (el servidor reconoce el cierre) | CU-05 | HU-04 | §4.2 (200 OK con `MensajeResponseDto`) |
| REQ-FUN-04 CA4 (datos y progreso se conservan) | CU-05 | HU-04 | §1, §2 (ninguna tabla se toca) |
| REQ-NF-02 (seguridad: JWT de sesión, mínimo privilegio, cero logs sensibles) | CU-05 | HU-04 | §4, §5 |

---

## 9. Migración y datos

**Sin migración nueva.** El logout no persiste ni lee nada: ninguna tabla, columna o índice
nuevo. **Sin dependencias nuevas.** Se reutiliza el proveedor `session-jwt` y el DTO
`MensajeResponseDto` (regla §5.4 #4 de CLAUDE.md: toda dependencia exige explicación y
aprobación previa; aquí no se agrega ninguna).

---

## 10. Plan de implementación y estado final

| Paso | Contenido | Estado |
|---|---|---|
| 1 | **Estructura y lógica:** `LogoutService` + handler `logout` en `AuthController` + ruta `post("/logout")` bajo `authenticate("session-jwt")` | **Implementado** (§6) |
| 2 | **Tests:** `LogoutServiceTest` (unitario) + `AuthControllerLogoutTest` (ruta) + ajuste de los 4 suites con `authRoutes` | **Implementado** (§7) |
| 3 | **Wiring final:** `LogoutService` en el constructor de `AuthController` en `Application.kt` | **Implementado** (§6.4) |
| 4 | **Documentación:** `docs/modulo-f-analisis.md` + README + CLAUDE.md §9 | **Implementado** (este documento) |

**Verificación transversal:** cero logs de token, correo, cédula o contraseña (CLAUDE.md
§6); el endpoint no expone datos personales y no toca la base de datos; la suite completa
de tests queda en verde (152/152).
