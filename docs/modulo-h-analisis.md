# ERA — Módulo H (Comentarios / Feedback) — Análisis funcional

> Documentación oficial del sistema ERA. Ver [`../CLAUDE.md`](../CLAUDE.md) para las reglas
> permanentes y la matriz de trazabilidad.
>
> **Base normativa:** [`requisitos-funcionales.md`](./requisitos-funcionales.md)
> (REQ-FUN-14), [`casos-de-uso.md`](./casos-de-uso.md) (CU-10, CU-12),
> [`historias-de-usuario.md`](./historias-de-usuario.md) (HU-14),
> [`ARQUITECTURA_BASE.md`](./ARQUITECTURA_BASE.md) (§3, §3.1, §5.1, §5.2) y
> [`DICCIONARIO_DATOS.md`](./DICCIONARIO_DATOS.md) (tabla `comentario`).
>
> Este documento registra el **análisis funcional aprobado** del Módulo H (Comentarios,
> REQ-FUN-14, CU-10, HU-14) y el **contrato** de su único endpoint. La decisión central —
> **el backend NO sirve las FAQ** (son locales y offline) y **solo recibe y persiste
> comentarios** — se detalla en §2 porque define el alcance cerrado del módulo (§2 de
> CLAUDE.md). Decisiones aprobadas: límite de 2000 caracteres, 403 `ACCOUNT_INACTIVE` en el
> POST y mensaje de confirmación `"Comentario enviado con éxito."`.

---

## 1. Alcance y actores

**Alcance:** un único endpoint, protegido por **JWT de sesión** (Módulos D/E/F/G/H, §3 de
ARQUITECTURA_BASE):

| Endpoint | Función |
|---|---|
| `POST /api/v1/feedback/comments` | Recibir y persistir el comentario o sugerencia del usuario autenticado. |

**Actor principal:** el menor de edad autenticado, con sesión activa (CU-10).

**Precondición:** el cliente posee un JWT de sesión válido y vigente. La ruta vive dentro de
`authenticate("session-jwt")` (ARQUITECTURA_BASE.md §3: el proveedor JWT se aplica a
D/E/F/G/H). El envío requiere conexión (REQ-FUN-14 CA4); la consulta de FAQ no (ver §2).

**Flujo (CU-10):**
1. El usuario accede a "Preguntas frecuentes" desde el menú lateral; las tarjetas FAQ se
   muestran desde el almacenamiento local (sin red).
2. El usuario escribe un comentario/sugerencia en el área de texto libre (REQ-FUN-14 CA2: el
   botón "Enviar" está deshabilitado si el campo está vacío — validación de cliente).
3. El usuario pulsa "Enviar".
4. El backend valida el input (forma + negocio), persiste el comentario y responde la
   **confirmación de recepción** (REQ-FUN-14 CA3, CU-10 paso 5).

**Postcondición:** el comentario queda registrado en `comentario` (CU-10); la FAQ se consulta
sin intervención del servidor.

---

## 2. El backend NO sirve las FAQ

Requisito de partida (CLAUDE.md §8.1): REQ-FUN-14 es **"Parcial"** para el backend — las FAQ
son contenido **local y offline** del cliente (REQ-FUN-14 CA4, CU-10 precondición). El único
flujo que llega al servidor es el **envío de comentarios**.

Consecuencias:
- **No hay endpoint de lectura de FAQ** (el servidor no las tiene).
- **No hay endpoint de lectura de comentarios** (el backend solo recibe y persiste; no hay
  pantalla en la app que los consuma desde el servidor — el menor no lee comentarios de
  otros). La revisión/moderación de comentarios, si el equipo la necesita, es trabajo
  administrativo fuera de este backend y de este alcance cerrado.
- **Idempotencia:** un reintento de red que repite el mismo POST **duplica** el comentario
  (dos filas). A diferencia del Módulo G (§3.2 de `modulo-g-analisis.md`), aquí no hay merge:
  cada envío es un evento independiente. El cliente es responsable de no reenviar el mismo
  comentario (esperar la confirmación 200 antes de descartar el borrador). Se documenta como
  limitación aceptada, sin infraestructura de dedupe.

---

## 3. Validación

### 3.1 Validación de forma (controller — primera línea de defensa)

El chequeo de `.isBlank()` y `.length > 2000` se ejecuta en el **controller**, como primera
línea de defensa, **ANTES de invocar al Service** y antes de tocar la base de datos (CLAUDE.md
§6). El Service jamás recibe un `contenido` en blanco o sobre-límite: si el controller falla,
lanza 400 y el Service no se invoca.

