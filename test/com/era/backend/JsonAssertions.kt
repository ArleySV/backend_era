package com.era.backend

import kotlin.test.assertEquals
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Refuerzo de Fase 3 (contrato de éxito exacto + mínimo privilegio): afirma que el
 * JSON `body` expone EXACTAMENTE las claves indicadas por cada path (ni más, ni menos).
 * La igualdad de conjuntos de claves es la garantía más fuerte de que no se filtra
 * datos extra (cédula, hash, contadores, ids).
 *
 * Semántica de path (separador `.`):
 *  - ""            → objeto raíz.
 *  - "resumen"     → objeto anidado bajo esa clave.
 *  - "progreso[*]" → CADA elemento del array `progreso` debe tener el mismo conjunto exacto.
 *    Un array vacío pasa vacuamente (no hay elementos que violen la forma).
 */
fun assertExactKeys(body: String, vararg shapes: Pair<String, Set<String>>) {
    val raiz = Json.parseToJsonElement(body)
    for ((path, esperadas) in shapes) {
        val objetos = resolverObjetos(raiz, path)
        val conjuntos = objetos.map { it.keys }
        val base = conjuntos.firstOrNull() ?: continue
        conjuntos.forEach {
            assertEquals(base, it, "Todos los objetivos de '$path' deben compartir el mismo conjunto de claves.")
        }
        assertEquals(
            esperadas,
            base,
            "El objeto en '$path' debe exponer EXACTAMENTE $esperadas; real: $base.",
        )
    }
}

private fun resolverObjetos(raiz: JsonElement, path: String): List<JsonObject> {
    if (path.isEmpty()) {
        return listOf(raiz as? JsonObject ?: fail("La raíz del JSON debe ser un objeto, no ${raiz::class.simpleName}."))
    }
    var actuales: List<JsonElement> = listOf(raiz)
    for (segmento in path.split('.')) {
        val comodin = segmento.endsWith("[*]")
        val clave = if (comodin) segmento.removeSuffix("[*]") else segmento
        val siguientes = mutableListOf<JsonElement>()
        for (actual in actuales) {
            val obj = actual as? JsonObject
                ?: fail("La ruta '$path' cruza un valor que no es objeto en el segmento '$segmento'.")
            val valor = obj[clave] ?: fail("La ruta '$path' no existe: falta la clave '$clave'.")
            siguientes += if (comodin) {
                (valor as? JsonArray ?: fail("La clave '$clave' de '$path' debe ser un array para usar '[*]'."))
                    .toList()
            } else {
                listOf(valor)
            }
        }
        actuales = siguientes
    }
    return actuales.map { it as? JsonObject ?: fail("El destino de '$path' debe ser un objeto JSON.") }
}
