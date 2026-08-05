package com.era.backend.exceptions

import io.ktor.http.HttpStatusCode

/**
 * Base de todas las excepciones de dominio. Transporta el estado HTTP y el código
 * máquina (`errorCode`) que el plugin StatusPages traduce al `ErrorDto` de §5.2 de
 * `ARQUITECTURA_BASE.md`. Los controllers nunca capturan para reformatear: dejan
 * que StatusPages haga la conversión (consistencia garantizada).
 */
abstract class DomainException(
    val status: HttpStatusCode,
    val errorCode: String,
    override val message: String,
) : RuntimeException(message)