| Regla | Respuesta |
|---|---|
| `contenido.isBlank()` (vacío o solo espacios) | 400 `VALIDATION_ERROR`, `FieldError("contenido", "Es obligatorio.")` |
| `contenido.length > 2000` (UTF-16, decisión aprobada) | 400 `VALIDATION_ERROR`, `FieldError("contenido", "Máximo 2000 caracteres.")` |

La validación de vacío es **redundante con el cliente** (REQ-FUN-14 CA2 deshabilita el botón);
se mantiene como defensa en profundidad (el backend no confía en el cliente).

**Límite de 2000 caracteres (decisión aprobada):** `contenido` es `TEXT` (máx. 65.535 bytes);
con utf8mb4 peor caso 4 bytes/carácter, 2000 chars ≈ 8 KB → imposible desbordar la columna.
Da margen a sugerencias largas de un niño sin riesgo de abuso de almacenamiento. Se cuenta con
`.length` (code units UTF-16), consistente con las demás validaciones del repo.

**Almacenamiento:** se persiste `contenido.trim()` (se eliminan espacios iniciales/finales; se
preservan internos y saltos de línea).

### 3.2 Validación de negocio (service)

- **403 `ACCOUNT_INACTIVE` (decisión aprobada):** con sesión válida pero cuenta en soft delete
  (`usuario.estado = 'eliminado'`), el envío se **rechaza**. La postcondición de CU-10 es "el
  comentario queda registrado"; un menor cuya cuenta fue dada de baja (REQ-FUN-05 CA5) no debe
  seguir escribiendo. Mismo mensaje y semántica que los Módulos D y G:
  "La cuenta no está activa."
- **404 defensivo:** token válido pero fila de `usuario` inexistente → `NOT_FOUND` (patrón de G).

No hay más reglas de negocio: **sin moderación de contenido** (no está en los requisitos) y
**sin rate-limit/anti-spam** (fuera del alcance cerrado §2 de CLAUDE.md; se deja anotado como
candidato de hardening futuro si el equipo lo requiere).

---

## 4. Contrato del endpoint

Nomenclatura §5.1 de ARQUITECTURA_BASE: `feedback/*` para comentarios (autenticado).

### 4.1 `POST /api/v1/feedback/comments`

Recibe y persiste el comentario del usuario de la sesión.

**Request — `ComentarioRequestDto`:**

```json
{
  "contenido": "Me gusta mucho el juego, pero el nivel de ciencias es muy difícil."
}
```

**El body contiene SOLO `contenido`.** El `id_usuario` se obtiene exclusivamente de
`SesionPrincipal` (`call.principal<SesionPrincipal>().idUsuario`, claim `sub` del token);
**nunca del cuerpo de la solicitud** (§6.1). Además, kotlinx.serialization está configurado
sin `ignoreUnknownKeys = true` (`json()` plano en `StatusPagesConfig.kt`): una clave
desconocida en el body (p. ej. `"idUsuario": 999`) lanza `JsonDecodingException` → 400. Es
estructuralmente imposible forjar el autor del comentario.

**Response de éxito — 200 OK — `MensajeResponseDto`:**

```json
{
  "message": "Comentario enviado con éxito."
}
```

`MensajeResponseDto` se **reutiliza** (su KDoc lo declara "reutilizable por cualquier módulo";
ya lo usan E y F). Cumple REQ-FUN-14 CA3 ("confirmación de recepción") y CU-10 paso 5.
Mínimo privilegio (CLAUDE.md §6): no expone `id_comentario`, `enviado_en` ni el contenido.

### 4.2 Códigos de estado (formato `ErrorDto` de §5.2 de ARQUITECTURA_BASE)

| Status | `error` | Cuándo |
|---|---|---|
| 200 | — | Éxito: comentario persistido + confirmación de recepción |
| 400 | `VALIDATION_ERROR` | Forma inválida (§3.1) |
| 401 | `UNAUTHORIZED` | Token ausente, malformado, expirado, de audiencia `era-app-reset` o con claim `purpose` (challenge del proveedor `session-jwt`) |
| 403 | `ACCOUNT_INACTIVE` | La cuenta está en soft delete (`usuario.estado = 'eliminado'`) |
| 500 | `INTERNAL_ERROR` | Error inesperado; stack trace solo en servidor |

No hay 404 público: el recurso es "el usuario de la sesión" (sin `{id}`, §5.1 de
ARQUITECTURA_BASE); el 404 de §3.2 es defensivo (fila inexistente pese al token válido). No
hay 409.

---

## 5. Privacidad y seguridad (CLAUDE.md §6) — regla de oro

Los comentarios de menores pueden contener **datos personales escritos de forma voluntaria**
(PII). Esto eleva el manejo de `contenido` al **nivel de exigencia de la autenticación**
(CLAUDE.md §6).

**Regla de oro: el contenido del comentario NUNCA se registra en los logs del servidor**
(ni en CallLogging ni en logs de aplicación), tal como el cuerpo del request/response se
descarta del log por defecto (ARQUITECTURA_BASE §3.1).

