package com.era.backend.models.entities

import org.jetbrains.exposed.v1.core.Table

/**
 * Mapeo 1:1 de la tabla `nivel` (DICCIONARIO_DATOS.md §nivel), el catálogo de los 20
 * niveles de trivia. Es **contenido administrado por el equipo** (FK del catálogo con
 * `CASCADE`), NO datos de un menor.
 *
 * En el Módulo G (`modulo-g-analisis.md` §2) el backend **no sirve este catálogo por
 * API**: solo lo usa como ancla referencial de `progreso_usuario.id_nivel` (FK
 * `RESTRICT`) y para resolver el identificador estable del wire — `nivel.orden` (1..20,
 * `CHECK` en V1) — hacia la PK interna `id_nivel`.
 */
object NivelTable : Table("nivel") {
    val idNivel = integer("id_nivel").autoIncrement()
    val titulo = varchar("titulo", 120)
    val orden = ubyte("orden")

    override val primaryKey = PrimaryKey(idNivel)
}

/**
 * Fila materializada de [NivelTable]. No expone contenido del juego por API: solo sirve
 * de registro del catálogo para la resolución `orden ↔ id_nivel` en la sincronización.
 */
data class NivelRow(
    val idNivel: Long,
    val titulo: String,
    val orden: Int,
)
