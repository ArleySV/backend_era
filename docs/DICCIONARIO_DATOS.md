# ERA — Diccionario de Datos

> Documentación oficial del sistema ERA. Refleja el esquema aplicado por la migración
> Flyway `V1__init_schema.sql`. Ver [`../CLAUDE.md`](../CLAUDE.md) para las reglas
> permanentes del proyecto y [`ARQUITECTURA_BASE.md`](./ARQUITECTURA_BASE.md) para el
> diseño en capas.
>
> Motor: MySQL 8.0 · InnoDB · utf8mb4. Ningún borrado físico de datos de usuario
> (soft delete vía `usuario.estado`); los FK que cuelgan de `usuario` usan
> `ON DELETE RESTRICT` como segunda barrera contra borrado físico accidental.

---

## `usuario`

Cuenta del menor de edad (usuario final de la app). Se crea únicamente cuando se
verifica el código de `registro_pendiente` (nunca queda en un estado a medias).

| Campo | Tipo | Restricciones | Descripción |
|---|---|---|---|
| id_usuario | INT UNSIGNED | PK, autoincremental | Identificador único del usuario |
| nombre_menor | VARCHAR(120) | No nulo | Nombre completo del menor (solo lectura tras el registro) |
| fecha_nacimiento | DATE | No nulo | Fecha de nacimiento del menor (solo lectura); la edad se calcula dinámicamente |
| correo | VARCHAR(255) | No nulo, único | Correo del acudiente, usado para login y notificaciones (solo lectura) |
| nombre_usuario | VARCHAR(60) | No nulo, único | Nombre visible en la app; editable desde "Mi cuenta" |
| contrasena_hash | VARCHAR(255) | No nulo | Hash bcrypt de la contraseña (nunca texto plano ni cifrado reversible) |
| avatar | VARCHAR(255) | Nulo permitido | Referencia (storage key) de la imagen personalizada subida vía Módulo I; NULL = el usuario tiene un avatar preestablecido, cuya selección es responsabilidad exclusiva del cliente y no se persiste en el servidor |
| intentos_login_fallidos | TINYINT UNSIGNED | No nulo, default 0 | Contador de intentos fallidos consecutivos de login (REQ-FUN-02) |
| bloqueado_hasta | DATETIME | Nulo permitido | Fin del bloqueo temporal tras 5 intentos fallidos (2 min, REQ-NF-02) |
| estado | ENUM('activo','eliminado') | No nulo, default 'activo' | Soft delete (REQ-FUN-05); una cuenta 'eliminado' no puede iniciar sesión |
| creado_en | DATETIME | No nulo, default CURRENT_TIMESTAMP | Auditoría: alta del registro |
| actualizado_en | DATETIME | No nulo, on update CURRENT_TIMESTAMP | Auditoría: última modificación |

---

## `acudiente`

Datos del adulto responsable que autoriza y acompaña el registro. Relación 1:1 con
`usuario`.

| Campo | Tipo | Restricciones | Descripción |
|---|---|---|---|
| id_acudiente | INT UNSIGNED | PK, autoincremental | Identificador único |
| id_usuario | INT UNSIGNED | No nulo, único, FK → usuario.id_usuario (RESTRICT/CASCADE) | Menor asociado |
| nombre_completo | VARCHAR(120) | No nulo | Nombre completo del acudiente (solo lectura) |
| numero_cedula | VARCHAR(20) | No nulo | Documento de identidad del acudiente (solo lectura) |
| creado_en | DATETIME | No nulo, default CURRENT_TIMESTAMP | Auditoría |
| actualizado_en | DATETIME | No nulo, on update CURRENT_TIMESTAMP | Auditoría |

---

## `registro_pendiente`

Datos del registro (pasos 1 y 2) mientras el correo no ha sido verificado.
Ninguna fila de `usuario` existe todavía; al verificar el código, el service crea
`usuario` + `acudiente` en una transacción y elimina esta fila.

| Campo | Tipo | Restricciones | Descripción |
|---|---|---|---|
| id_registro | INT UNSIGNED | PK, autoincremental | Identificador único |
| correo | VARCHAR(255) | No nulo, único | Correo ingresado en el paso 2; libera automáticamente si expira sin verificar |
| nombre_usuario | VARCHAR(60) | No nulo, único | Nombre de usuario elegido |
| contrasena_hash | VARCHAR(255) | No nulo | Hash bcrypt de la contraseña elegida |
| nombre_menor | VARCHAR(120) | No nulo | Nombre completo del menor (paso 1) |
| fecha_nacimiento | DATE | No nulo | Fecha de nacimiento del menor (paso 1) |
| nombre_acudiente | VARCHAR(120) | No nulo | Nombre completo del acudiente (paso 1) |
| cedula_acudiente | VARCHAR(20) | No nulo | Cédula del acudiente (paso 1) |
| avatar | VARCHAR(255) | Nulo permitido | Identificador del avatar preestablecido elegido en el paso 2 (constante de preset, ver Módulo A §3.1/V6); esta fila nunca contiene una foto personalizada — esa solo existe post-verificación (Módulo I) |
| codigo_hash | VARCHAR(255) | No nulo | Hash bcrypt del código OTP de 6 dígitos enviado |
| intentos_fallidos | TINYINT UNSIGNED | No nulo, default 0 | Intentos fallidos al verificar el código (límite de fuerza bruta) |
| expira_en | DATETIME | No nulo | Vigencia del código (10 min, REQ-FUN-01) |
| creado_en | DATETIME | No nulo, default CURRENT_TIMESTAMP | Auditoría |

