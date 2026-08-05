package com.era.backend.exceptions

import io.ktor.http.HttpStatusCode

/**
 * Núcleo aprobado en ARQUITECTURA_BASE.md §5.3. Nombres y status HTTP se mantienen
 * verbatim; las excepciones de cada módulo (A…H) se agregan como subclases en
 * `ModuleExtensions.kt`, sin reemplazar este núcleo.
 */

/** Input válido en forma pero inválido en regla (contraseña débil, OTP malformado). */
class ValidationException(
    message: String,
    val details: List<FieldError> = emptyList(),
) : DomainException(HttpStatusCode.BadRequest, "VALIDATION_ERROR", message)

/** Login fallido → se mapea a mensaje genérico (nunca revela qué campo falló). */
class InvalidCredentialsException(message: String) :
    DomainException(HttpStatusCode.Unauthorized, "INVALID_CREDENTIALS", message)

/** OTP incorrecto o vencido → mensaje genérico. Un solo tipo para ambos casos. */
class OtpInvalidException(message: String) :
    DomainException(HttpStatusCode.Unauthorized, "OTP_INVALID_OR_EXPIRED", message)

/** Token de reseteo inválido, expirado (~10 min) o ya usado (single-use). */
class ResetTokenInvalidException(message: String) :
    DomainException(HttpStatusCode.Unauthorized, "RESET_TOKEN_INVALID", message)

/** Cuenta en soft delete intenta loguearse (REQ-FUN-05). */
class AccountInactiveException(message: String) :
    DomainException(HttpStatusCode.Forbidden, "ACCOUNT_INACTIVE", message)

/** Recurso inexistente; solo se usa donde es seguro informar. */
open class NotFoundException(
    message: String,
    errorCode: String = "NOT_FOUND",
) : DomainException(HttpStatusCode.NotFound, errorCode, message)

/** Correo o username ya en uso (REQ-FUN-01) y conflictos de negocio relacionados. */
open class ConflictException(
    message: String,
    errorCode: String = "CONFLICT",
) : DomainException(HttpStatusCode.Conflict, errorCode, message)

/** Bloqueo de 2 min tras 5 intentos fallidos de login (REQ-FUN-02, REQ-NF-02). */
class AccountLockedException(message: String) :
    DomainException(HttpStatusCode.Locked, "ACCOUNT_LOCKED", message)
