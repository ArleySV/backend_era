package com.era.backend.repositories

import com.era.backend.models.entities.UsuarioRow

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
     * Verifica si [nombreUsuario] ya está en uso. Aplica a cuentas activas y eliminadas:
     * el username de una cuenta soft-deleted permanece ocupado (V1, REQ-FUN-01).
     * Sin loguear datos de la fila (CLAUDE.md §6).
     */
    fun existsByUsername(nombreUsuario: String): Boolean
}