---

## `codigo_verificacion`

Código OTP exclusivo del flujo de **recuperación de contraseña** (Módulo C); el
usuario ya existe en este punto. La verificación de correo del registro usa
`registro_pendiente.codigo_hash`.

| Campo | Tipo | Restricciones | Descripción |
|---|---|---|---|
| id_codigo | INT UNSIGNED | PK, autoincremental | Identificador único |
| id_usuario | INT UNSIGNED | No nulo, FK → usuario.id_usuario (RESTRICT/CASCADE) | Dueño del código |
| codigo_hash | VARCHAR(255) | No nulo | Hash bcrypt del código de 6 dígitos (nunca texto plano) |
| intentos_fallidos | TINYINT UNSIGNED | No nulo, default 0 | Límite de fuerza bruta contra el código |
| expira_en | DATETIME | No nulo | Vigencia de 10 minutos (REQ-FUN-07) |
| ultimo_envio_en | DATETIME | Nulo permitido | Último envío del OTP de recuperación (throttle P2, 60 s; migración V3/C-2) |
| usado | TINYINT(1) | No nulo, default 0 | Marca de uso único (single-use) |
| creado_en | DATETIME | No nulo, default CURRENT_TIMESTAMP | Auditoría |

---

## `tokens_reseteo`

Token puente de corta vida emitido por `password-reset/verify` y consumido por
`password-reset/confirm` (ver ARQUITECTURA_BASE.md §2.3).

| Campo | Tipo | Restricciones | Descripción |
|---|---|---|---|
| id_token | INT UNSIGNED | PK, autoincremental | Identificador único |
| jti | VARCHAR(64) | No nulo, único | Identificador único del JWT de reseteo (garantiza single-use) |
| id_usuario | INT UNSIGNED | No nulo, FK → usuario.id_usuario (RESTRICT/CASCADE) | Dueño del token |
| expira_en | DATETIME | No nulo | Vigencia ~10 minutos |
| consumido | TINYINT(1) | No nulo, default 0 | Marca de uso único |
| creado_en | DATETIME | No nulo, default CURRENT_TIMESTAMP | Auditoría |

---

## `configuracion`

Preferencias sincronizables del usuario (REQ-FUN-13). Relación 1:1 con `usuario`.

| Campo | Tipo | Restricciones | Descripción |
|---|---|---|---|
| id_config | INT UNSIGNED | PK, autoincremental | Identificador único |
| id_usuario | INT UNSIGNED | No nulo, único, FK → usuario.id_usuario (RESTRICT/CASCADE) | Dueño de la configuración |
| sonido | TINYINT(1) | No nulo, default 1 | Efectos de sonido activos/inactivos |
| musica | TINYINT(1) | No nulo, default 1 | Música de fondo activa/inactiva |
| tema_visual | ENUM('claro','oscuro') | No nulo, default 'claro' | Tema visual de la app |
| tamano_texto | ENUM('pequeno','mediano','grande') | No nulo, default 'mediano' | Tamaño del texto |
| actualizado_en | DATETIME | No nulo, on update CURRENT_TIMESTAMP | Auditoría / marca de última sincronización |

---

## `comentario`

Comentarios y sugerencias enviados por el usuario (REQ-FUN-14).

| Campo | Tipo | Restricciones | Descripción |
|---|---|---|---|
| id_comentario | INT UNSIGNED | PK, autoincremental | Identificador único |
| id_usuario | INT UNSIGNED | No nulo, FK → usuario.id_usuario (RESTRICT/CASCADE) | Autor del comentario |
| contenido | TEXT | No nulo | Texto libre enviado por el usuario |
| enviado_en | DATETIME | No nulo, default CURRENT_TIMESTAMP | Fecha de envío |

---

## `nivel`

Catálogo de los 20 niveles de trivia (contenido, no datos de un menor).

| Campo | Tipo | Restricciones | Descripción |
|---|---|---|---|
| id_nivel | INT UNSIGNED | PK, autoincremental | Identificador único |
| titulo | VARCHAR(120) | No nulo | Título/tema del nivel |
| orden | TINYINT UNSIGNED | No nulo, único, CHECK 1-20 | Posición consecutiva del nivel |

---

## `pregunta`

Exactamente una pregunta por nivel (REQ-FUN-10, CA4).

