package com.era.backend.services

import at.favre.lib.crypto.bcrypt.BCrypt
import com.era.backend.exceptions.AccountInactiveException
import com.era.backend.exceptions.ConflictException
import com.era.backend.exceptions.InvalidCredentialsException
import com.era.backend.exceptions.NotFoundException
import com.era.backend.models.dto.ActualizarUsuarioRequestDto
import com.era.backend.models.dto.EliminarCuentaRequestDto
import com.era.backend.models.entities.EstadoUsuario
import com.era.backend.models.entities.RegistroPendienteRow
import com.era.backend.models.entities.UsuarioRow
import com.era.backend.repositories.FakeRegistroPendienteRepository
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
 * `actualizarEstado`) y `FakeRegistroPendienteRepository` (unicidad del PATCH).
 */
class UsuarioServiceTest {

    private fun servicioCon(
        seed: (FakeUsuarioRepository, FakeRegistroPendienteRepository) -> Unit,
    ): Triple<UsuarioService, FakeUsuarioRepository, FakeRegistroPendienteRepository> {
        val fake = FakeUsuarioRepository()
        val pendientes = FakeRegistroPendienteRepository()
        seed(fake, pendientes)
        val servicio = UsuarioService(fake, pendientes, TransactionRunner { it() })
        return Triple(servicio, fake, pendientes)
    }

    private fun usuario(
        id: Long = 1L,
        estado: EstadoUsuario = EstadoUsuario.ACTIVO,
        hash: String = HASH_CONTRASENA,
        avatar: String? = "preset:1",
        nombreUsuario: String = "mariacamila",
    ): UsuarioRow =
        UsuarioRow(
            idUsuario = id,
            nombreMenor = "María Camila",
            fechaNacimiento = LocalDate.of(2017, 4, 10),
            correo = "laura.perez@example.com",
            nombreUsuario = nombreUsuario,
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
        val (servicio, _, _) = servicioCon { fake, _ -> fake.seed(usuario(avatar = null)) }
        val perfil = servicio.consultarPerfil(1L)
        assertEquals("María Camila", perfil.nombreMenor)
        assertEquals("2017-04-10", perfil.fechaNacimiento)
        assertEquals("laura.perez@example.com", perfil.correo)
        assertEquals("mariacamila", perfil.nombreUsuario)
        assertNull(perfil.avatar, "avatar NULL se propaga como tal")
    }

    @Test
    fun `perfil con avatar no nulo lo devuelve`() {
        val (servicio, _, _) = servicioCon { fake, _ -> fake.seed(usuario(avatar = "preset:2")) }
        val perfil = servicio.consultarPerfil(1L)
        assertEquals("preset:2", perfil.avatar)
    }

    @Test
    fun `perfil de usuario inexistente lanza NotFoundException`() {
        val (servicio, _, _) = servicioCon { fake, _ -> fake.seed(usuario(id = 1L)) }
        assertFailsWith<NotFoundException> { servicio.consultarPerfil(99L) }
    }

    @Test
    fun `perfil de cuenta eliminada lanza AccountInactiveException`() {
        val (servicio, _, _) =
            servicioCon { fake, _ -> fake.seed(usuario(estado = EstadoUsuario.ELIMINADO)) }
        assertFailsWith<AccountInactiveException> { servicio.consultarPerfil(1L) }
    }

    // ── Módulo D: actualizarNombreUsuario (PATCH /users/me, REQ-FUN-06 CA5) ───────────

    @Test
    fun `actualizar username valido cambia la fila y devuelve el perfil actualizado`() {
        val (servicio, fake, _) = servicioCon { usuarios, _ -> usuarios.seed(usuario()) }
        val perfil = servicio.actualizarNombreUsuario(1L, ActualizarUsuarioRequestDto("nuevoNick"))
        assertEquals("nuevoNick", perfil.nombreUsuario)
        assertEquals("nuevoNick", fake.findById(1L)?.nombreUsuario)
        assertEquals("María Camila", perfil.nombreMenor)
        assertEquals("2017-04-10", perfil.fechaNacimiento)
        assertEquals("laura.perez@example.com", perfil.correo)
        assertEquals("preset:1", perfil.avatar)
    }

