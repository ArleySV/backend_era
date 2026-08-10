package com.era.backend.repositories

/**
 * Acceso a datos del catálogo `nivel` (ARQUITECTURA_BASE.md §2.4), solo lo que el Módulo G
 * necesita para la sincronización (`modulo-g-analisis.md` §2, §5.2): resolver el
 * identificador estable del wire `orden` (1..20, `UNIQUE` con `CHECK 1..20` en V1) hacia la
 * PK interna `id_nivel` y viceversa. El catálogo jamás se expone por API.
 *
 * Interfaz para que `ProgressSyncService` (y los tests) dependan de la abstracción, no de
 * Exposed: producción usa [ExposedNivelRepository], los tests un fake en memoria. Debe
 * ejecutarse dentro de una transacción (la provee `ProgressSyncService` vía
 * [TransactionRunner]).
 */
interface NivelRepository {

    /**
     * Validación de integridad §5.2: subconjunto de los [ordenes] recibidos que **sí**
     * existen en `nivel`. El service rechaza con 400 `VALIDATION_ERROR` cualquier `orden`
     * recibido que no esté en el resultado, antes de escribir nada.
     */
    fun ordenesExistentes(ordenes: Collection<Int>): Set<Int>

    /**
     * Resolución `orden → id_nivel` (la FK `progreso_usuario.id_nivel` apunta a la PK
     * interna, no al `orden` del wire). `null` si el nivel no existe en el catálogo.
     */
    fun findByIdOrden(orden: Int): Long?

    /**
     * Resolución inversa `id_nivel → orden` para construir el snapshot de respuesta (el wire
     * usa `orden`, nunca la PK interna). Devuelve un mapa solo con los niveles existentes;
     * toda fila de `progreso_usuario` referencia un nivel existente (FK `RESTRICT`).
     */
    fun findOrdenesByIdNiveles(idNiveles: Collection<Long>): Map<Long, Int>
}
