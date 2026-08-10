package com.era.backend.repositories

import com.era.backend.models.entities.EstadoUsuario
import com.era.backend.models.entities.UsuarioRow
import java.time.LocalDateTime

/**
 * Acceso a datos de `usuario` (ARQUITECTURA_BASE.md §2.4).
 *
 * Es una **interfaz** para que `RegistrationService` (y los tests) dependan de la
 * abstracción, no de Exposed: en producción se inyecta [ExposedUsuarioRepository] y en
 * tests un `FakeUsuarioRepository` en memoria (sin MySQL). El service nunca ve SQL ni
 * columnas.
 *
 * En el Módulo A solo expone consultas de unicidad/estado; no escribe nada.
 * En producción, los métodos deben ejecutarse dentro de una transacción (la provee
 * `RegistrationService` vía `TransactionRunner`).
 */
interface UsuarioRepository {

    /**
     * Busca un usuario por correo (normalizado a minúsculas, V5). El service usa el
     * estado para decidir: `ACTIVO` → `EmailAlreadyRegisteredException`; `ELIMINADO` →
     * `EmailLockedException` (REQ-FUN-01 CA1, REQ-FUN-05; §4 service).
     *
     * Seguridad (CLAUDE.md §6): nunca loguear el correo, la cédula ni `contrasena_hash`
     * de la fila devuelta.
     */
    fun findByEmail(correo: String): UsuarioRow?

    /**
     * Lectura del usuario por correo con **lock de escritura** (`SELECT ... FOR UPDATE`)
     * para el login (Módulo B, `modulo-b-analisis.md` §5). Serializa intentos de login
     * concurrentes del mismo usuario: evita que dos requests lean el mismo contador de
     * `intentos_login_fallidos` y ambos escriban el mismo valor (lost-update). El lock
     * cubre también la posterior escritura del estado de login.
     *
     * Debe ejecutarse dentro de la misma transacción que escribe el estado de login
     * (la provee `LoginService` vía `TransactionRunner`).
     *
     * Seguridad (CLAUDE.md §6): nunca loguear el correo ni `contrasena_hash` de la fila.
     */
    fun findByEmailForUpdate(correo: String): UsuarioRow?

    /**
     * Verifica si [nombreUsuario] ya está en uso. Aplica a cuentas activas y eliminadas:
     * el username de una cuenta soft-deleted permanece ocupado (V1, REQ-FUN-01).
     * Sin loguear datos de la fila (CLAUDE.md §6).
     */
    fun existsByUsername(nombreUsuario: String): Boolean

    /**
     * Persiste un usuario. En el Módulo A.1 se usa en la conversión transaccional de
     * `registro_pendiente` → `usuario` + `acudiente` + `configuracion` (V1:55-57): la
     * fila se crea solo cuando el OTP se verificó, con estado `ACTIVO` y el hash bcrypt
     * de la contraseña ya calculado en el registro.
     *
     * Seguridad (CLAUDE.md §6): nunca loguear correo, cédula ni `contrasena_hash`.
     *
     * @return id del usuario creado (para las filas 1:1 de `acudiente` y `configuracion`).
     */
    fun insert(row: UsuarioRow): Long

    /**
     * Lectura del usuario por nombre de usuario con **lock de escritura**
     * (`SELECT ... FOR UPDATE`), para el login (B-6, `modulo-b-analisis.md` §5). La
     * coincidencia es **case-insensitive** (espejo de la collation `utf8mb4_unicode_ci`
     * del UNIQUE de V1): "Maria" y "maria" son el mismo usuario. Misma justificación de
     * serialización que [findByEmailForUpdate].
     *
     * Debe ejecutarse dentro de la misma transacción que escribe el estado de login.
     * Sin loguear datos de la fila (CLAUDE.md §6).
     */
    fun findByUsernameForUpdate(nombreUsuario: String): UsuarioRow?

    /**
     * Lectura del usuario por id con **lock de escritura** (`SELECT ... FOR UPDATE`) para
     * el cambio de contraseña (Módulo C, paso 3). Serializa reseteos concurrentes del
     * mismo usuario y hace atómica la secuencia leer-hash actual → verificar reuso →
     * actualizar contraseña dentro de la transacción de `PasswordResetService`.
     *
     * Seguridad (CLAUDE.md §6): nunca loguear `contrasena_hash` de la fila.
     */
    fun findByIdForUpdate(idUsuario: Long): UsuarioRow?

    /**
     * Lectura del usuario por id **sin lock de escritura**, para la consulta de perfil
     * (Módulo D, `GET /users/me`). Es una lectura pura: no hay actualización concurrente
     * que proteger (a diferencia de [findByIdForUpdate]). El service usa la fila para
     * verificar que la cuenta sigue `ACTIVO` (403 `ACCOUNT_INACTIVE` si fue eliminada) y
     * para mapear el `UsuarioPerfilDto`.
     *
     * Debe ejecutarse dentro de una transacción (la provee `UsuarioService` vía
     * `TransactionRunner`); Exposed exige contexto transaccional incluso para un SELECT.
     *
     * Seguridad (CLAUDE.md §6): nunca loguear correo, cédula ni `contrasena_hash` de la fila.
     */
    fun findById(idUsuario: Long): UsuarioRow?

    /**
     * Persiste el cambio de estado de la cuenta (Módulo E, REQ-FUN-05): la "eliminación"
     * es un **soft delete por estado** — `UPDATE usuario SET estado = 'eliminado'` —, nunca
     * un `DELETE` físico (CLAUDE.md §7). Espejo de `actualizarEstadoLogin` y
     * `actualizarContrasena` (un solo UPDATE de columna).
     *
     * Debe ejecutarse dentro de la transacción de `UsuarioService` (junto a la lectura con
     * [findByIdForUpdate] y la verificación de contraseña); el throw de la excepción de
     * dominio ocurre fuera del bloque para que solo el cambio de estado commitee.
     *
     * Seguridad (CLAUDE.md §6): nunca loguear la fila ni la contraseña verificada.
     */
    fun actualizarEstado(idUsuario: Long, estado: EstadoUsuario)

    /**
     * Persiste el estado de login del usuario (Módulo B, §5): el contador de intentos
     * fallidos consecutivos y la ventana de bloqueo. Un solo método cubre todos los
     * casos de escritura: incremento por fallo, apertura de ventana al 5.º fallo (B-3),
     * limpieza lazy de ventana expirada (B-2) y **reset tras éxito** (`0`, `NULL`), que
     * debe ejecutarse de forma atómica con la autenticación.
     *
     * Debe ejecutarse dentro de la transacción de `LoginService`; el throw de la
     * excepción de dominio ocurre fuera de ella para que esta escritura se commitee.
     */
    fun actualizarEstadoLogin(
        idUsuario: Long,
        intentosLoginFallidos: Int,
        bloqueadoHasta: LocalDateTime?,
    )

    /**
     * Persiste el nuevo hash bcrypt de la contraseña (Módulo C, paso 3, REQ-FUN-07). Se
     * usa tras validar el token puente de reseteo y la política de contraseña. Nunca
     * loguear el hash ni derivar la contraseña original (CLAUDE.md §6).
     *
     * Debe ejecutarse dentro de la transacción de `PasswordResetService` junto con la
     * marcación de consumido del token, para que el cambio y el single-use sean atómicos.
     */
    fun actualizarContrasena(idUsuario: Long, contrasenaHash: String)
}
