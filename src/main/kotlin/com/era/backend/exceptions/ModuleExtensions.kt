package com.era.backend.exceptions

import io.ktor.http.HttpStatusCode

/**
 * Extensiones aprobadas sobre el núcleo de §5.3 (decisión 2026-08-04). No reemplazan
 * el árbol aprobado: agregan casos concretos de los módulos A…H como subclases.
 */

/** Acudiente inexistente durante el flujo de registro (solo donde es seguro informar). */
class GuardianNotFoundException(message: String) :
    NotFoundException(message, "GUARDIAN_NOT_FOUND")

/** Un mismo correo no puede registrar más de una cuenta activa (REQ-FUN-01). */
class EmailAlreadyRegisteredException(message: String) :
    ConflictException(message, "EMAIL_ALREADY_REGISTERED")

/** Correo de cuenta eliminada (soft delete), bloqueado hasta liberación administrativa. */
class EmailLockedException(message: String) :
    ConflictException(message, "EMAIL_LOCKED")

/** Conflicto de datos locales vs remotos durante la sincronización (CU-12). */
class SyncConflictException(message: String) :
    ConflictException(message, "SYNC_CONFLICT")

/** La nueva contraseña no puede repetir la anterior (REQ-FUN-07). */
class PasswordReuseException(message: String) :
    ConflictException(message, "PASSWORD_REUSED")

/** Reenvío de OTP solicitado demasiado pronto (política definida en Módulo A.1). */
class OtpResendThrottledException(message: String) :
    DomainException(HttpStatusCode.TooManyRequests, "OTP_RESEND_THROTTLED", message)
