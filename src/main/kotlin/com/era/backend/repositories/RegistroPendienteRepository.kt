package com.era.backend.repositories

import com.era.backend.models.entities.RegistroPendienteRow

/**
 * Acceso a datos de `registro_pendiente` (ARQUITECTURA_BASE.md §2.4).
 *
 * Es una **interfaz** para que `RegistrationService` (y los tests) dependan de la
 * abstracción, no de Exposed: en producción se inyecta
 * [ExposedRegistroPendienteRepository] y en tests un `FakeRegistroPendienteRepository` en
 * memoria (sin MySQL). El service nunca ve queries ni columnas.
 *
 * En producción, los métodos deben ejecutarse dentro de una transacción (la provee
 * `RegistrationService` vía `TransactionRunner`) para que las operaciones del registro
 * sean atómicas.
 */
interface RegistroPendienteRepository {

    /**
     * Persiste un registro pendiente (pasos 1 + 2) con hash bcrypt de contraseña y del
     * OTP, `expira_en = now + 10 min` e `intentos_fallidos = 0` (REQ-FUN-01, CU-01; §2).
     *
     * Seguridad (CLAUDE.md §6): nunca loguear `contrasena_hash` ni `codigo_hash` en texto
     * plano (ni el correo ni la cédula de la fila).
     *
     * @param row fila a insertar; `id` y `creadoEn` los asigna la base de datos.
     * @return id del registro creado.
     */
    fun insert(row: RegistroPendienteRow): Long

    /**
     * Busca un registro pendiente por correo (normalizado a minúsculas, V5) para el check
     * de unicidad de correo contra `registro_pendiente` no expirado (REQ-FUN-01 CA1, §4).
     *
     * Seguridad (CLAUDE.md §6): no loguear el correo ni los hashes de la fila.
     */
    fun findByEmail(correo: String): RegistroPendienteRow?

    /**
     * Busca un registro pendiente por nombre de usuario para el check de unicidad de
     * `nombreUsuario` (REQ-FUN-01, §4). No loguear datos de la fila.
     */
    fun findByUsername(nombreUsuario: String): RegistroPendienteRow?

    /**
     * Limpieza lazy de registros expirados por correo (V2): elimina la fila de [correo]
     * si su `expira_en` ya pasó, liberando el correo para un nuevo registro. Se invoca
     * desde `RegistrationService.register`, dentro de la misma transacción que valida e
     * inserta, para garantizar atomicidad.
     */
    fun deleteExpiredByEmail(correo: String)

    /**
     * Limpieza lazy de registros expirados por nombre de usuario (V2): elimina la fila de
     * [nombreUsuario] si su `expira_en` ya pasó, liberando el username para un nuevo
     * registro. Misma justificación que [deleteExpiredByEmail]: un registro abandonado no
     * debe bloquear el username para siempre (V1 aplica solo a cuentas `usuario` en soft
     * delete, no a pendientes expirados).
     */
    fun deleteExpiredByUsername(nombreUsuario: String)
}
