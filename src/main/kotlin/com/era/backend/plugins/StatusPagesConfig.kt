package com.era.backend.plugins

import com.era.backend.exceptions.DomainException
import com.era.backend.exceptions.ErrorDto
import com.era.backend.exceptions.ValidationException
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.ContentConvertException
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import java.time.Instant
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory

/**
 * Plugins Ktor transversales (ARQUITECTURA_BASE.md §3): ContentNegotiation (JSON) y
 * StatusPages (manejo centralizado de errores). Es el ÚNICO lugar que traduce
 * dominio → HTTP; los controllers no capturan para reformatear.
 */
fun Application.configurePlugins() {
    install(ContentNegotiation) {
        json()
    }

    install(StatusPages) {
        exception<DomainException> { call, cause ->
            call.respond(
                cause.status,
                ErrorDto(
                    timestamp = Instant.now().toString(),
                    status = cause.status.value,
                    error = cause.errorCode,
                    message = cause.message,
                    path = call.request.path(),
                    details = if (cause is ValidationException) cause.details else emptyList(),
                ),
            )
        }

        // Requests malformados: JSON inválido (kotlinx → JsonConvertException/SerializationException),
        // parámetros faltantes, body/headers incorrectos y fallos de transformación → 400 genérico.
        exception<ContentConvertException> { call, _ ->
            call.respondInvalidRequest()
        }
        exception<SerializationException> { call, _ ->
            call.respondInvalidRequest()
        }
        exception<BadRequestException> { call, _ ->
            call.respondInvalidRequest()
        }
        exception<ContentTransformationException> { call, _ ->
            call.respondInvalidRequest()
        }

        // Error inesperado → 500 genérico. El detalle completo se loguea SOLO en
        // servidor (CLAUDE.md §6: nunca loguear datos personales ni cuerpos).
        exception<Throwable> { call, cause ->
            LoggerFactory.getLogger("com.era.backend.error").error(
                "Error inesperado en {} {}: {}",
                call.request.httpMethod.value,
                call.request.path(),
                cause::class.qualifiedName,
                cause,
            )
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorDto(
                    timestamp = Instant.now().toString(),
                    status = HttpStatusCode.InternalServerError.value,
                    error = "INTERNAL_ERROR",
                    message = "Ocurrió un error interno. Inténtalo de nuevo.",
                    path = call.request.path(),
                ),
            )
        }
    }
}

private suspend fun ApplicationCall.respondInvalidRequest() {
    respond(
        HttpStatusCode.BadRequest,
        ErrorDto(
            timestamp = Instant.now().toString(),
            status = HttpStatusCode.BadRequest.value,
            error = "INVALID_REQUEST",
            message = "La solicitud no es válida.",
            path = request.path(),
        ),
    )
}
