# Justificación: Pruebas CRUD de Base de Datos

## 1. Qué son

12 scripts SQL manuales para MySQL Workbench que validan directamente las operaciones
CRUD (Create, Read, Update, Delete) sobre cada una de las 12 tablas del esquema
`V1__init_schema.sql`, ejecutados contra la base de pruebas `era_db_test`.

**Archivos:** `scripts/crud/01_nivel.sql` … `12_intento.sql`

## 2. Por qué existen

El backend de ERA usa **Exposed** como capa de acceso a datos sobre MySQL 8.0. Los
294 tests automatizados (JUnit/Kotlin) validan la lógica de negocio en la capa de
servicio y rutas HTTP, pero **no prueban el esquema de base de datos directamente**.

Los scripts CRUD cierran esa brecha: validan que las tablas, constraints y
relaciones del esquema funcionan correctamente antes de que cualquier código de la
aplicación las use.

## 3. Qué validan

### 3.1 Operaciones CRUD por tabla

Cada script ejecuta los cuatro bloques fundamentales sobre su tabla:

| Operación | Qué hace | Ejemplo (tabla `usuario`) |
|---|---|---|
| **Create** | INSERT con datos válidos | Crear usuario con todos los campos obligatorios |
| **Read** | SELECT con filtros y JOINs | Leer usuario por correo, JOIN con acudiente |
| **Update** | UPDATE de uno o varios campos | Cambiar username, avatar, estado (soft delete) |
| **Delete** | DELETE o soft delete | Borrar configuración, cambiar estado a 'eliminado' |

### 3.2 Constraints de integridad referencial

| Constraint | Tipo | Qué valida | Script |
|---|---|---|---|
| `uq_usuario_correo` | UNIQUE | Un mismo correo no puede tener más de una cuenta | `04_usuario.sql` |
| `uq_usuario_nombre_usuario` | UNIQUE | Un mismo username no puede repetirse | `04_usuario.sql` |
| `uq_acudiente_usuario` | UNIQUE | Un usuario solo puede tener un acudiente (1:1) | `05_acudiente.sql` |
| `uq_config_usuario` | UNIQUE | Un usuario solo puede tener una configuración (1:1) | `06_configuracion.sql` |
| `uq_nivel_orden` | UNIQUE | Cada nivel tiene un orden único | `01_nivel.sql` |
| `uq_pregunta_nivel` | UNIQUE | Cada nivel tiene exactamente una pregunta | `02_pregunta.sql` |
| `uq_tokens_reseteo_jti` | UNIQUE | Cada token puente tiene un JTI único | `09_tokens_reseteo.sql` |
| `uq_progreso_usuario_nivel` | UNIQUE | Un usuario solo tiene un progreso por nivel | `11_progreso_usuario.sql` |
| `fk_acudiente_usuario` | RESTRICT | No se puede borrar usuario con acudiente vinculado | `05_acudiente.sql` |
| `fk_configuracion_usuario` | RESTRICT | No se puede borrar usuario con configuración | `06_configuracion.sql` |
| `fk_codigo_usuario` | RESTRICT | No se puede borrar usuario con código OTP activo | `08_codigo_verificacion.sql` |
| `fk_tokens_reseteo_usuario` | RESTRICT | No se puede borrar usuario con token de reseteo | `09_tokens_reseteo.sql` |
| `fk_comentario_usuario` | RESTRICT | No se puede borrar usuario con comentarios | `10_comentario.sql` |
| `fk_progreso_usuario` | RESTRICT | No se puede borrar usuario con progreso | `11_progreso_usuario.sql` |
| `fk_progreso_nivel` | RESTRICT | No se puede borrar nivel con progreso asociado | `11_progreso_usuario.sql` |
| `fk_intento_progreso` | RESTRICT | No se puede borrar progreso con intentos | `12_intento.sql` |
| `fk_pregunta_nivel` | CASCADE | Borrar nivel borra su pregunta | `02_pregunta.sql` |
| `fk_opcion_pregunta` | CASCADE | Borrar pregunta borra sus opciones | `03_opcion_respuesta.sql` |
| `fk_intento_opcion` | SET NULL | Borrar opción pone NULL en el intento | `12_intento.sql` |
| `ck_nivel_orden` | CHECK | El orden del nivel debe estar entre 1 y 20 | `01_nivel.sql` |

