package com.era.backend.services

import at.favre.lib.crypto.bcrypt.BCrypt
import com.era.backend.exceptions.OtpInvalidException
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests de [OtpService] (REQ-FUN-01 CA4, CU-11, política P1). Verifican el contrato
 * que consumirá el Módulo A.1 (`verify-email`) y el C (recuperación de contraseña): OTP de
 * 6 dígitos, hash bcrypt de un solo sentido y mensaje genérico en toda falla
 * (ARQUITECTURA_BASE.md §5.3). Sin MySQL ni SMTP.
 */
class OtpServiceTest {

    private val service = OtpService(FakeOtpNotifier())

    @Test
    fun `generate produce 6 digitos numericos y con variedad`() {
        val valores = (1..50).map { service.generate() }
        assertTrue(valores.all { it.length == 6 && it.all { c -> c.isDigit() } })
        assertTrue(valores.distinct().size > 1)
    }

    @Test
    fun `hash genera hash bcrypt que verifica el codigo`() {
        val hash = service.hash("123456")
        assertTrue(hash.startsWith("\$2"), "Debe ser un hash bcrypt (prefijo \$2)")
        assertTrue(BCrypt.verifyer().verify("123456".toCharArray(), hash).verified)
    }

    @Test
    fun `verificar acepta el codigo correcto dentro de la vigencia`() {
        val code = "123456"
        val hash = service.hash(code)
        // No debe lanzar: código correcto, vigente y con intentos fallidos por debajo del tope P1.
        service.verificar(code, hash, intentosFallidos = 2, expiraEn = LocalDateTime.now().plusMinutes(10))
    }

    @Test
    fun `verificar rechaza codigo incorrecto`() {
        val hash = service.hash("123456")
        val ex = assertFailsWith<OtpInvalidException> {
            service.verificar("654321", hash, intentosFallidos = 0, expiraEn = LocalDateTime.now().plusMinutes(10))
        }
        assertEquals("OTP_INVALID_OR_EXPIRED", ex.errorCode)
    }

    @Test
    fun `verificar rechaza codigo vencido`() {
        val hash = service.hash("123456")
        assertFailsWith<OtpInvalidException> {
            service.verificar("123456", hash, intentosFallidos = 0, expiraEn = LocalDateTime.now().minusMinutes(1))
        }
    }

    @Test
    fun `verificar rechaza al superar el maximo de intentos fallidos (P1)`() {
        val hash = service.hash("123456")
        assertFailsWith<OtpInvalidException> {
            service.verificar("123456", hash, intentosFallidos = 3, expiraEn = LocalDateTime.now().plusMinutes(10))
        }
    }

    @Test
    fun `todas las fallas lanzan el mismo mensaje generico sin revelar la causa`() {
        val hash = service.hash("123456")
        val futuro = LocalDateTime.now().plusMinutes(10)
        val mensajes =
            listOf(
                assertFailsWith<OtpInvalidException> { service.verificar("000000", hash, 0, futuro) }.message,
                assertFailsWith<OtpInvalidException> { service.verificar("123456", hash, 0, LocalDateTime.now().minusMinutes(1)) }.message,
                assertFailsWith<OtpInvalidException> { service.verificar("123456", hash, 3, futuro) }.message,
            )
        assertEquals(1, mensajes.distinct().size)
    }
}