    @Test
    fun `actualizar al mismo username propio no lanza conflicto`() {
        val (servicio, fake, _) = servicioCon { usuarios, _ -> usuarios.seed(usuario()) }
        val perfil = servicio.actualizarNombreUsuario(1L, ActualizarUsuarioRequestDto("mariacamila"))
        assertEquals("mariacamila", perfil.nombreUsuario)
        assertEquals("mariacamila", fake.findById(1L)?.nombreUsuario)
    }

    @Test
    fun `actualizar a un username ocupado por otra cuenta activa lanza ConflictException`() {
        val (servicio, fake, _) =
            servicioCon { usuarios, _ ->
                usuarios.seed(usuario(id = 1L, nombreUsuario = "mariacamila"))
                usuarios.seed(usuario(id = 2L, nombreUsuario = "pedrito").copy(correo = "pedro@example.com"))
            }
        assertFailsWith<ConflictException> {
            servicio.actualizarNombreUsuario(1L, ActualizarUsuarioRequestDto("pedrito"))
        }
        assertEquals("mariacamila", fake.findById(1L)?.nombreUsuario)
    }

    @Test
    fun `actualizar a un username ocupado por cuenta en soft delete lanza ConflictException`() {
        val (servicio, _, _) =
            servicioCon { usuarios, _ ->
                usuarios.seed(usuario(id = 1L, nombreUsuario = "mariacamila"))
                usuarios.seed(
                    usuario(id = 2L, nombreUsuario = "eliminado", estado = EstadoUsuario.ELIMINADO)
                        .copy(correo = "baja@example.com"),
                )
            }
        assertFailsWith<ConflictException> {
            servicio.actualizarNombreUsuario(1L, ActualizarUsuarioRequestDto("eliminado"))
        }
    }

    @Test
    fun `actualizar a un username con distinta capitalizacion lanza conflicto case-insensitive`() {
        val (servicio, _, _) =
            servicioCon { usuarios, _ ->
                usuarios.seed(usuario(id = 1L, nombreUsuario = "mariacamila"))
                usuarios.seed(usuario(id = 2L, nombreUsuario = "otroUser").copy(correo = "otro@example.com"))
            }
        assertFailsWith<ConflictException> {
            servicio.actualizarNombreUsuario(1L, ActualizarUsuarioRequestDto("OTROUSER"))
        }
    }

    @Test
    fun `actualizar a un username reservado en registro pendiente lanza ConflictException`() {
        val (servicio, fake, _) =
            servicioCon { usuarios, pendientes ->
                usuarios.seed(usuario())
                pendientes.seed(pendiente(nombreUsuario = "reservado"))
            }
        assertFailsWith<ConflictException> {
            servicio.actualizarNombreUsuario(1L, ActualizarUsuarioRequestDto("reservado"))
        }
        assertEquals("mariacamila", fake.findById(1L)?.nombreUsuario)
    }

    @Test
    fun `actualizar de cuenta eliminada lanza AccountInactiveException y no cambia la fila`() {
        val (servicio, fake, _) =
            servicioCon { usuarios, _ -> usuarios.seed(usuario(estado = EstadoUsuario.ELIMINADO)) }
        assertFailsWith<AccountInactiveException> {
            servicio.actualizarNombreUsuario(1L, ActualizarUsuarioRequestDto("nuevoNick"))
        }
        assertEquals("mariacamila", fake.findById(1L)?.nombreUsuario)
    }

    @Test
    fun `actualizar de usuario inexistente lanza NotFoundException`() {
        val (servicio, _, _) = servicioCon { fake, _ -> fake.seed(usuario(id = 1L)) }
        assertFailsWith<NotFoundException> {
            servicio.actualizarNombreUsuario(99L, ActualizarUsuarioRequestDto("nuevoNick"))
        }
    }

