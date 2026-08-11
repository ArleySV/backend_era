package com.era.backend.repositories

/**
 * Acceso a datos de `comentario` (ARQUITECTURA_BASE.md §2.4, `modulo-h-analisis.md` §6).
 *
 * Interfaz para que `ComentarioService` (y los tests) dependan de la abstracción, no de
 * Exposed: producción usa [ExposedComentarioRepository], los tests un fake en memoria.
 *
 * El requisito es puramente de **escritura** (CU-10: "el comentario queda registrado en el
 * sistema"). No hay lectura, UPDATE ni DELETE de comentarios (CLAUDE.md §7 — sin borrado
 * físico; el módulo solo recibe).
 */
interface ComentarioRepository {

    /**
     * Persiste un comentario. `contenido` llega ya sanitizado (`.trim()`, §7.3 de
     * `modulo-h-analisis.md`); `enviado_en` lo asigna la base (`DEFAULT CURRENT_TIMESTAMP`).
     *
     * @return id del comentario creado (para auditoría; nunca se expone en la respuesta).
     */
    fun insertar(idUsuario: Long, contenido: String): Long
}