| Campo | Tipo | Restricciones | Descripción |
|---|---|---|---|
| id_pregunta | INT UNSIGNED | PK, autoincremental | Identificador único |
| id_nivel | INT UNSIGNED | No nulo, único, FK → nivel.id_nivel (CASCADE) | Nivel al que pertenece |
| enunciado | TEXT | No nulo | Texto de la pregunta |
| imagen_url | VARCHAR(255) | Nulo permitido | Imagen alusiva opcional (REQ-FUN-10, CA5) |

---

## `opcion_respuesta`

Tres opciones de respuesta por pregunta, solo una correcta.

| Campo | Tipo | Restricciones | Descripción |
|---|---|---|---|
| id_opcion | INT UNSIGNED | PK, autoincremental | Identificador único |
| id_pregunta | INT UNSIGNED | No nulo, FK → pregunta.id_pregunta (CASCADE) | Pregunta a la que pertenece |
| texto_opcion | VARCHAR(255) | No nulo | Texto de la opción |
| es_correcta | TINYINT(1) | No nulo, default 0 | Marca la opción correcta |

---

## `progreso_usuario`

Estado de cada nivel para cada usuario; espejo remoto del progreso offline-first
del cliente (REQ-FUN-10/11/12, CU-08, CU-12).

| Campo | Tipo | Restricciones | Descripción |
|---|---|---|---|
| id_progreso | INT UNSIGNED | PK, autoincremental | Identificador único |
| id_usuario | INT UNSIGNED | No nulo, FK → usuario.id_usuario (RESTRICT/CASCADE) | Dueño del progreso |
| id_nivel | INT UNSIGNED | No nulo, FK → nivel.id_nivel (RESTRICT/CASCADE) | Nivel al que corresponde |
| estado_nivel | ENUM('bloqueado','disponible','completado') | No nulo, default 'bloqueado' | Estado del nivel para el usuario |
| intentos_totales | INT UNSIGNED | No nulo, default 0 | Reintentos acumulados en el nivel |
| intentos_fallidos_consecutivos | TINYINT UNSIGNED | No nulo, default 0 | Fallos consecutivos; dispara la pausa a los 2 |
| pausa_activa | TINYINT(1) | No nulo, default 0 | Si la pausa "Estírate y respira" está en curso |
| pausa_hasta | DATETIME | Nulo permitido | Fin de la pausa de 60 segundos |
| completado_en | DATETIME | Nulo permitido | Fecha en que se superó el nivel |
| ultima_interaccion | DATETIME | No nulo, on update CURRENT_TIMESTAMP | Última actividad registrada en el nivel |

*Restricción adicional:* único por `(id_usuario, id_nivel)`.

---

## `intento`

Registro de cada respuesta dentro de un nivel (para reintentos y auditoría).

| Campo | Tipo | Restricciones | Descripción |
|---|---|---|---|
| id_intento | INT UNSIGNED | PK, autoincremental | Identificador único |
| id_progreso | INT UNSIGNED | No nulo, FK → progreso_usuario.id_progreso (RESTRICT/CASCADE) | Progreso al que pertenece el intento |
| id_opcion_elegida | INT UNSIGNED | Nulo permitido, FK → opcion_respuesta.id_opcion (SET NULL/CASCADE) | Opción seleccionada (nulo si venció el cronómetro) |
| fue_correcto | TINYINT(1) | No nulo, default 0 | Resultado del intento |
| segundos_restantes | TINYINT UNSIGNED | No nulo, default 0 | Segundos que quedaban en el cronómetro al responder |
| registrado_en | DATETIME | No nulo, default CURRENT_TIMESTAMP | Fecha del intento |

---

## Relaciones (resumen)

```
usuario 1───1 acudiente
usuario 1───1 configuracion
usuario 1───N codigo_verificacion
usuario 1───N tokens_reseteo
usuario 1───N comentario
usuario 1───N progreso_usuario

nivel   1───1 pregunta
pregunta 1───N opcion_respuesta
nivel   1───N progreso_usuario

progreso_usuario 1───N intento
opcion_respuesta 0───N intento   (id_opcion_elegida nulo si venció el cronómetro)

registro_pendiente  (sin FK — precede a la existencia del usuario)
```

## Notas de diseño

- **PK:** `INT UNSIGNED AUTO_INCREMENT` en todas las tablas (decisión del equipo,
  en lugar de UUID).
- **FK hacia `usuario` (directa o transitiva vía `progreso_usuario`):**
  `ON DELETE RESTRICT ON UPDATE CASCADE` — refuerza a nivel de base de datos la
  regla de "nunca borrado físico" (CLAUDE.md §7, REQ-FUN-05).
- **FK del catálogo de trivia** (`pregunta→nivel`, `opcion_respuesta→pregunta`):
  `ON DELETE CASCADE` — es contenido administrado por el equipo, no datos de un
  menor.
- **Ningún OTP ni token se guarda en texto plano**: `codigo_verificacion.codigo_hash`
  y `registro_pendiente.codigo_hash` son hashes bcrypt.
- **Unicidad de correo/usuario durante el registro:** el service debe validar
  contra `usuario` **y** `registro_pendiente` a la vez, para evitar que dos
  registros a medias reserven el mismo correo en paralelo.