    @Test
    fun `el perfil actualizado nunca expone hash ni campos sensibles`() {
        val (servicio, _, _) = servicioCon { fake, _ -> fake.seed(usuario()) }
        val perfil = servicio.actualizarNombreUsuario(1L, ActualizarUsuarioRequestDto("nuevoNick"))
        assertFalse(perfil.toString().contains(HASH_CONTRASENA), "el hash no debe filtrarse")
        assertFalse(perfil.toString().contains("contrasena"), "no debe haber campos de contraseña")
        assertTrue(perfil.toString().contains("nuevoNick"))
    }

    // ── Módulo E: eliminarCuenta ─────────────────────────────────────────────────────

    @Test
    fun `eliminar cuenta con contrasena correcta aplica soft delete y responde mensaje`() {
        val (servicio, fake, _) = servicioCon { usuarios, _ -> usuarios.seed(usuario()) }
        val respuesta = servicio.eliminarCuenta(1L, EliminarCuentaRequestDto(CONTRASENA))
        assertEquals("Cuenta eliminada. Tus datos se conservan.", respuesta.message)
        assertEquals(EstadoUsuario.ELIMINADO, fake.findById(1L)?.estado)
    }

    @Test
    fun `eliminar cuenta con contrasena incorrecta lanza InvalidCredentials y conserva estado`() {
        val (servicio, fake, _) = servicioCon { usuarios, _ -> usuarios.seed(usuario()) }
        assertFailsWith<InvalidCredentialsException> {
            servicio.eliminarCuenta(1L, EliminarCuentaRequestDto("Clave-Erronea#1"))
        }
        assertEquals(EstadoUsuario.ACTIVO, fake.findById(1L)?.estado)
    }

    @Test
    fun `eliminar cuenta ya eliminada lanza AccountInactiveException`() {
        val (servicio, fake, _) =
            servicioCon { usuarios, _ -> usuarios.seed(usuario(estado = EstadoUsuario.ELIMINADO)) }
        assertFailsWith<AccountInactiveException> {
            servicio.eliminarCuenta(1L, EliminarCuentaRequestDto(CONTRASENA))
        }
        assertEquals(EstadoUsuario.ELIMINADO, fake.findById(1L)?.estado)
    }

    @Test
    fun `eliminar cuenta inexistente lanza NotFoundException`() {
        val (servicio, _, _) = servicioCon { fake, _ -> fake.seed(usuario(id = 1L)) }
        assertFailsWith<NotFoundException> {
            servicio.eliminarCuenta(99L, EliminarCuentaRequestDto(CONTRASENA))
        }
    }

    // ── Verificación transversal: mínimo privilegio ──────────────────────────────────

    @Test
    fun `el perfil nunca expone hash ni campos sensibles`() {
        val (servicio, _, _) = servicioCon { fake, _ -> fake.seed(usuario()) }
        val perfil = servicio.consultarPerfil(1L)
        assertFalse(perfil.toString().contains(HASH_CONTRASENA), "el hash no debe filtrarse")
        assertFalse(perfil.toString().contains("contrasena"), "no debe haber campos de contraseña")
        assertTrue(perfil.toString().contains("mariacamila"))
    }

    /** Fila pendiente de prueba para sembrar reservas de username (unicidad del PATCH). */
    private fun pendiente(nombreUsuario: String): RegistroPendienteRow =
        RegistroPendienteRow(
            idRegistro = 1L,
            correo = "pendiente@example.com",
            nombreUsuario = nombreUsuario,
            contrasenaHash = HASH_CONTRASENA,
            nombreMenor = "Menor",
            fechaNacimiento = LocalDate.of(2016, 5, 10),
            nombreAcudiente = "Acudiente",
            cedulaAcudiente = "ABC123456",
            avatar = null,
            codigoHash = "hash",
            intentosFallidos = 0,
            expiraEn = LocalDateTime.now().plusMinutes(10),
            ultimoEnvioEn = LocalDateTime.now(),
            creadoEn = LocalDateTime.now(),
        )

    companion object {
        /** Contraseña de prueba para el hash bcrypt sembrado en el fake. */
        val CONTRASENA = "Trivia#2025"

        /** Hash bcrypt real (coste 8, solo test) para sembrar usuarios activos. */
        val HASH_CONTRASENA: String = BCrypt.withDefaults().hashToString(8, CONTRASENA.toCharArray())
    }
}
