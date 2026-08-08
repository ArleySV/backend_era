package com.era.backend.utils

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Helpers sin estado de validación de forma (primera barrera del controller, §4).
 *
 * La política de contraseña (REQ-FUN-01 CA2) NO vive aquí: es regla de negocio del
 * service (`RegistrationService`).
 */
object Validators {

    /** Formato V5: al menos un carácter local, `@`, dominio con punto y sin espacios. */
    private val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    /** Cédula V8: 6–20 caracteres alfanuméricos. Solo formato y longitud, nunca el contenido. */
    private val CEDULA_REGEX = Regex("^[A-Za-z0-9]{6,20}$")

    /**
     * Valida el formato básico de correo (V5): regex `^[^@\s]+@[^@\s]+\.[^@\s]+$` y
     * longitud ≤ 255. El controller aplica además la normalización a minúsculas ANTES
     * de delegar al service (V5).
     */
    fun isValidEmail(value: String): Boolean =
        value.length <= 255 && EMAIL_REGEX.matches(value)

    /**
     * Valida la cédula del acudiente: 6–20 caracteres alfanuméricos (V8, HU-15 CA1).
     * Solo formato y longitud, nunca el contenido. Dato sensible (CLAUDE.md §6): no se
     * loguea ni se expone en respuestas.
     */
    fun isValidCedula(value: String): Boolean =
        CEDULA_REGEX.matches(value)

    /**
     * Parsea `yyyy-MM-dd` a [LocalDate] y rechaza fechas futuras (V9, REQ-FUN-01 CA3).
     * Sin rango de edad (la edad se calcula en el cliente).
     *
     * @return la fecha parseada, o `null` si el formato es inválido o es futura.
     */
    fun parseFechaNacimiento(value: String): LocalDate? =
        try {
            val fecha = LocalDate.parse(value) // ISO_LOCAL_DATE: yyyy-MM-dd
            if (fecha.isAfter(LocalDate.now())) null else fecha
        } catch (e: DateTimeParseException) {
            null
        }
}
