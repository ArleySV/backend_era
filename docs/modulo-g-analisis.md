# ERA — Módulo G (Sincronización de progreso) — Análisis funcional

> Documentación oficial del sistema ERA. Ver [`../CLAUDE.md`](../CLAUDE.md) para las reglas
> permanentes y la matriz de trazabilidad.
>
> **Base normativa:** [`requisitos-funcionales.md`](./requisitos-funcionales.md)
> (REQ-FUN-10, REQ-FUN-11, REQ-FUN-12), [`casos-de-uso.md`](./casos-de-uso.md) (CU-08,
> CU-12), [`historias-de-usuario.md`](./historias-de-usuario.md) (HU-10, HU-11, HU-12),
> [`ARQUITECTURA_BASE.md`](./ARQUITECTURA_BASE.md) (§1, §2.3, §3, §5.1, §5.2, §5.3) y
> [`DICCIONARIO_DATOS.md`](./DICCIONARIO_DATOS.md) (tablas `nivel`, `progreso_usuario`,
> `intento`, `usuario`).
>
> Este documento registra el **análisis funcional aprobado** del Módulo G (Sincronización
> de progreso, REQ-FUN-10/11/12, CU-08, CU-12, HU-10/11/12) y el **contrato** de sus dos
> endpoints. Las decisiones centrales — **el backend no sirve el catálogo de trivia** y la
> **sincronización de agregados con merge hacia adelante** — se detallan en §2 y §3 porque
> explican *por qué* el módulo hace lo que hace y *por qué no* más (no sirve preguntas, no
> sincroniza pausas, no persiste filas de `intento`).

---

## 1. Alcance y actores

**Alcance:** dos endpoints, ambos protegidos por **JWT de sesión** (Módulo B):

| Endpoint | Función |
|---|---|
| `GET /api/v1/progress/sync` | Obtener el snapshot autoritativo del progreso del usuario (bootstrap al iniciar sesión, cambio de dispositivo, reconciliación). |
| `POST /api/v1/progress/sync` | Subir el estado local acumulado; el servidor **mergea hacia adelante**, persiste y devuelve el snapshot autoritativo resultante. |

**Actor principal:** el menor de edad autenticado. La sincronización la dispara la app
automáticamente al detectar conexión o el botón "Sincronizar ahora" de Ajustes (CU-09
`<<extend>>` CU-12); el actor principal de CU-12 es el servidor.

**Precondición:** el cliente posee un JWT de sesión válido y vigente. Las rutas viven
dentro de `authenticate("session-jwt")` (ARQUITECTURA_BASE.md §3: el proveedor JWT se
aplica a D/E/F/G/H).

**Flujo (CU-12):**
1. El cliente detecta conexión disponible (o el usuario pulsa "Sincronizar ahora").
2. El cliente envía al servidor el estado local de los niveles (progreso, niveles,
   reintentos; **no** comentarios ni avatar — CU-12 excluye el avatar; los comentarios son
   Módulo H).
