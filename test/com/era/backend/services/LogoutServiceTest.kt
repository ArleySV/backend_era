package com.era.backend.services

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests unitarios del Módulo F (cierre de sesión, REQ-FUN-04, CU-05, HU-04).
 *
 * El service no consulta BD ni aplica reglas de negocio (arquitectura stateless,
 * ARQUITECTURA_BASE.md §5.4 Decisión 2): solo registra el cierre en el log y devuelve la
 * confirmación formal. Verificamos el contrato y la idempotencia.
 */
class LogoutServiceTest {

    private val service = LogoutService()

    @Test
    fun `cerrarSesion devuelve la confirmacion formal`() {
        val respuesta = service.cerrarSesion(1L)
        assertEquals("Sesión cerrada.", respuesta.message)
        assertEquals(LogoutService.MENSAJE_SESION_CERRADA, respuesta.message)
    }

    @Test
    fun `cerrarSesion es idempotente y no depende del idUsuario`() {
        val primera = service.cerrarSesion(42L)
        val segunda = service.cerrarSesion(7L)
        assertEquals(primera, segunda)
    }
}