Cómo se garantiza:

1. **No se instala `CallLogging`.** Verificado en el código actual: no está en `module.yaml`,
   ni en `configurePlugins` (solo ContentNegotiation + StatusPages), ni en `Application.kt`.
   Hoy **nadie** loguea cuerpos de request. Si en el futuro se instalara, la lista negra de
   §3.1 (contraseñas, OTP, tokens, cédula, correo, fecha de nacimiento) y el descarte del
   cuerpo son obligatorios — o no se instala.
2. **El service no loguea el contenido.** A lo sumo audita con `idUsuario` y `idComentario`
   (patrón de `LogoutService`/`ProgressSyncService`); jamás `"...$contenido..."`.
3. **Mensajes de error sin eco del contenido.** Los `FieldError` son genéricos
   ("Es obligatorio.", "Máximo 2000 caracteres."); nunca repiten el texto recibido.
4. **Sin SQL log.** Exposed está sin `logToConsole`; no añadirlo. `logback.xml` no tiene
   access log (root INFO, consola).
5. **La respuesta no contiene el contenido** (`MensajeResponseDto` con solo `message`).

PII voluntaria: se **almacena** (es el requisito de CU-10), **no se expone** por ningún
endpoint de lectura (§2) y **nunca se loguea**.

---

## 6. Modelo de datos

### 6.1 La tabla `comentario` (V1, sin migración)

Definida en `V1__init_schema.sql` y `DICCIONARIO_DATOS.md`:

| Campo | Tipo | Restricciones |
|---|---|---|
| id_comentario | INT UNSIGNED | PK, autoincremental |
| id_usuario | INT UNSIGNED | No nulo, FK → usuario.id_usuario (**ON DELETE RESTRICT**, ON UPDATE CASCADE) |
| contenido | TEXT | No nulo |
| enviado_en | DATETIME | No nulo, default CURRENT_TIMESTAMP |

- `INDEX (id_comentario... idx_comentario_usuario (id_usuario))` ya existe.
- La FK **`ON DELETE RESTRICT`** es la segunda barrera contra el borrado físico (CLAUDE.md §7),
  igual que en el resto del esquema.
- `enviado_en` lo fija MySQL con `DEFAULT CURRENT_TIMESTAMP`; el servidor no recibe ni envía
  el timestamp (mínimo privilegio).

### 6.2 Acceso a datos

Nueva entidad Exposed `ComentarioTable` (mapeo de la tabla existente; V1 **no se toca**).
Repositorio `ComentarioRepository` con `insertar(idUsuario: Long, contenido: String): Long`
(devolver el `id_comentario` generado, útil para auditoría sin exponerlo en la respuesta).

**El `id_usuario` es SIEMPRE del `SesionPrincipal`** (claim `sub` del token), nunca del body
(§4.1).

---

## 7. Diseño técnico por capas (propuesto)

Sigue el patrón existente (routes → controllers → services → repositories → models,
ARQUITECTURA_BASE.md §1).

### 7.1 `routes/FeedbackRoutes.kt` (nuevo)

```kotlin
route("/api/v1/feedback") {
    authenticate("session-jwt") {
        post("/comments") { feedbackController.enviarComentario(call) }
    }
}
```

### 7.2 `controllers/FeedbackController.kt` (nuevo)

Valida la forma (§3.1) con `FieldError` — chequeo de `.isBlank()` y `.length > 2000` **antes
de invocar al Service** —, delega en el service y mapea la respuesta. No decide políticas de
negocio. La identidad viene de `SesionPrincipal` (patrón `UsuarioController`,
`ProgressController`).

### 7.3 `services/ComentarioService.kt` (nuevo)

Puro de Ktor y de SQL. Reglas:
- `enviarComentario(idUsuario, contenido)`: **usa `TransactionRunner` aunque sea una sola
  inserción** (estándar de manejo de excepciones de Exposed y consistencia con el resto del
  código; patrón de `ProgressSyncService`): verifica cuenta activa (403 §3.2) → inserta
  `contenido.trim()` → devuelve `MensajeResponseDto("Comentario enviado con éxito.")`.
- **Sanitización:** se aplica `.trim()` al contenido **en el Service, antes de persistirlo**
  (§3.1), para evitar almacenar espacios innecesarios.
- Solo log de auditoría con `idUsuario`/`idComentario`; nunca el contenido (§5).

### 7.4 Repositories (nuevos)

- `ComentarioRepository` / `ExposedComentarioRepository`: `insertar(idUsuario, contenido)`.

### 7.5 Models

