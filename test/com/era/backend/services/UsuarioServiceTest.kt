package com.era.backend.services

import at.favre.lib.crypto.bcrypt.BCrypt
import com.era.backend.exceptions.AccountInactiveException
import com.era.backend.exceptions.InvalidCredentialsException
import com.era.backend.exceptions.NotFoundException
import com.era.backend.models.dto.EliminarCuentaRequestDto
import com.era.backend.models.entities.EstadoUsuario
import com.era.backend.models.entities.UsuarioRow
import com.era.backend.repositories.FakeUsuarioRepository
import com.era.backend.repositories.TransactionRunner
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests de [UsuarioService] (Módulos D y E, `modulo-d-analisis.md` §7). Sin MySQL:
 * se usa `FakeUsuarioRepository` (espejo en memoria de `findById`, `findByIdForUpdate` y
 * `actualizarEstado`).
 */
class UsuarioServiceTest {

    private fun servicioCon(
        seed: (FakeUsuarioRepository) -> Unit,
    ): Pair<UsuarioService, FakeUsuarioRepository> {
        val fake = FakeUsuarioRepository()
        seed(fake)
        val servicio = UsuarioService(fake, TransactionRunner { it() })
        return servicio to fake
    }

    private fun usuario(
        id: Long = 1L,
        estado: EstadoUsuario = EstadoUsuario.ACTIVO,
        hash: String = HASH_CONTRASENA,
        avatar: String? = "preset:1",
    ): UsuarioRow =
        UsuarioRow(
            idUsuario = id,
            nombreMenor = "María Camila",
            fechaNacimiento = LocalDate.of(2017, 4, 10),
            correo = "laura.perez@example.com",
            nombreUsuario = "mariacamila",
            contrasenaHash = hash,
            avatar = avatar,
            intentosLoginFallidos = 0,
            bloqueadoHasta = null,
            estado = estado,
            creadoEn = LocalDateTime.now(),
            actualizadoEn = LocalDateTime.now(),
        )

    // ── Módulo D: consultarPerfil ─────────────────────────────────────────────────────

    @Test
    fun `perfil devuelve exactamente los 5 campos de minimo privilegio`() {
        val (servicio, _) = servicioCon { it.seed(usuario(avatar = null)) }
        val perfil = servicio.consultarPerfil(1L)
        assertEquals("María Camila", perfil.nombreMenor)
        assertEquals("2017-04-10", perfil.fechaNacimiento)
        assertEquals("laura.perez@example.com", perfil.correo)
        assertEquals("mariacamila", perfil.nombreUsuario)
        assertNull(perfil.avatar, "avatar NULL se propaga como tal")
    }

    @Test
    fun `perfil con avatar no nulo lo devuelve`() {
        val (servicio, _) = servicioCon { it.seed(usuario(avatar = "preset:2")) }
        val perfil = servicio.consultarPerfil(1L)
        assertEquals("preset:2", perfil.avatar)
    }

    @Test
    fun `perfil de usuario inexistente lanza NotFoundException`() {
        val (servicio, _) = servicioCon { it.seed(usuario(id = 1L)) }
        assertFailsWith<NotFoundException> { servicio.consultarPerfil(99L) }
    }

    @Test
    fun `perfil de cuenta eliminada lanza AccountInactiveException`() {
        val (servicio, _) = servicioCon { it.seed(usuario(estado = EstadoUsuario.ELIMINADO)) }
        assertFailsWith<AccountInactiveException> { servicio.consultarPerfil(1L) }
    }

    // ── Módulo E: eliminarCuenta ─────────────────────────────────────────────────────

    @Test
    fun `eliminar cuenta con contrasena correcta aplica soft delete y responde mensaje`() {
        val (servicio, fake) = servicioCon { it.seed(usuario()) }
        val respuesta = servicio.eliminarCuenta(1L, EliminarCuentaRequestDto(CONTRASENA))
        assertEquals("Cuenta eliminada. Tus datos se conservan.", respuesta.message)
        assertEquals(EstadoUsuario.ELIMINADO, fake.findById(1L)?.estado)
    }

    @Test
    fun `eliminar cuenta con contrasena incorrecta lanza InvalidCredentials y conserva estado`() {
        val (servicio, fake) = servicioCon { it.seed(usuario()) }
        assertFailsWith<InvalidCredentialsException> {
            servicio.eliminarCuenta(1L, EliminarCuentaRequestDto("Clave-Erronea#1"))
        }
        assertEquals(EstadoUsuario.ACTIVO, fake.findById(1L)?.estado)
    }

    @Test
    fun `eliminar cuenta ya eliminada lanza AccountInactiveException`() {
        val (servicio, fake) = servicioCon { it.seed(usuario(estado = EstadoUsuario.ELIMINADO)) }
        assertFailsWith<AccountInactiveException> {
            servicio.eliminarCuenta(1L, EliminarCuentaRequestDto(CONTRASENA))
        }
        assertEquals(EstadoUsuario.ELIMINADO, fake.findById(1L)?.estado)
    }

    @Test
    fun `eliminar cuenta inexistente lanza NotFoundException`() {
        val (servicio, _) = servicioCon { it.seed(usuario(id = 1L)) }
        assertFailsWith<NotFoundException> {
            servicio.eliminarCuenta(99L, EliminarCuentaRequestDto(CONTRASENA))
        }
    }

    // ── Verificación transversal: mínimo privilegio ──────────────────────────────────

    @Test
    fun `el perfil nunca expone hash ni campos sensibles`() {
        val (servicio, _) = servicioCon { it.seed(usuario()) }
        val perfil = servicio.consultarPerfil(1L)
        assertFalse(perfil.toString().contains(HASH_CONTRASENA), "el hash no debe filtrarse")
        assertFalse(perfil.toString().contains("contrasena"), "no debe haber campos de contraseña")
        assertTrue(perfil.toString().contains("mariacamila"))
    }

    companion object {
        /** Contraseña de prueba para el hash bcrypt sembrado en el fake. */
        val CONTRASENA = "Trivia#2025"

        /** Hash bcrypt real (coste 8, solo test) para sembrar usuarios activos. */
        val HASH_CONTRASENA: String = BCrypt.withDefaults().hashToString(8, CONTRASENA.toCharArray())
    }
}