### 3.3 Soft delete

La tabla `usuario` nunca se borra físicamente. El script `04_usuario.sql` valida que:
- `UPDATE estado = 'eliminado'` funciona correctamente.
- El usuario sigue existiendo después del soft delete ( COUNT = 1 ).
- Esta es la única vía de baja del usuario (REQ-FUN-05).

## 4. Estrategia de testing (capas)

Los scripts CRUD son la **capa más baja** de la estrategia de pruebas del proyecto:

```
┌─────────────────────────────────────────────────┐
│  Capa 4: Pruebas E2E (scripts/*.ps1)           │  Flujo completo register→verify→login
├─────────────────────────────────────────────────┤
│  Capa 3: Tests de integración MySQL             │  MySqlIntegrationTest, MySqlConcurrenciaTest
│  (9 tests automatizados)                        │  TOCTOU, FOR UPDATE, rollback atómico
├─────────────────────────────────────────────────┤
│  Capa 2: Tests de servicio + HTTP               │  284 tests automatizados (JUnit/Kotlin)
│  (284 tests automatizados)                      │  Reglas de negocio, contratos REST, JWT
├─────────────────────────────────────────────────┤
│  Capa 1: Scripts CRUD de esquema                │  12 scripts SQL manuales (este documento)
│  (12 scripts manuales)                          │  Constraints, relaciones, soft delete
└─────────────────────────────────────────────────┘
```

**Por qué esta capa es necesaria:** si el esquema de MySQL falla (constraint mal
definido, FK incorrecta, tipo de dato inadecuado), toda la aplicación falla por
encima. Los scripts CRUD validan la fundación antes de construir sobre ella.

## 5. Evidencia de ejecución

Cada script se ejecutó en MySQL Workbench contra `era_db_test`. Los resultados
confirman:

- **12/12 scripts ejecutados exitosamente.**
- **Todos los constraints de integridad disparan el error esperado** cuando se
  violan (Error 1062 para UNIQUE, Error 1451/1452 para FK, Error 3819 para CHECK).
- **Los CASCADE y SET NULL funcionan correctamente** (borrar nivel cascadea a
  pregunta y opciones; borrar opción pone NULL en intento).
- **El soft delete preserva los datos** (usuario con estado='eliminado' sigue
  existiendo en la tabla).
- **Cada transacción se revirtió con ROLLBACK**, sin efectos permanentes sobre la
  base de pruebas.

## 6. Relación con los requisitos

| Requisito | Qué validan los scripts |
|---|---|
| REQ-FUN-01 (Registro) | Unicidad de correo y username, 1:1 usuario-acudiente-configuración |
| REQ-FUN-02 (Login) | Campo `intentos_login_fallidos` y `bloqueado_hasta` en tabla `usuario` |
| REQ-FUN-04 (Cierre de sesión) | No hay tabla de sesiones (stateless); los scripts validan que no existe |
| REQ-FUN-05 (Eliminar cuenta) | Soft delete por estado, FK RESTRICT impide borrado físico |
| REQ-FUN-06 (Cuenta del usuario) | Lectura y actualización de username/avatar en tabla `usuario` |
| REQ-FUN-07 (Recuperación) | Tablas `codigo_verificacion` y `tokens_reseteo` con constraints |
| REQ-FUN-10/11/12 (Progreso) | Tablas `nivel`, `progreso_usuario`, `intento` con merge y constraints |
| REQ-FUN-14 (Comentarios) | Tabla `comentario` con FK a usuario |
| REQ-NF-02 (Seguridad) | Contraseña hasheada (`contrasena_hash`), OTP hasheado, sin datos en claro |
