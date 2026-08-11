# ERA — Módulo I (Avatar personalizado) — Análisis funcional

> Documentación oficial del sistema ERA. Ver [`../CLAUDE.md`](../CLAUDE.md) para las reglas
> permanentes y la matriz de trazabilidad.
>
> **Base normativa:** [`requisitos-funcionales.md`](./requisitos-funcionales.md)
> (REQ-FUN-06), [`casos-de-uso.md`](./casos-de-uso.md) (CU-06),
> [`historias-de-usuario.md`](./historias-de-usuario.md) (HU-06),
> [`ARQUITECTURA_BASE.md`](./ARQUITECTURA_BASE.md) (§1, §3, §3.1, §5.2 y §5.4 decisión 7)
> y [`DICCIONARIO_DATOS.md`](./DICCIONARIO_DATOS.md) (`usuario.avatar`).
>
> Este documento registra el **análisis funcional aprobado** del Módulo I (Avatar
> personalizado, ampliado al backend por decisión del propietario 2026-08-05) y el contrato
> de sus dos endpoints. La decisión central: **la imagen de un menor es un dato sensible**
> (CLAUDE.md §6), por lo que el servido es **siempre autenticado** y sin URL pública (§5).
> Decisiones aprobadas el 2026-08-10: mecanismo preset-vs-foto con prefijo `custom:`,
> respuesta de éxito reutilizando `MensajeResponseDto`, 400 `VALIDATION_ERROR` para todos los
> errores de input (incluido > 2 MB), validación mandatoria por magic bytes + fourCC de chunk
> WebP, rutas dentro de `UserRoutes.kt` bajo `/api/v1/users/me`, e **independencia de la
> interfaz `AvatarStorage`** (permite migrar a S3 sin rediseño, §7.3).

---

## 1. Alcance y actores

**Alcance:** dos endpoints, ambos protegidos por **JWT de sesión** (`session-jwt`, mismo
proveedor de los Módulos D/E/F/G/H):

| Endpoint | Función |
|---|---|
| `PUT /api/v1/users/me/avatar` | Subir o **reemplazar** la foto personalizada (multipart/form-data, autenticado). |
| `GET /api/v1/users/me/avatar` | Servir el binario de la foto personalizada (autenticado). |

**Actor principal:** el menor de edad autenticado, con sesión activa (CU-06, flujo alternativo
3a).

**Precondición:** el cliente posee un JWT de sesión válido y vigente. La subida es
**post-verificación**: el usuario ya existe en `usuario` (ARQUITECTURA_BASE §5.4 decisión 7 —
la imagen **no** se sube durante el registro; en ese punto solo existe `registro_pendiente`,
que puede expirar sin verificarse y generaría archivos huérfanos).

**Flujo (CU-06):**
1. El usuario accede a "Mi cuenta" y pulsa "Editar" (CU-06 paso 3).
2. El usuario elige una imagen de la galería interna o del dispositivo (CU-06 3a; REQ-FUN-06
   CA4) y pulsa "Guardar".
3. El backend valida el archivo (forma: formato + tamaño + binario), lo persiste en storage y
   actualiza `usuario.avatar`; responde la confirmación.
4. Para mostrarla de nuevo, el cliente solicita `GET /api/v1/users/me/avatar` con su token.

**Postcondición:** `usuario.avatar` apunta a la clave de storage de la foto personalizada; el
archivo anterior (si existía) se eliminó. El avatar personalizado **no forma parte de CU-12**
(la sincronización no incluye imágenes, REQ-FUN-06; ver `modulo-g-analisis.md` §2).

---

## 2. Reglas de negocio

### 2.1 Restricciones de archivo

