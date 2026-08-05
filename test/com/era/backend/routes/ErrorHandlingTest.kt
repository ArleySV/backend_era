package com.era.backend.routes

import com.era.backend.exceptions.AccountLockedException
import com.era.backend.exceptions.EmailAlreadyRegisteredException
import com.era.backend.exceptions.FieldError
import com.era.backend.exceptions.NotFoundException
import com.era.backend.exceptions.OtpInvalidException
import com.era.backend.exceptions.ValidationException
import com.era.backend.plugins.configurePlugins
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable

/**
 * Verifica el manejo centralizado de errores (ARQUITECTURA_BASE.md §5.2 y §5.3):
 * status codes por tipo de excepción, formato JSON consistente y que el 500 no
 * filtra detalles internos.
 */
class ErrorHandlingTest {

    @Serializable
    private data class EchoDto(val name: String)

    private fun Application.appWith(throwable: Throwable) {
        configurePlugins()
        routing { get("/boom") { throw throwable } }
    }

    private fun getFor(throwable: Throwable): Pair<HttpStatusCode, String> {
        var status: HttpStatusCode? = null
        var body: String? = null
        testApplication {
            application { appWith(throwable) }
            val response = client.get("/boom")
            status = response.status
            body = response.bodyAsText()
        }
        return checkNotNull(status) to checkNotNull(body)
    }

    @Test
    fun `ValidationException devuelve 400 con details por campo`() {
        val (status, body) =
            getFor(
                ValidationException(
                    "Datos inválidos.",
                    listOf(FieldError("password", "Debe tener al menos 8 caracteres.")),
                ),
            )
        assertEquals(HttpStatusCode.BadRequest, status)
        assertTrue(body.contains("\"error\":\"VALIDATION_ERROR\""))
        assertTrue(body.contains("\"field\":\"password\""))
        assertTrue(body.contains("\"path\":\"/boom\""))
        assertTrue(body.contains("\"status\":400"))
    }

    @Test
    fun `ConflictException de negocio devuelve 409`() {
        val (status, body) = getFor(EmailAlreadyRegisteredException("Ya existe una cuenta activa con este correo."))
        assertEquals(HttpStatusCode.Conflict, status)
        assertTrue(body.contains("\"error\":\"EMAIL_ALREADY_REGISTERED\""))
    }

    @Test
    fun `OtpInvalidException devuelve 401 con codigo OTP_INVALID_OR_EXPIRED`() {
        val (status, body) = getFor(OtpInvalidException("Código inválido o vencido."))
        assertEquals(HttpStatusCode.Unauthorized, status)
        assertTrue(body.contains("\"error\":\"OTP_INVALID_OR_EXPIRED\""))
    }

    @Test
    fun `AccountLockedException devuelve 423 Locked`() {
        val (status, body) = getFor(AccountLockedException("Cuenta bloqueada temporalmente."))
        assertEquals(HttpStatusCode.Locked, status)
        assertTrue(body.contains("\"error\":\"ACCOUNT_LOCKED\""))
    }

    @Test
    fun `NotFoundException devuelve 404`() {
        val (status, body) = getFor(NotFoundException("Nivel no encontrado."))
        assertEquals(HttpStatusCode.NotFound, status)
        assertTrue(body.contains("\"error\":\"NOT_FOUND\""))
    }

    @Test
    fun `Error inesperado devuelve 500 sin filtrar detalles internos`() {
        val (status, body) = getFor(IllegalStateException("detalle interno secreto"))
        assertEquals(HttpStatusCode.InternalServerError, status)
        assertTrue(body.contains("\"error\":\"INTERNAL_ERROR\""))
        assertFalse(body.contains("detalle interno secreto"))
        assertFalse(body.contains("IllegalStateException"))
    }

    @Test
    fun `JSON malformado devuelve 400 INVALID_REQUEST`() {
        var status: HttpStatusCode? = null
        var body: String? = null
        testApplication {
            application {
                configurePlugins()
                routing { post("/echo") { call.receive<EchoDto>() } }
            }
            val response =
                client.post("/echo") {
                    contentType(ContentType.Application.Json)
                    setBody("{ malformed")
                }
            status = response.status
            body = response.bodyAsText()
        }
        assertEquals(HttpStatusCode.BadRequest, checkNotNull(status))
        assertTrue(checkNotNull(body).contains("\"error\":\"INVALID_REQUEST\""))
    }
}
