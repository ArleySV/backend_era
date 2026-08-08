package com.era.backend.utils

/**
 * Identificadores opacos de los 3 avatares preestablecidos de la app (V6, REQ-FUN-06).
 *
 * El prefijo reservado `preset:` distingue por construcción un preset (imagen local del
 * cliente, no se persiste) de una foto personalizada (Módulo I, `usuario.avatar`): una
 * foto nunca llega por el endpoint de registro (`modulo-a-analisis.md` §3.1).
 */
enum class AvatarPreset(val id: String) {
    PRESET_1("preset:1"),
    PRESET_2("preset:2"),
    PRESET_3("preset:3"),
    ;

    companion object {

        /**
         * Resuelve un identificador a su preset, o `null` si no es uno de los 3 válidos.
         * Regla de forma → controller (V6): cualquier otro valor se rechaza con 400
         * `VALIDATION_ERROR`.
         */
        fun fromId(id: String): AvatarPreset? =
            entries.firstOrNull { it.id == id }
    }
}