| Regla | Valor |
|---|---|
| Tamaño máximo | **2 MB** (decisión 7). Se fuerza a nivel de **parte multipart**, leyendo el stream con tope de `2 MB + 1` bytes y cortando al superarlo (no confiar en `Content-Length` global; evita DoS por streams gigantes). |
| Formatos permitidos | **`jpeg`, `png`, `webp`** (whitelist). |
| Validación de seguridad | **Doble validación obligatoria** (decisión 7): `Content-Type` de la parte (no autoritativo) **y** *magic bytes* (autoritativo). |

**Magic bytes por formato:**

| Formato | Firma | Chequeo estructural adicional |
|---|---|---|
| JPEG | `FF D8 FF` | — |
| PNG | `89 50 4E 47 0D 0A 1A 0A` | — |
| WEBP | `52 49 46 46` (`RIFF`) + `57 45 42 50` (`WEBP`) en offset 8 | fourCC de chunk `VP8 `, `VP8L` o `VP8X` en offset 12 |

**Decisión aprobada (2026-08-10):** magic bytes + fourCC de chunk WebP son la validación
**mandatoria** de los tres formatos. El `sanity-check de decodificación` de la decisión 7 se
satisface por el fourCC estructural de WebP (el JDK no trae decodificador WebP y añadir
TwelveMonkeys exigiría aprobación de dependencia nueva, CLAUDE.md #4); para JPEG/PNG queda
**opcional** un decode con `ImageIO` (JDK, cero deps) como hardening futuro, sin convertirse en
barrera de aceptación.

### 2.2 Mecanismo preset-vs-foto (decisión aprobada: prefijo `custom:`)

Hoy `usuario.avatar` puede contener `preset:1|2|3` (copiado de `registro_pendiente` en la
conversión de A.1, `VerificationService.kt`) o `NULL`. Para distinguir por construcción entre
un preset (imagen local del cliente, no se persiste archivo) y una foto personalizada:

| Valor en `usuario.avatar` | Significado |
|---|---|
| `preset:1` \| `preset:2` \| `preset:3` | Avatar preestablecido (asset local del cliente; **sin** archivo en el servidor). |
| `custom:<uuid>.<ext>` | Foto personalizada subida vía Módulo I (clave de storage; el archivo existe). |
| `NULL` | Sin avatar seleccionado en el registro. |

El prefijo `custom:` es simétrico al `preset:` existente (`utils/AvatarPreset.kt`) y
**retrocompatible**: `GET /me` (Módulo D) sigue devolviendo el valor tal cual y el cliente
decide sin ambigüedad — `preset:*` → asset local; `custom:*` → `GET /avatar` autenticado. La
columna `usuario.avatar` es VARCHAR(255); `custom:` + UUID (36) + `.webp` ≈ 48 caracteres.

### 2.3 Almacenamiento y anonimización

- **`AvatarStorage`** (interfaz, §7.3): abstracción que **aísla la persistencia de archivos**
  de la lógica de negocio. `LocalDiskAvatarStorage` es la implementación sobre disco local en
  `AVATAR_STORAGE_DIR` (decisión 7: cero dependencias, una sola instancia, volúmenes ≤ 2 MB).
  El resto de la aplicación depende **solo de la interfaz** → migrar a S3 es reemplazar la
  implementación, sin tocar services/controllers (independencia aprobada).
- **Anonimización:** los nombres de archivo son **opacos** (`UUID.randomUUID()`, nunca el
  nombre del cliente) + extensión canónica derivada del formato **detectado por magic bytes**,
  no del `filename` multipart.
- **Protección contra path traversal:** las claves las genera el servidor (UUID), por lo que
  el traversal es estructuralmente imposible; aún así, `LocalDiskAvatarStorage` resuelve contra
  el directorio base y verifica que el path canónico esté dentro de él.
- **Robustez de escritura:** se escribe a `<clave>.tmp` y se mueve con `Files.move(ATOMIC_MOVE)`
  (no queda un archivo parcial con la clave definitiva si el proceso muere a mitad). Al arrancar,
  `Files.createDirectories(AVATAR_STORAGE_DIR)` con fail-fast claro ante permisos negados.
- **Retención ante soft delete (decisión 7):** el archivo **se conserva** cuando la cuenta se
  elimina (`DELETE /me` no toca el storage). Consistente con REQ-FUN-05 y CLAUDE.md §7; borrar
  el archivo dejaría un enlace roto en `usuario.avatar`.

### 2.4 Ciclo de vida: sobrescritura y vuelta a preset

**`PUT` (sobrescritura/reemplazo) — orden con compensación:**

1. Transacción: `findByIdForUpdate(idUsuario)` + verificación de cuenta `ACTIVO` (403 §4.2) +
   lectura del valor actual de `avatar` (clave vieja).
2. Fuera de la transacción: escribir el archivo nuevo → clave `custom:<uuid>`.
3. Transacción: `actualizarAvatar(idUsuario, claveNueva)` → commit.
4. Tras el commit: si la clave vieja era `custom:*`, `storage.eliminar(claveVieja)` (best-effort;
   un fallo se loguea solo con `idUsuario` y la clave, sin contenido binario).
5. **Compensación:** si el paso 3 falla → `storage.eliminar(claveNueva)` y se propaga el error.

**Vuelta a preset (futuro `PATCH /api/v1/users/me`, módulo de username):** si el valor viejo de
`avatar` era `custom:*`, se borra el archivo antes de persistir el nuevo valor preset. Para no
duplicar la regla, el service de avatar expone `eliminarAvatarSiPersonalizado(claveVieja)`,
reutilizado por el PUT (paso 4) y por el futuro PATCH. Este módulo **no implementa** el PATCH;
solo deja el bloque reutilizable y el criterio documentado.

**Limitación aceptada:** dos PUTs concurrentes del mismo usuario pueden dejar un archivo
huérfano (el segundo delete de paso 4 puede borrar la clave del primero). Borde tolerado:
despliegue de una sola instancia y avatares ≤ 2 MB; se documenta como candidato de hardening
(borrar la clave vieja solo si `usuario.avatar` ya no la referencia).

---

## 3. Datos de entrada y salida

### 3.1 Subida — `PUT /api/v1/users/me/avatar`

**Request:** `multipart/form-data` con una **única parte** de archivo llamada `avatar`
(`PartData.FileItem` con su `Content-Type`). No hay DTO serializado: multipart se lee por
partes con `call.receiveMultipart()`; el `filename` del cliente se **ignora**.

**Response de éxito — 200 OK — `MensajeResponseDto` (decisión aprobada: reutilizado, como en
E/F/H):**

```json
{
  "message": "Avatar actualizado con éxito."
}
```

El nuevo valor (`custom:…`) no se expone en la respuesta (mínimo privilegio, CLAUDE.md §6): el
cliente obtiene la fuente autoritativa en el siguiente `GET /me`, y ya posee los bytes en
memoria para el refresh local. Se descartó un DTO dedicado: más superficie de contrato para
cero ganancia.

### 3.2 Descarga — `GET /api/v1/users/me/avatar`

Headers de respuesta necesarios:

| Header | Valor | Por qué |
|---|---|---|
| `Content-Type` | `image/jpeg` \| `image/png` \| `image/webp` | Tipo **canónico** (el detectado por magic bytes al subir y persistido), nunca el `Content-Type` del cliente |
| `Content-Length` | automático (Ktor `respondBytes`) | Dimensionado correcto |
| `Cache-Control` | `private, no-store` | Foto de un menor: prohibir cachés compartidos/proxies (§5) |
| `X-Content-Type-Options` | `nosniff` | Evitar MIME sniffing |
| `Content-Disposition` | `inline; filename="avatar.<ext>"` | Nombre opaco, no el original del cliente |

---

## 4. Contrato de endpoints

### 4.1 Códigos de estado (formato `ErrorDto` de §5.2 de ARQUITECTURA_BASE)

| Status | `error` | Cuándo |
|---|---|---|
| 200 | — | Éxito: PUT persiste + reemplaza; GET sirve el binario |
| 400 | `VALIDATION_ERROR` | **Todos los errores de input** (decisión aprobada; no se añade 413 al contrato). Con `FieldError("avatar", …)`: parte ausente/errónea ("Se requiere un archivo."); `Content-Type` fuera de whitelist o magic bytes corruptos/mismatch o binario truncado ("Formato no permitido: jpeg, png o webp."); tamaño > 2 MB ("Máximo 2 MB.") |
| 401 | `UNAUTHORIZED` | Token ausente, malformado, expirado, de audiencia `era-app-reset` o con claim `purpose` (challenge del proveedor `session-jwt`) |
| 403 | `ACCOUNT_INACTIVE` | La cuenta está en soft delete (`usuario.estado = 'eliminado'`) |
| 404 | `NOT_FOUND` | GET con token válido pero **sin foto personalizada** (`avatar` `NULL` o `preset:*`) — "No hay avatar personalizado." |
| 500 | `INTERNAL_ERROR` | Errores de I/O (disco lleno, permisos negados): se envuelve y mapea a 500 genérico; detalle solo en log del servidor, sin contenido binario ni clave sensible en texto plano |

No hay 409 ni 429. El 404 del GET es intencional y descriptivo: solo tiene sentido pedir el
binario cuando `GET /me` devuelve un valor `custom:*`.

### 4.2 Reglas de negocio del service (espejo de D/G/H)

- **403 `ACCOUNT_INACTIVE`:** cuenta en soft delete no puede subir ni servir (mismo mensaje que
  D/G/H: "La cuenta no está activa.").
- **404 defensivo:** token válido pero fila de `usuario` inexistente → `NOT_FOUND` (patrón de G/H).

---

## 5. Privacidad y seguridad (CLAUDE.md §6) — foto de un menor

La foto personalizada es una **imagen de un menor**: dato personal sensible. Eleva el manejo al
**nivel de exigencia de la autenticación** (CLAUDE.md §6). Garantías por construcción:

1. **GET autenticado, sin URL pública (triple barrera):**
   - La ruta vive dentro de `authenticate("session-jwt")`: sin token válido → 401 del challenge,
     el binario jamás se lee del disco.
   - **No hay servido estático**: no se instala `staticFiles`/`staticResources` sobre
     `AVATAR_STORAGE_DIR` (hoy no existe ningún static en `Application.kt`); se documenta la
     prohibición para no crear URLs públicas tipo `/uploads/<archivo>`.
   - Las claves son UUID opacos no adivinables: el valor de `usuario.avatar` no es un path
     servible.
2. **Cero logs del binario**: el contenido de la imagen nunca se loguea; los mensajes de error
   no repiten datos; la auditoría usa solo `idUsuario` y, como máximo, la clave de storage
   (opaca). Sin `CallLogging` (§5 del módulo H: verificado, no instalado).
3. **Caché prohibida**: `Cache-Control: private, no-store` en la respuesta (§3.2) para que
   ningún intermediario retenga la foto.
4. **Validación de entrada estricta** antes de tocar disco o BD: formato (magic bytes) y tamaño
   (§2.1), sin confiar en el cliente.
5. **I/O con fallo seguro**: si el UPDATE de BD falla, se elimina el archivo nuevo
   (compensación §2.4) — nunca un archivo huérfano apuntando a un estado inconsistente.

---

## 6. Modelo de datos

### 6.1 `usuario.avatar` (V1, sin migración)

| Campo | Tipo | Restricciones |
|---|---|---|
| avatar | VARCHAR(255) | Nulo permitido; valor `preset:*` (preset, sin archivo) o `custom:<uuid>.<ext>` (foto personalizada, §2.2) |

Columna existente en `V1__init_schema.sql`; **no se toca el esquema**. El archivo no vive en la
BD: la columna guarda la **clave de storage** (referencia opaca).

### 6.2 Acceso a datos

Nuevo método en `UsuarioRepository` (interfaz) + `ExposedUsuarioRepository` + fakes, espejo de
`actualizarContrasena`/`actualizarEstado`:

```kotlin
fun actualizarAvatar(idUsuario: Long, avatar: String?)  // UPDATE usuario SET avatar = ?
```

`String?` cubre fijar la clave `custom:*` (PUT) y limpiar a `NULL`/preset (futuro PATCH). Se
ejecuta dentro de la transacción de `AvatarService` (§2.4).

---

## 7. Diseño técnico por capas (propuesto)

Sigue el patrón existente (routes → controllers → services → repositories/storage → models,
ARQUITECTURA_BASE.md §1). **La dependencia del storage es solo por interfaz (§2.3):**
controllers/services/tests nunca importan `LocalDiskAvatarStorage`.

### 7.1 `routes/UserRoutes.kt` (modificación, decisión aprobada)

Los dos endpoints se añaden al `route("/api/v1/users")` existente, dentro del mismo
`authenticate("session-jwt")`:

```kotlin
route("/api/v1/users") {
    authenticate("session-jwt") {
        get("/me") { usuarioController.obtenerPerfil(call) }
        delete("/me") { usuarioController.eliminarCuenta(call) }
        put("/me/avatar") { avatarController.subirAvatar(call) }
        get("/me/avatar") { avatarController.obtenerAvatar(call) }
    }
}
```

### 7.2 `controllers/AvatarController.kt` (nuevo)

- `subirAvatar(call)`: lee multipart (`call.receiveMultipart()`), extrae la parte `avatar`,
  delega la validación de formato/tamaño y la persistencia en `AvatarService`, responde 200
  `MensajeResponseDto`.
- `obtenerAvatar(call)`: delega en `AvatarService`; responde los bytes con los headers de §3.2
  o 404 si no hay foto personalizada.
- La identidad viene de `SesionPrincipal` (patrón `UsuarioController`, `FeedbackController`).
  Las validaciones de forma "de seguridad" (formato, tamaño) viven en el validador del service
  (§7.4), no en el controller, por requerir bytes del stream.

### 7.3 `storage/` (nuevo paquete) — interfaz independiente

```kotlin
interface AvatarStorage {
    fun guardar(clave: String, bytes: ByteArray, contentType: String)   // persiste/sobrescribe
    fun leer(clave: String): ContenidoAvatar?                            // bytes + tipo canónico; null si no existe
    fun eliminar(clave: String)                                          // best-effort
}

data class ContenidoAvatar(val bytes: ByteArray, val contentType: String)

class LocalDiskAvatarStorage(private val dir: Path) : AvatarStorage { ... } // impl disco local, §2.3
```

La interfaz es el único contrato que consumen service y tests. `LocalDiskAvatarStorage` se
construye en `Application.kt` con `AppConfig.storage.avatarDir` e inyecta en el service.

### 7.4 `services/AvatarService.kt` (nuevo)

Puro de Ktor y de SQL. Depende de `UsuarioRepository`, `AvatarStorage` (interfaz) y
`TransactionRunner`. Responsabilidades:
- `subirAvatar(idUsuario, bytes, contentTypeCliente): MensajeResponseDto` — valida
  (magic bytes + fourCC + whitelist + tamaño ≤ 2 MB), genera clave `custom:<uuid>.<ext>`
  (extensión derivada del formato detectado), ejecuta el flujo de §2.4 con compensación.
- `obtenerAvatar(idUsuario): ContenidoAvatar` — verifica cuenta activa, lee `usuario.avatar`;
  si es `NULL`/`preset:*` → 404; si es `custom:*` → `storage.leer(clave)` (null → 404 defensivo).
- `eliminarAvatarSiPersonalizado(clave: String?)` — bloque reutilizable por el futuro PATCH
  (§2.4).

**Validación de binario (§2.1) en un helper dedicado** (`utils/AvatarValidador.kt` o interno):
`detectarFormato(bytes): FormatoAvatar?` por magic bytes + fourCC WebP. Determinista y testeable.

### 7.5 Models

- Sin DTO nuevo: request multipart (sin DTO serializable) y respuesta `MensajeResponseDto`
  (reutilizado).
- `ContenidoAvatar` (valor del storage) vive en `storage/` (§7.3), no cruza la API.

### 7.6 `config/` (modificación)

- `AppConfig` gana `storage: StorageConfig(avatarDir: String)`.
- `AppConfigLoader.toAppConfig()` lee `storage.avatarDir` de `application.yaml`.
- `resources/application.yaml`: `storage: { avatarDir: ${AVATAR_STORAGE_DIR} }`.
- `.env.example`: `AVATAR_STORAGE_DIR=<AVATAR_STORAGE_DIR>`.

### 7.7 `Application.kt` (modificación)

Instanciar `LocalDiskAvatarStorage(AppConfig.storage.avatarDir)`, `AvatarService` (con
`ExposedUsuarioRepository` + storage + `ExposedTransactionRunner`), `AvatarController` y montar
las rutas. `AVATAR_STORAGE_DIR` se resuelve de configuración, nunca hardcodeado.

**Sin cambios en `module.yaml` ni `libs.versions.toml`:** multipart (`receiveMultipart`,
`PartData`) y `respondBytes` viven en `ktor-server-core`; `UUID` e `ImageIO` son JDK. **Cero
dependencias nuevas** (regla CLAUDE.md #4 y decisión 7). **Sin migración nueva** (§9).

---

## 8. Tests previstos

| Suite | Cobertura |
|---|---|
| `services/AvatarServiceTest.kt` (unitario, `FakeUsuarioRepository` + `FakeAvatarStorage` + `TransactionRunner` fake) | PUT happy path (guarda en storage con clave `custom:*`, actualiza BD, borra la foto vieja); sobrescritura elimina el archivo previo; 403 cuenta inactiva sin escrituras ni archivos; 404 usuario inexistente; **compensación** (fallo del UPDATE → se elimina el archivo nuevo); GET devuelve bytes solo si `custom:*`; GET 404 con `NULL`/`preset:*`; `eliminarAvatarSiPersonalizado` con clave `custom:*` vs `preset:*`/`NULL`. |
| `utils/AvatarValidadorTest.kt` | Detección correcta de jpeg/png/webp por magic bytes; fourCC WebP (`VP8`/`VP8L`/`VP8X`); rechazo de `Content-Type` falso (octet-stream con magic real); binario truncado/corrupto rechazado; formato desconocido (`gif`, `pdf`) rechazado. |
| `storage/LocalDiskAvatarStorageTest.kt` | Guardar/leer/eliminar en directorio temporal; `leer` de clave inexistente → null; clave con `../` (traversal) rechazada; recreación de directorio faltante. |
| `routes/AvatarRoutesTest.kt` (Ktor TestHost con `configureAuthentication(JWT_CONFIG_TEST)`) | 401 sin token; 401 token de reseteo; 403 cuenta eliminada; 400 parte ausente; 400 > 2 MB; 400 formato inválido; 200 PUT + 200 GET verificando headers (`Content-Type` canónico, `Cache-Control: private, no-store`, `nosniff`, `Content-Disposition`); 404 GET sin foto personalizada. |

**Verificación transversal:** la suite usa `FakeAvatarStorage` (nunca disco real salvo el test
específico de `LocalDiskAvatarStorage`); el repo sigue sin CallLogging (§5); build exitoso y
suite completa en verde (196 previos + nuevos de I); `ConfigLoadTest` actualizado para exigir
`AVATAR_STORAGE_DIR` (igual que las demás env vars placeholder).

---

## 9. Migración, dependencias y datos

**Sin migración nueva:** `usuario.avatar` VARCHAR(255) ya existe en V1. **Sin tablas ni
columnas nuevas** (el binario vive en disco, no en la BD). **Sin dependencias nuevas**
(§7.7): `module.yaml` y `libs.versions.toml` no se tocan. **Nueva variable de entorno**
`AVATAR_STORAGE_DIR` (solo configuración, no código).

---

## 10. Trazabilidad

| Requisito / criterio | Caso de uso | Historia | Dónde se cumple |
|---|---|---|---|
| REQ-FUN-06 CA4 (avatar desde galería interna o dispositivo) | CU-06 | HU-06 | §1, §3.1 (PUT multipart autenticado, post-verificación) |
| REQ-FUN-06 CA5 (solo `avatar` y username editables) | CU-06 | HU-06 | §6.2 (el Módulo I toca únicamente `usuario.avatar`; el PATCH de username queda fuera de alcance) |
| CU-06 3a (cambiar avatar) y paso 5 (guardar) | CU-06 | HU-06 | §2.4 (sobrescritura), §4.1 (200) |
| CU-06 postcondición (datos actualizados) | CU-06 | HU-06 | §6.2, §7.4 (persistencia de la clave en `usuario.avatar`) |
| CLAUDE.md §6 (datos de menores, rigor de autenticación) | CU-06 | HU-06 | §5 (GET autenticado, sin static, `no-store`, cero logs del binario) |
| ARQUITECTURA_BASE §5.4 #7 (2 MB, whitelist + doble validación, UUID, retención, limpieza, cero deps) | CU-06 | HU-06 | §2.1, §2.2, §2.3, §2.4, §7.3, §9 |
| Mínimo privilegio (CLAUDE.md §6) | CU-06 | HU-06 | §3.1 (respuesta solo `message`), §3.2 (headers mínimos) |
| REQ-NF-01 (respuesta < 3 s) | CU-06 | HU-06 | I/O local de ≤ 2 MB; una transacción por operación; sin red externa |

---

## 11. Plan de implementación y estado

| Paso | Contenido | Estado |
|---|---|---|
| 1 | **Estructura y lógica:** `AvatarStorage` (interfaz) + `ContenidoAvatar` en `storage/`, `LocalDiskAvatarStorage`, `AvatarValidador` (magic bytes + fourCC), `AvatarService` (flujo §2.4 con compensación), `AvatarController` (multipart + bytes) | **Pendiente** |
| 2 | **Tests:** `AvatarValidadorTest`, `LocalDiskAvatarStorageTest` (directorio temporal), `AvatarServiceTest` (FakeAvatarStorage + FakeUsuarioRepository), `AvatarRoutesTest` (HTTP) | **Pendiente** |
| 3 | **Wiring y configuración:** `AppConfig.storage.avatarDir` + `application.yaml` + `.env.example`, método `actualizarAvatar` en `UsuarioRepository`/`ExposedUsuarioRepository`/`FakeUsuarioRepository`, rutas en `UserRoutes.kt`, `Application.kt` | **Pendiente** |
| 4 | **Documentación:** README (tabla de endpoints, suite) + CLAUDE.md §9 + `.env.example` actualizado | **Pendiente** |

**Verificación transversal:**
1. **Independencia de `AvatarStorage`:** ningún archivo fuera de `storage/` (ni controller, ni
   service, ni tests) importa `LocalDiskAvatarStorage`; solo la interfaz (§7.3).
2. **Validación mandatoria:** magic bytes + fourCC WebP como gatekeeper antes de cualquier
   escritura en disco o BD (§2.1).
3. **Sin URL pública:** GET dentro de `authenticate("session-jwt")`, sin servido estático, claves
   UUID opacas (§5).
4. **Ciclo de vida:** sobrescritura con compensación y `eliminarAvatarSiPersonalizado`
   reutilizable por el futuro PATCH (§2.4); cero logs del binario (§5); build + suite completa en
   verde (196 previos + nuevos de I).