3. El servidor valida, **mergea hacia adelante**, persiste **atómicamente** y devuelve el
   snapshot actualizado (paso 3 de CU-12: "el servidor confirma la recepción y devuelve
   datos actualizados si existen").
4. El cliente reemplaza su almacenamiento local con el snapshot recibido.

**Postcondición:** los datos locales y del servidor quedan consistentes (CU-12). Sin
conexión, la app sigue operando offline y pospone (alternativa 1a); si la sincronización
falla, se informa y se reintenta en la siguiente oportunidad (alternativa 3a).

---

## 2. El backend NO sirve el catálogo de trivia

Requisito de partida (CLAUDE.md §2): **"El backend NO sirve el contenido del juego."** El
cliente Android opera offline-first: el catálogo de los 20 niveles (título, pregunta,
opciones, imágenes) es **estático en el dispositivo**. Coherente con la matriz de
relevancia de CLAUDE.md §8.1: REQ-FUN-10, REQ-FUN-11 y REQ-FUN-12 son "Cliente +
sincronización" — el backend **solo almacena niveles y reintentos**.

**Rol de las tablas de catálogo en el servidor:**
- `nivel` es el **ancla referencial** de `progreso_usuario.id_nivel` (FK `RESTRICT`). No es
  una fuente de contenido que se exponga por API.
- `pregunta` y `opcion_respuesta` son contenido administrado por el equipo (FK `CASCADE`,
  DICCIONARIO_DATOS.md) y **no participan** en la sincronización: sin `intento` sincronizado
  (§3), el wire nunca necesita `id_opcion`.

**Identificador en el wire — `orden` (1..20), no `id_nivel` (decisión aprobada).** El
cliente offline se desacopla de las PKs internas del catálogo: si el equipo re-siembra o
reordena el catálogo, los dispositivos no desplegados siguen enviando `orden` 1..20 y el
servidor resuelve `orden → id_nivel` en el momento del sync. `nivel.orden` es `UNIQUE` con
`CHECK 1..20` (V1), garantía de la correspondencia.

---

## 3. Estrategia de sincronización: agregados + merge hacia adelante

### 3.1 Qué se sincroniza (decisión aprobada: solo agregados)

El POST lleva **el estado completo de los 20 niveles** por usuario:

| Campo del wire | Entidad | Regla de merge (por nivel) |
|---|---|---|
| `orden` | `nivel.orden` | Clave del wire; el servidor resuelve a `id_nivel` |
| `estadoNivel` | `progreso_usuario.estado_nivel` | `max(bloqueado < disponible < completado)` — **nunca regresar** |
| `intentosTotales` | `progreso_usuario.intentos_totales` | `max(cliente, servidor)` — monotónico e idempotente |
| `intentosFallidosConsecutivos` | `progreso_usuario.intentos_fallidos_consecutivos` | `max(cliente, servidor)` — espejo; el cliente lo reinicia (REQ-FUN-11 CA3) |

**No se sincronizan:** `pausa_activa` / `pausa_hasta` (ver §3.3), `completado_en`
(lo fija el servidor una sola vez con su reloj, §4.4), `ultima_interaccion` (siempre reloj
del servidor) ni filas de `intento`.

### 3.2 Idempotencia — sin tablas nuevas

Con agregados y merge hacia adelante, **cada escritura es naturalmente idempotente**:
reescribir el mismo valor mergeado produce la misma fila. Un reintento de red que repite el
mismo POST no genera estados duplicados ni infla contadores. **No se necesita batch ID,
UUID de sincronización ni tabla de deduplicación** — el esquema de 12 tablas queda intacto
(CLAUDE.md §2). Si en el futuro se sincronizaran filas de `intento` (auditoría detalle a
detalle), ahí sí haría falta un `id_intento_cliente` UUID + migración; quedó descartado por
mínimo privilegio (CLAUDE.md §6): ningún consumidor en pantalla necesita el detalle de cada
respuesta.

**Límite asumido del `max`:** si el mismo nivel se juega en dos dispositivos offline y
ambos sincronizan, el contador menor se pierde (subconteo de reintentos). Es el precio de la
idempotencia sin infraestructura de dedupe; se acepta y queda registrado.

### 3.3 Pausa "Estírate y respira" — lógica solo de cliente (decisión aprobada)

La pausa de 60 s tras 2 fallos consecutivos (REQ-FUN-11) es **efímera por sesión**:
REQ-FUN-11 CA3 reinicia el contador de fallos consecutivos al superar el nivel **o al salir
de la pantalla de niveles**, así que la pausa nunca sobrevive a una salida. El servidor no
puede ejecutar el cronómetro, no hay valor de negocio ni anti-abuso en persistirla (el
cliente es el motor de juego y opera offline), y un `pausa_hasta` persistido crearía estados
absurdos entre dispositivos.

`pausa_activa` y `pausa_hasta` **se mantienen en el esquema V1** (no se toca la migración)
pero **no viajan en el payload** de sincronización. `intentosFallidosConsecutivos` sí se
sincroniza como parte del espejo.

### 3.4 Resolución de conflictos — fuente de verdad

El servidor es el **espejo remoto autoritativo del estado final**, y el merge hacia
adelante garantiza que la fuente de verdad jamás retrocede:

- Si el servidor tiene `completado` y el cliente envía `disponible` (p. ej. reinstalación o
  dispositivo nuevo con estado fresco) → **prevalece `completado`**.
- Si el cliente envía `completado` y el servidor tenía `disponible` → **prevalece
  `completado`** (progreso del juego).
- `intentosTotales` y `intentosFallidosConsecutivos` solo crecen (max).

El servidor **no** valida la cadena de desbloqueo (decisión aprobada, §5): un "nivel 5
completado" sin los previos se **acepta** (validación *lenient*), porque el cliente es el
motor y una validación estricta rechazaría estados legítimos en multi-dispositivo. La
integridad que sí se exige es la **existencia del nivel en el catálogo** (§5).

---

## 4. Contrato de endpoints

Nomenclatura §5.1 de ARQUITECTURA_BASE: `progress/*` para sincronización (autenticado).

### 4.1 `GET /api/v1/progress/sync`

Recupera el snapshot autoritativo. Usos: bootstrap al iniciar sesión (REQ-FUN-10 CA3,
HU-10 CA4), cambio de dispositivo, reconciliación tras un sync fallido (CU-12 alt. 3a).

**Request:** sin body; `Authorization: Bearer <token-sesion>`.

**Response de éxito — 200 OK — `ProgresoSyncResponseDto`:**

```json
{
  "progreso": [
    { "orden": 1, "estadoNivel": "completado", "intentosTotales": 3,
      "completadoEn": "2026-08-09T10:00:00Z", "ultimaInteraccion": "2026-08-09T10:05:12Z" }
  ],
  "resumen": { "nivelesCompletados": 7, "totalNiveles": 20, "totalReintentos": 42 }
}
```

`progreso` contiene una entrada por nivel con actividad (niveles sin fila se omiten; su
estado implícito es `bloqueado` con 0 reintentos). `resumen` se calcula en el servidor
(§7).

### 4.2 `POST /api/v1/progress/sync`

Sube el estado local acumulado. **Request — `ProgresoSyncRequestDto`:**

```json
{
  "progreso": [
    { "orden": 1, "estadoNivel": "completado", "intentosTotales": 3, "intentosFallidosConsecutivos": 0 },
    { "orden": 2, "estadoNivel": "disponible", "intentosTotales": 1, "intentosFallidosConsecutivos": 1 }
  ]
}
```

El cliente envía **el estado completo de los 20 niveles** (incluidos los `bloqueado` con 0
reintentos, o solo los que tienen actividad — ambos son válidos; el merge lo absorbe). No
envía `completadoEn` ni `ultimaInteraccion`: son del servidor (§4.4).

**Response:** 200 OK con el **mismo** `ProgresoSyncResponseDto` de §4.1, pero ya **mergeado
y persistido** — el servidor "confirma la recepción y devuelve datos actualizados si
existen" (CU-12 paso 3) en un **único round-trip**, sin que el cliente necesite un GET
adicional para reconciliar.

### 4.3 Códigos de estado (formato `ErrorDto` de §5.2 de ARQUITECTURA_BASE)

| Status | `error` | Cuándo |
|---|---|---|
| 200 | — | Éxito: snapshot (GET) o snapshot mergeado persistido (POST) |
| 400 | `VALIDATION_ERROR` | Forma inválida (§5.1) o **integridad: `orden` recibido no existe en `nivel`** (§5.2) |
| 401 | `UNAUTHORIZED` | Token ausente, malformado, expirado, de audiencia `era-app-reset` o con claim `purpose` (challenge del proveedor `session-jwt`) |
| 403 | `ACCOUNT_INACTIVE` | La cuenta está en soft delete (`usuario.estado = 'eliminado'`); mismo mensaje y semántica que el Módulo D |
| 500 | `INTERNAL_ERROR` | Error inesperado; stack trace solo en servidor |

No hay 404: el recurso es "el usuario de la sesión" (`/progress/sync`, sin `{id}`,
§5.1 de ARQUITECTURA_BASE). No hay 409.

### 4.4 Campos que administra el servidor (no el cliente)

| Campo | Regla |
|---|---|
| `completadoEn` | El servidor lo fija **una sola vez** con su reloj (`NOW()`) cuando el nivel transiciona a `completado` y aún no tenía valor; **nunca** se resetea ni se acepta del cliente (relojes de dispositivo no confiables) |
| `ultimaInteraccion` | Siempre reloj del servidor, en cada escritura |

---

## 5. Validación de integridad

### 5.1 Validación de forma (controller / DTO)

Antes de tocar la base de datos (primera barrera, CLAUDE.md §6):
- `progreso` debe estar presente; si viene `null`/ausente → 400.
- Por ítem: `orden` entero en `1..20`; `estadoNivel` ∈ `{bloqueado, disponible, completado}`;
  `intentosTotales` ≥ 0; `intentosFallidosConsecutivos` ≥ 0 (default 0).
- **`orden` duplicado dentro del mismo payload → 400** (ambiguo; el cliente nunca debe
  mandarlo).

### 5.2 Validación de integridad contra el catálogo (obligatoria, especificación aprobada)

> **El servidor debe validar que el `orden` recibido (1..20) existe en su tabla `nivel`.**

Antes de persistir, el servidor comprueba en `nivel` que **todos** los `orden` recibidos
existen (`SELECT orden FROM nivel WHERE orden IN (...)`, y comparación contra los recibidos).
Si algún `orden` no existe → 400 `VALIDATION_ERROR` y la sincronización **no escribe nada**
(§6). Esto protege la FK `progreso_usuario.id_nivel → nivel` (`RESTRICT`) de intentos de
escribir niveles inexistentes y mantiene la consistencia del espejo con el catálogo real.

### 5.3 Validación de negocio (lenient, decisión aprobada)

No se valida la cadena de desbloqueo (nivel N completado sin N−1) ni la coherencia de
`intentosTotales` con el `estadoNivel`. El merge hacia adelante (§3.4) es la única política:
previene regresiones sin rechazar estados legítimos.

---

## 6. Manejo de transacciones — atomicidad del POST (obligatoria, especificación aprobada)

> **La operación de `POST /sync` (procesar los 20 niveles) debe ser atómica. Si falla el
> guardado de un nivel, debe fallar toda la sincronización para evitar estados parciales en
> el servidor.**

Todo el procesamiento del POST — verificación de cuenta activa, validación de catálogo y
merge/escritura de los niveles — corre dentro de **una sola transacción** usando el
`TransactionRunner` existente (`repositories/TransactionRunner.kt` → `transaction { }` de
Exposed sobre el pool Hikari/MySQL):

- Si cualquier ítem falla (validación de catálogo, violación de constraint, error de BD), la
  excepción aborta la transacción y **se revierte todo el lote**: no quedan niveles a medias
  (estado parcial) en el servidor.
- El alta de un nivel nuevo usa `INSERT` y el cambio de uno existente `UPDATE`
  (upsert por `(id_usuario, id_nivel)` — clave `UNIQUE` de `progreso_usuario`); el `orden`
  recibido se resuelve a `id_nivel` dentro de la misma transacción.
- La verificación de cuenta activa se hace **dentro** de la misma transacción: si el estado
  es `eliminado` → `AccountInactiveException` → rollback total y 403 `ACCOUNT_INACTIVE`
  (patrón de `UsuarioService.consultarPerfil`, pero sin transacción previa separada).
- El `TransactionRunner` de producción no devuelve valores (bloque `() -> Unit`); para el
  snapshot de respuesta, el resultado se captura en `var` dentro del bloque y se consume
  fuera (mismo patrón que `UsuarioService`).

**GET:** lectura en una transacción (Exposed exige contexto transaccional incluso para
`SELECT`), capturando el snapshot en `var`s. La cuenta debe estar activa → 403 si no.

**Concurrencia:** dos POST concurrentes del mismo usuario son benignos por diseño — el merge
es `max`-idempotente (cada nivel converge al valor mayor) y la `UNIQUE (id_usuario, id_nivel)`
impide filas duplicadas; no se requiere `FOR UPDATE`.

---

## 7. Resumen de respuesta — `totalReintentos` calculado en el servidor (obligatoria, especificación aprobada)

> **`totalReintentos` en el DTO de respuesta debe ser la suma calculada en el servidor de
> todos los `intentos_totales` del usuario.**

El `resumen` del `ProgresoSyncResponseDto` se calcula **en una consulta** sobre
`progreso_usuario` del usuario, no por suma en el cliente:

| Campo | Cálculo en el servidor |
|---|---|
| `nivelesCompletados` | `COUNT(*) WHERE estado_nivel = 'completado'` |
| `totalNiveles` | Constante 20 (catálogo, REQ-FUN-10) |
| `totalReintentos` | `SUM(intentos_totales)` de todos los niveles del usuario |

El porcentaje (`nivelesCompletados / 20 × 100`) lo calcula **el cliente** (REQ-FUN-12 CA1;
CLAUDE.md §8.1: "el porcentaje se calcula en el cliente; el backend solo almacena niveles y
reintentos"). El servidor entrega los datos base en una sola query para que la división del
cliente sea trivial y no tenga que sumar 20 filas a mano. La barra de progreso (CA2) y el
total acumulado (CA3) quedan servidos por estos campos.

---

## 8. Seguridad (ratificación de 401/403)

- **401 `UNAUTHORIZED`** — toda petición sin token de sesión válido muere en el `challenge`
  del proveedor `session-jwt` (ARQUITECTURA_BASE.md §5.3/D-6 del Módulo D): token ausente,
  malformado, expirado, de audiencia `era-app-reset` o con claim `purpose`. Ningún handler
  de sync se ejecuta sin sesión válida.
- **403 `ACCOUNT_INACTIVE`** — con sesión válida pero cuenta en soft delete
  (`usuario.estado = 'eliminado'`): el sync se **rechaza** (GET y POST). A diferencia del
  logout (Módulo F, que no evalúa la cuenta), escribir/leer progreso de una cuenta dada de
  baja no debe continuar: REQ-FUN-05 CA5. Mensaje idéntico al Módulo D:
  "La cuenta no está activa."
- **Mínimo privilegio (CLAUDE.md §6):** el payload no contiene datos personales (solo
  `orden`/estado/contadores); la respuesta no expone `id_usuario`, `id_nivel` ni `id_progreso`.
- **Zero logs sensibles:** nunca se loguea el token, `Authorization`, correo, cédula ni
  fechas de nacimiento (ARQUITECTURA_BASE.md §3.1, CLAUDE.md §6). El log del sync puede
  incluir `idUsuario` y conteos, nunca el cuerpo ni datos personales.

---

## 9. Diseño técnico por capas (propuesto)

Sigue el patrón existente (routes → controllers → services → repositories → models,
ARQUITECTURA_BASE.md §1).

### 9.1 `routes/ProgressRoutes.kt` (nuevo)

```kotlin
route("/api/v1/progress") {
    authenticate("session-jwt") {
        get("/sync") { progressController.getSync(call) }
        post("/sync") { progressController.postSync(call) }
    }
}
```

### 9.2 `controllers/ProgressController.kt` (nuevo)

Valida input básico (presencia de body, campos de forma §5.1), delega en el service y mapea
la respuesta. No decide políticas de negocio.

### 9.3 `services/ProgressSyncService.kt` (nuevo)

Puro de Ktor y de SQL. Reglas:
- `obtenerSnapshot(idUsuario)`: verifica cuenta activa (403) y devuelve `ProgresoSyncResponseDto`
  con `resumen` (§7).
- `sincronizar(idUsuario, request)`: en **una transacción** (§6): verifica cuenta activa →
  valida `orden` contra el catálogo (§5.2) → mergea hacia adelante por nivel (§3) →
  persiste → captura el snapshot resultante y lo devuelve.

### 9.4 Repositories (nuevos)

- `ProgresoRepository` / `ExposedProgresoRepository`: `findByIdUsuario(idUsuario)`,
  `findByIdUsuarioYNivel(idUsuario, idNivel)`, `insertar(...)`, `actualizar(...)`,
  `contarCompletados(idUsuario)`, `sumarIntentosTotales(idUsuario)`.
- `NivelRepository` / `ExposedNivelRepository`: `ordenesExistentes(ordenes: List<Int>): Set<Int>`
  (validación §5.2) y `findByIdOrden(orden): Int?` (resolución `orden → id_nivel`).

### 9.5 Models

- `entities/`: `ProgresoUsuarioTable`, `NivelTable` (tablas Exposed; `progreso_usuario` y
  `nivel` ya existen en V1, solo faltan sus mapeos) + `EstadoNivel` enum.
- `dto/`: `ProgresoSyncRequestDto` (lista de `ProgresoSyncItemDto`: `orden`, `estadoNivel`,
  `intentosTotales`, `intentosFallidosConsecutivos`), `ProgresoSyncResponseDto`
  (`progreso: List<NivelProgresoDto>`, `resumen: ResumenProgresoDto`), `ResumenProgresoDto`
  (`nivelesCompletados`, `totalNiveles`, `totalReintentos`).

### 9.6 `Application.kt` (modificación)

Instanciar repositorios/service/controller y montar `progressRoutes` en `routing {}`.

Sin cambios en `module.yaml` ni `libs.versions.toml`: se reutiliza `$ktor.server.auth.jwt`,
Exposed y el `TransactionRunner` existente. **Sin migración nueva** (§11).

---

## 10. Tests previstos

| Suit | Cobertura |
|---|---|
| `services/ProgressSyncServiceTest.kt` (unitario, repos fake + `TransactionRunner` fake) | Merge hacia adelante (completado > disponible > bloqueado; el servidor nunca regresa); `intentosTotales` = max; `completadoEn` se fija una sola vez; 403 cuenta inactiva; 400 si un `orden` no existe en catálogo; atomicidad: un ítem inválido en medio del lote revierte el lote completo; `totalReintentos` = suma servidor; snapshot tras POST idéntico a GET. |
| `routes/ProgressControllerTest.kt` (Ktor TestHost con `configureAuthentication(JWT_CONFIG_TEST)`) | 200 GET con snapshot (usuario nuevo → `progreso` vacío, 0 completados, 0 reintentos); 200 POST → respuesta mergeada; 400 forma (orden fuera de rango, estado inválido, `intentosTotales` negativo, orden duplicado); 400 integridad (orden inexistente); 401 sin token / token de reseteo; 403 cuenta eliminada. |
| `services/ProgressSyncServiceTest` (catalog mock) | `orden` recibido no existe en `nivel` → 400 y cero escrituras. |

**Resultado esperado:** `.\kotlin build` exitoso y suite completa en verde (152 previos +
29 nuevos de G, **181 tests** en 20 suites).

---

## 11. Migración y datos

**Sin migración nueva.** Las tablas `nivel` y `progreso_usuario` ya existen en
`V1__init_schema.sql` con todas las columnas requeridas (`estado_nivel`,
`intentos_totales`, `intentos_fallidos_consecutivos`, `completado_en`,
`ultima_interaccion`, `pausa_activa`, `pausa_hasta`, `UNIQUE (id_usuario, id_nivel)`,
FK `RESTRICT` hacia `usuario` y `nivel`). **Sin dependencias nuevas** (regla §5.4 #4 de
CLAUDE.md). No se agrega ninguna tabla ni columna.

---

## 12. Trazabilidad

| Requisito / criterio | Caso de uso | Historia | Dónde se cumple |
|---|---|---|---|
| REQ-FUN-10 CA3 (estado por nivel persistido y sincronizado al iniciar sesión) | CU-12 | HU-10 | §4.1 (GET al login), §6 (persistencia atómica) |
| REQ-FUN-10 CA2/CA4 (desbloqueo y contenido del juego son del cliente) | CU-02 | HU-10 | §2 (el backend no sirve el catálogo) |
| REQ-FUN-11 CA3/CA5 (fallos consecutivos y reintentos registrados) | CU-02 | HU-11 | §3.1, §7 (`intentosTotales`, `totalReintentos`) |
| REQ-FUN-12 CA1 (porcentaje `completados/20 × 100` calculado en cliente) | CU-08 | HU-12 | §7 (el servidor da datos base; el cliente calcula) |
| REQ-FUN-12 CA2/CA3 (total de reintentos acumulado) | CU-08 | HU-12 | §7 (`totalReintentos = SUM(intentos_totales)` en servidor) |
| CU-12 paso 3 (servidor confirma y devuelve datos actualizados) | CU-12 | HU-13 | §4.2 (POST responde con snapshot mergeado, un solo round-trip) |
| CU-12 alt. 3a (reintento en la próxima conexión) | CU-12 | HU-13 | §3.2 (idempotencia: reenviar el mismo estado no duplica) |
| REQ-NF-02 / CLAUDE.md §6 (seguridad, mínimo privilegio, 401/403, zero logs) | CU-12 | HU-02/HU-15 | §8 |

---

## 13. Plan de implementación y estado

| Paso | Contenido | Estado |
|---|---|---|
| 1 | **Estructura y lógica:** entidades Exposed (`NivelTable`, `ProgresoUsuarioTable`, `EstadoNivel`), DTOs, `NivelRepository`/`ProgresoRepository`, `ProgressSyncService` (merge + atomicidad), `ProgressController`, `ProgressRoutes` | **Completado** |
| 2 | **Tests:** `ProgressSyncServiceTest` (unitario) + `ProgressControllerTest` (ruta) + ajuste del wiring de `Application.kt` | **Completado** |
| 3 | **Wiring final:** repositorios reales + `ExposedTransactionRunner` en `Application.kt`, montar `progressRoutes` | **Completado** |
| 4 | **Documentación:** README (tabla de endpoints, suite de tests) + CLAUDE.md §9 | **Completado** |

**Verificación transversal:** validación de `orden` contra `nivel` (§5.2), atomicidad del
POST (§6), `totalReintentos` como suma del servidor (§7), 401/403 (§8); cero logs de datos
personales (CLAUDE.md §6); sin migración ni dependencias nuevas.
