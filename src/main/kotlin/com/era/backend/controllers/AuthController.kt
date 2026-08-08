package com.era.backend.controllers

import com.era.backend.exceptions.FieldError
import com.era.backend.exceptions.ValidationException
import com.era.backend.models.dto.RegisterRequestDto
import com.era.backend.services.RegistrationService
import com.era.backend.utils.AvatarPreset
import com.era.backend.utils.Validators
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond

/**
 * Handler de `POST /api/v1/auth/register`. Valida la forma del input (primera barrera,
 * ARQUITECTURA_BASE.md §2.2) y delega las reglas de negocio en [RegistrationService].
 * No decide políticas de negocio (unicidad, fuerza de contraseña, vigencia).
 */
class AuthController(private val registrationService: RegistrationService) {

    /**
     * Endpoint `POST /api/v1/auth/register` (REQ-FUN-01, CU-01, HU-01, HU-15).
     *
     * Validaciones de forma (§4 controller):
     * - Presencia y tipos de todos los campos; longitudes máx (120/60/255/20).
     * - `correo`: regex `^[^@\s]+@[^@\s]+\.[^@\s]+$` y **normalización a minúsculas (V5)**.
     *   La normalización ocurre aquí, ANTES de delegar al service, para que el service
     *   trabaje siempre con datos limpios (unicidad, SMTP, comparaciones).
     * - `fechaNacimiento`: ISO `yyyy-MM-dd`, parseable a `LocalDate` y no futura (V9).
     * - `cedulaAcudiente`: 6–20 alfanuméricos (V8).
     * - `avatar`: si viene, debe ser un preset válido `preset:1|2|3` (V6).
     * - `confirmarContrasena == contrasena` → 400 `VALIDATION_ERROR` con `details`.
     *   (La política de contraseña NO se decide aquí; es regla del service.)
     *
     * Si algo falla, lanza [ValidationException] con todos los `details` por campo.
     * Delega en el service y mapea la respuesta a `RegisterResponseDto` (201 Created, V7).
     */
    suspend fun register(call: ApplicationCall): Unit {
        val request = call.receive<RegisterRequestDto>()

        val errores = mutableListOf<FieldError>()

        // Presencia (los tipos y campos ausentes ya los rechaza la deserialización como INVALID_REQUEST).
        mapOf(
            "nombreMenor" to request.nombreMenor,
            "fechaNacimiento" to request.fechaNacimiento,
            "nombreAcudiente" to request.nombreAcudiente,
            "cedulaAcudiente" to request.cedulaAcudiente,
            "correo" to request.correo,
            "nombreUsuario" to request.nombreUsuario,
            "contrasena" to request.contrasena,
            "confirmarContrasena" to request.confirmarContrasena,
        ).forEach { (campo, valor) ->
            if (valor.isBlank()) errores += FieldError(campo, "Es obligatorio.")
        }

        // Longitudes máximas (120/60/255/20).
        if (request.nombreMenor.length > 120) errores += FieldError("nombreMenor", "Máximo 120 caracteres.")
        if (request.nombreAcudiente.length > 120) errores += FieldError("nombreAcudiente", "Máximo 120 caracteres.")
        if (request.correo.length > 255) errores += FieldError("correo", "Máximo 255 caracteres.")
        if (request.cedulaAcudiente.length > 20) errores += FieldError("cedulaAcudiente", "Máximo 20 caracteres.")

        // Username (V4): 3–60 sin espacios.
        if (request.nombreUsuario.isNotBlank()) {
            if (request.nombreUsuario.length !in 3..60) {
                errores += FieldError("nombreUsuario", "Debe tener entre 3 y 60 caracteres.")
            }
            if (request.nombreUsuario.any { it.isWhitespace() }) {
                errores += FieldError("nombreUsuario", "No puede contener espacios.")
            }
        }

        // Correo (V5): formato.
        if (request.correo.isNotBlank() && !Validators.isValidEmail(request.correo)) {
            errores += FieldError("correo", "Formato de correo inválido.")
        }

        // Fecha de nacimiento (V9): ISO, parseable, no futura.
        if (request.fechaNacimiento.isNotBlank() && Validators.parseFechaNacimiento(request.fechaNacimiento) == null) {
            errores += FieldError("fechaNacimiento", "Fecha inválida o futura.")
        }

        // Cédula del acudiente (V8): 6–20 alfanuméricos.
        if (request.cedulaAcudiente.isNotBlank() && !Validators.isValidCedula(request.cedulaAcudiente)) {
            errores += FieldError("cedulaAcudiente", "Debe tener entre 6 y 20 caracteres alfanuméricos.")
        }

        // Avatar (V6): si viene, debe ser un preset válido.
        if (request.avatar != null && AvatarPreset.fromId(request.avatar) == null) {
            errores += FieldError("avatar", "Debe ser un preset válido: preset:1, preset:2 o preset:3.")
        }

        // Confirmación de contraseña (§4): regla de forma, coincide campo a campo.
        if (request.confirmarContrasena != request.contrasena) {
            errores += FieldError("confirmarContrasena", "No coincide con contrasena.")
        }

        if (errores.isNotEmpty()) {
            throw ValidationException("Datos de registro inválidos.", errores)
        }

        // Normalización V5: el service siempre trabaja con el correo en minúsculas.
        val requestNormalizado = request.copy(correo = request.correo.lowercase())

        // Reglas de negocio (unicidad, política de contraseña) en el service; 201 Created (V7).
        val respuesta = registrationService.register(requestNormalizado)
        call.respond(HttpStatusCode.Created, respuesta)
    }
}