- `entities/ComentarioTable.kt`: mapeo Exposed de la tabla V1.
- `dto/ComentarioRequestDto.kt`: **solo** `contenido: String`.
- Reutiliza `MensajeResponseDto` (no se crea DTO de respuesta).

### 7.6 `Application.kt` (modificación)

Instanciar `ExposedComentarioRepository`, `ComentarioService`, `FeedbackController` y montar
`feedbackRoutes` en `routing {}`.

Sin cambios en `module.yaml` ni `libs.versions.toml`: se reutiliza `$ktor.server.auth.jwt`,
Exposed y el `TransactionRunner` existente. **Sin migración nueva** (§9).

---

## 8. Tests previstos

| Suite | Cobertura |
|---|---|
| `services/ComentarioServiceTest.kt` (unitario, repos fake + `TransactionRunner` fake) | Happy path: inserta con el `idUsuario` del principal y devuelve el mensaje de confirmación; 403 cuenta inactiva **sin** insertar; 404 defensivo (fila inexistente); auditoría sin contenido (revisión de que el service no loguea `contenido`). |
| `routes/FeedbackControllerTest.kt` (Ktor TestHost con `configureAuthentication(JWT_CONFIG_TEST)`) | 200 happy path → `MensajeResponseDto` "Comentario enviado con éxito."; 400 `contenido` blanco; 400 solo espacios; 400 `contenido` > 2000 (2001 caracteres); 400 con clave desconocida `idUsuario` en el body (garantía §4.1); 401 sin token; 401 con token de reseteo; 403 cuenta eliminada. |

**Verificación transversal:** los mensajes de error (400/403) **no contienen** el contenido
enviado (assert del cuerpo de la respuesta de error); el repo sigue sin CallLogging (§5); build
exitoso y suite completa en verde (181 previos + nuevos de H).

---

## 9. Migración y datos

**Sin migración nueva.** La tabla `comentario` ya existe en `V1__init_schema.sql` con todas
las columnas requeridas. **Sin dependencias nuevas** (regla de trabajo CLAUDE.md §5.4 #4). No
se agrega ninguna tabla ni columna.

---

## 10. Trazabilidad

| Requisito / criterio | Caso de uso | Historia | Dónde se cumple |
|---|---|---|---|
| REQ-FUN-14 CA2 (botón deshabilitado si vacío) | CU-10 | HU-14 | Validación de cliente; respaldada en backend por §3.1 (`isBlank`) |
| REQ-FUN-14 CA3 (confirmación de recepción al enviar) | CU-10 | HU-14 | §4.1 (200 `MensajeResponseDto` "Comentario enviado con éxito.") |
| REQ-FUN-14 CA4 (FAQ offline; envío requiere conexión) | CU-10 | HU-14 | §2 (el backend no sirve FAQ; el envío es el único flujo de red) |
| CU-10 postcondición (comentario registrado en el sistema) | CU-10 | HU-14 | §6.1, §7.3 (persistencia en `comentario`) |
| CLAUDE.md §6 (datos personales con rigor de autenticación) | CU-10 | HU-14 | §5 (regla de oro, mensajes sin eco, zero logs) |
| Mínimo privilegio (CLAUDE.md §6) | CU-10 | HU-14 | §4.1 (body solo `contenido`, respuesta solo `message`) |
| REQ-NF-01 (respuesta < 3 s) | CU-10 | HU-14 | Persistencia de una fila en transacción; sin lógica adicional |

---

## 11. Plan de implementación y estado

| Paso | Contenido | Estado |
|---|---|---|
| 1 | **Estructura y lógica:** entidad Exposed `ComentarioTable`, DTO `ComentarioRequestDto`, `ComentarioRepository`/`ExposedComentarioRepository`, `ComentarioService` (403 + inserción transaccional), `FeedbackController`, `FeedbackRoutes` | **Pendiente** |
| 2 | **Tests:** `ComentarioServiceTest` (unitario) + `FeedbackControllerTest` (ruta) + ajuste del wiring de `Application.kt` | **Pendiente** |
| 3 | **Wiring final:** repositorio real + `ExposedTransactionRunner` en `Application.kt`, montar `feedbackRoutes` | **Pendiente** |
| 4 | **Documentación:** README (tabla de endpoints, suite de tests) + CLAUDE.md §9 | **Pendiente** |

**Verificación transversal:**
1. **Validación en el Controller:** `.isBlank()` y `.length > 2000` se ejecutan antes de
   invocar al Service (§3.1).
2. **Sanitización:** `.trim()` del contenido antes de persistir (§3.1, §7.3).
3. **Transaccionalidad:** el Service usa `TransactionRunner` aunque sea una sola inserción
   (§7.3).
4. **403** cuenta inactiva (§3.2); `id_usuario` solo del `SesionPrincipal` (§6.2); cero logs
   de contenido (§5); build + suite completa en verde.
