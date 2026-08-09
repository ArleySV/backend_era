package com.era.backend.utils

import com.era.backend.exceptions.FieldError
import com.era.backend.exceptions.ValidationException

/**
 * Política de contraseña compartida por los módulos que crean o renuevan contraseñas
 * (REQ-FUN-01 CA2 / REQ-FUN-07 CA5; V3 y decisión C-6 del Módulo C).
 *
 * Antes del Módulo C esta lógica era privada de `RegistrationService`. Se extrajo a
 * `utils/` (cálculo puro, sin Ktor ni SQL) para que el Módulo C la reutilice al exigir
 * que la nueva contraseña cumpla la misma política (modulo-c-analisis.md §5, C-6):
 * un solo lugar donde vive la regla, sin duplicación entre alta y recuperación.
 *
 * Reglas:
 * - Longitud ≥8 y ≤72. El tope superior no es un requisito, sino un **límite técnico de
 *   bcrypt** (72 bytes) para evitar truncamiento silencioso: hashear un valor más largo
 *   descartaría los bytes sobrantes y la verificación fallaría de forma confusa.
 * - Debe incluir mayúscula, minúscula, número y símbolo (no letra, no dígito, no espacio).
 * - No puede ser igual al `nombreUsuario` (case-insensitive).
 * - No puede contener el `nombreMenor` ni sus tokens (case-insensitive). Se filtran tokens
 *   < 3 caracteres para no sobrerrestricir contra conectores ("de", "y", "a") ni letras
 *   sueltas (V3, interpretación mínima).
 *
 * Falla → [ValidationException] con `details` por regla incumplida. Es una regla de
 * negocio del service (NO del controller): no se decide en la capa de forma.
 *
 * Seguridad (CLAUDE.md §6): nunca loguear ni la contraseña ni el hash; función pura.
 */
object PasswordPolicy {

    fun validar(contrasena: String, nombreUsuario: String, nombreMenor: String) {
        val errores = mutableListOf<FieldError>()

        if (contrasena.length < 8) {
            errores += FieldError("contrasena", "Debe tener al menos 8 caracteres.")
        }
        if (contrasena.length > 72) {
            errores += FieldError("contrasena", "Máximo 72 caracteres.")
        }
        if (!contrasena.any { it.isUpperCase() }) {
            errores += FieldError("contrasena", "Debe incluir al menos una mayúscula.")
        }
        if (!contrasena.any { it.isLowerCase() }) {
            errores += FieldError("contrasena", "Debe incluir al menos una minúscula.")
        }
        if (!contrasena.any { it.isDigit() }) {
            errores += FieldError("contrasena", "Debe incluir al menos un número.")
        }
        if (!contrasena.any { !it.isLetterOrDigit() && !it.isWhitespace() }) {
            errores += FieldError("contrasena", "Debe incluir al menos un símbolo.")
        }
        if (contrasena.equals(nombreUsuario, ignoreCase = true)) {
            errores += FieldError("contrasena", "No puede ser igual al nombre de usuario.")
        }

        val tokensDelNombre = nombreMenor
            .trim()
            .split(Regex("\\s+"))
            .filter { it.length >= 3 }
        if (tokensDelNombre.isNotEmpty() && tokensDelNombre.any { contrasena.contains(it, ignoreCase = true) }) {
            errores += FieldError("contrasena", "No puede contener datos personales.")
        }

        if (errores.isNotEmpty()) {
            throw ValidationException("La contraseña no cumple la política de seguridad.", errores)
        }
    }
}
