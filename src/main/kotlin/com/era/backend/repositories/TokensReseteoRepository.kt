package com.era.backend.repositories

import com.era.backend.models.entities.TokensReseteoRow
import java.time.LocalDateTime

/**
 * Acceso a datos de `tokens_reseteo` (Módulo C, ARQUITECTURA_BASE.md §2.4).
 *
 * Es una **interfaz** para que `PasswordResetService` (y los tests) dependan de la
 * abstracción, no de Exposed: en producción se inyecta [ExposedTokensReseteoRepository] y
 * en tests un `FakeTokensReseteoRepository` en memoria (sin MySQL).
 *
 * En producción, los métodos deben ejecutarse dentro de una transacción (la provee
 * `PasswordResetService` vía `TransactionRunner`).
 */
interface TokensReseteoRepository {

    /**
     * Persiste el [jti] del token puente de reseteo con `expira_en = now + 10 min` y
     * `consumido = false` (Módulo C paso 2). El JWT completo jamás se persiste ni se
     * loguea; solo su identificador (CLAUDE.md §6). El `UNIQUE(jti)` de V1 es la barrera
     * final de unicidad.
     *
     * @param row fila a insertar; `idToken` y `creadoEn` los asigna la base de datos.
     * @return id del token creado.
     */
    fun insert(row: TokensReseteoRow): Long

    /**
     * Busca el token por [jti] tomando un **lock de escritura** (`SELECT ... FOR UPDATE`).
     * Es la barrera del single-use (C-3): dos `/confirm` concurrentes con el mismo jti se
     * serializan; el segundo re-lee la fila tras el lock con `consumido = 1` y debe ser
     * rechazado. Nunca loguear el [jti] ni el token.
     *
     * Debe ejecutarse dentro de la misma transacción que lo consume.
     */
    fun findByJtiForUpdate(jti: String): TokensReseteoRow?

    /**
     * Marca el token como consumido (single-use) tras un cambio de contraseña exitoso
     * (Módulo C paso 3). Solo se invoca dentro de la transacción del cambio.
     */
    fun marcarConsumido(idToken: Long)

    /**
     * Limpieza lazy de tokens expirados del usuario (patrón V2): elimina sus filas cuyo
     * `expira_en` ya pasó, para no acumular registros muertos.
     */
    fun deleteExpiradosPorUsuario(idUsuario: Long)
}
