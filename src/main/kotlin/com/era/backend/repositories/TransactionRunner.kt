package com.era.backend.repositories

import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Abstracción de la frontera transaccional de Exposed.
 *
 * Por qué existe: `RegistrationService.register` debe ejecutar limpieza lazy + checks de
 * unicidad + inserción en UNA sola transacción (atomicidad, anti-TOCTOU) y, a la vez, el
 * service debe seguir siendo testeable con repositorios simulados sin levantar MySQL
 * (ARQUITECTURA_BASE.md §2.3). Los tests inyectan un runner fake que solo ejecuta el bloque;
 * producción usa [ExposedTransactionRunner].
 *
 * Nota: el bloque devuelve `Unit` (las operaciones de escritura/chequeo del registro no
 * necesitan devolver valor fuera de la transacción), lo que permite mantenerlo como
 * `fun interface` (SAM) para los tests.
 */
fun interface TransactionRunner {
    fun run(block: () -> Unit)
}

/** Implementación real: delega en `transaction { }` de Exposed sobre el pool Hikari/MySQL. */
object ExposedTransactionRunner : TransactionRunner {
    override fun run(block: () -> Unit) {
        transaction { block() }
    }
}
