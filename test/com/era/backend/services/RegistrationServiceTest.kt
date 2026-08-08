package com.era.backend.services

import at.favre.lib.crypto.bcrypt.BCrypt
import com.era.backend.exceptions.ConflictException
import com.era.backend.exceptions.EmailAlreadyRegisteredException
import com.era.backend.exceptions.EmailLockedException
import com.era.backend.exceptions.ValidationException
import com.era.backend.models.dto.RegisterRequestDto
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests de `RegistrationService.register` (REQ-FUN-01, CU-01, HU-01, HU-15):
 * unicidad de correo/usuario (V1), limpieza lazy de expirados (V2), política de
 * contraseña (CA2/V3), hash bcrypt, vigencia del OTP y envío fuera de la transacción.
 * Usan fakes en memoria (sin MySQL) y el runner transaccional fake `{ it() }`.
 */
class RegistrationServiceTest {

    private val fakeRegistro = FakeRegistroPendienteRepository()
    private val fakeUsuario = FakeUsuarioRepository()
    private val fakeNotifier = FakeOtpNotifier()
    private val otpService = OtpService(fakeNotifier)

    private fun nuevoService(
        registro: FakeRegistroPendienteRepository = fakeRegistro,
        usuario: FakeUsuarioRepository = fakeUsuario,
        runner: TransactionRunner = TransactionRunner { it() },
    ) = RegistrationService(registro, usuario, otpService, runner)

    private val service = nuevoService()

    private fun requestValido() =
        RegisterRequestDto(
            nombreMenor = "María Camila",
            fechaNacimiento = "2017-04-10",
            nombreAcudiente = "Laura Pérez",
            cedulaAcudiente = "1032456789",
            correo = "laura.perez@example.com",
            nombreUsuario = "mariacamila",
            avatar = "preset:1",
            contrasena = "Trivia#2025",
            confirmarContrasena = "Trivia#2025",
        )

    private fun usuario(
        estado: EstadoUsuario,
        correo: String = "laura.perez@example.com",
        username: String = "mariacamila",
    ) =
        UsuarioRow(
            idUsuario = 1L,
            nombreMenor = "María Camila",
            fechaNacimiento = LocalDate.of(2017, 4, 10),
            correo = correo,
            nombreUsuario = username,
            contrasenaHash = "hash-irrelevante",
            avatar = null,
            intentosLoginFallidos = 0,
            bloqueadoHasta = null,
            estado = estado,
            creadoEn = LocalDateTime.now(),
            actualizadoEn = LocalDateTime.now(),
        )

    private fun pendiente(
        correo: String = "laura.perez@example.com",
        username: String = "mariacamila",
        expiraEn: LocalDateTime,
    ) =
        RegistroPendienteRow(
            idRegistro = 0L,
            correo = correo,
            nombreUsuario = username,
            contrasenaHash = "hash-irrelevante",
            nombreMenor = "María Camila",
            fechaNacimiento = LocalDate.of(2017, 4, 10),
            nombreAcudiente = "Laura Pérez",
            cedulaAcudiente = "1032456789",
            avatar = null,
            codigoHash = "hash-irrelevante",
            intentosFallidos = 0,
            expiraEn = expiraEn,
            creadoEn = LocalDateTime.now(),
        )

    // ── Flujo feliz ─────────────────────────────────────────────────────────────────

    @Test
    fun `flujo feliz persiste pendiente hasheado y envia el OTP al correo`() {
        val respuesta = service.register(requestValido())

        assertTrue(respuesta.message.contains("verificación"))

        val fila = fakeRegistro.findByEmail("laura.perez@example.com")
        assertNotNull(fila)

        // Hash bcrypt de la contraseña: se verifica el texto plano, nunca se guarda el plano.
        assertTrue(BCrypt.verifyer().verify("Trivia#2025".toCharArray(), fila.contrasenaHash).verified)

        // El código que capturó el notifier es exactamente el que quedó hasheado en BD.
        val enviado = fakeNotifier.ultimoCodigo()
        assertNotNull(enviado)
        assertEquals(1, fakeNotifier.envios.size)
        assertEquals("laura.perez@example.com", fakeNotifier.envios.single().first)
        assertTrue(BCrypt.verifyer().verify(enviado.toCharArray(), fila.codigoHash).verified)

        // Vigencia de 10 min e intentos en cero (REQ-FUN-01 CA4, P1).
        assertTrue(fila.expiraEn.isAfter(LocalDateTime.now()))
        assertTrue(fila.expiraEn.isBefore(LocalDateTime.now().plusMinutes(11)))
        assertEquals(0, fila.intentosFallidos)
        assertEquals("preset:1", fila.avatar)
    }

    @Test
    fun `el bloque transaccional se ejecuta una sola vez y el envio queda fuera`() {
        var invocaciones = 0
        val serviceConRunner =
            nuevoService(runner = TransactionRunner {
                invocaciones++
                it()
            })
        serviceConRunner.register(requestValido())

        // Una única transacción para limpieza lazy + unicidad + insert; el envío SMTP va después.
        assertEquals(1, invocaciones)
        assertEquals(1, fakeNotifier.envios.size)
    }

    @Test
    fun `contrasena invalida no abre ninguna transaccion`() {
        var invocaciones = 0
        val serviceConRunner =
            nuevoService(runner = TransactionRunner {
                invocaciones++
                it()
            })
        assertFailsWith<ValidationException> {
            serviceConRunner.register(requestValido().copy(contrasena = "abc", confirmarContrasena = "abc"))
        }
        assertEquals(0, invocaciones)
        assertEquals(0, fakeRegistro.size())
    }

    // ── Unicidad de correo (REQ-FUN-01 CA1) ─────────────────────────────────────────

    @Test
    fun `correo con cuenta activa lanza EMAIL_ALREADY_REGISTERED y no inserta`() {
        fakeUsuario.seed(usuario(estado = EstadoUsuario.ACTIVO))
        val ex = assertFailsWith<EmailAlreadyRegisteredException> { service.register(requestValido()) }
        assertEquals("EMAIL_ALREADY_REGISTERED", ex.errorCode)
        assertEquals(0, fakeRegistro.size())
        assertTrue(fakeNotifier.envios.isEmpty())
    }

    @Test
    fun `correo de cuenta eliminada lanza EMAIL_LOCKED y su correo no se reusa (V1)`() {
        fakeUsuario.seed(usuario(estado = EstadoUsuario.ELIMINADO))
        val ex = assertFailsWith<EmailLockedException> { service.register(requestValido()) }
        assertEquals("EMAIL_LOCKED", ex.errorCode)
        assertEquals(0, fakeRegistro.size())
        assertTrue(fakeNotifier.envios.isEmpty())
    }

    @Test
    fun `correo con registro pendiente no expirado lanza EMAIL_ALREADY_REGISTERED`() {
        fakeRegistro.seed(pendiente(expiraEn = LocalDateTime.now().plusMinutes(5)))
        assertFailsWith<EmailAlreadyRegisteredException> { service.register(requestValido()) }
    }

    @Test
    fun `pendiente expirado se limpia y permite registrar el mismo correo (V2)`() {
        fakeRegistro.seed(pendiente(expiraEn = LocalDateTime.now().minusMinutes(1)))
        service.register(requestValido())

        assertEquals(1, fakeRegistro.size()) // el expirado fue reemplazado por el nuevo
        val fila = fakeRegistro.findByEmail("laura.perez@example.com")
        assertNotNull(fila)
        assertTrue(fila.expiraEn.isAfter(LocalDateTime.now()))
    }

    // ── Unicidad de username (V1) ───────────────────────────────────────────────────

    @Test
    fun `username de cuenta eliminada permanece ocupado (V1)`() {
        fakeUsuario.seed(usuario(estado = EstadoUsuario.ELIMINADO, correo = "otro@example.com"))
        val ex = assertFailsWith<ConflictException> { service.register(requestValido()) }
        assertEquals("CONFLICT", ex.errorCode)
        assertEquals(0, fakeRegistro.size())
    }

    @Test
    fun `username ocupado por pendiente no expirado lanza conflict`() {
        fakeRegistro.seed(pendiente(correo = "otro@example.com", expiraEn = LocalDateTime.now().plusMinutes(5)))
        assertFailsWith<ConflictException> { service.register(requestValido()) }
    }

    @Test
    fun `username liberado por pendiente expirado permite registrar (V2)`() {
        fakeRegistro.seed(pendiente(correo = "otro@example.com", expiraEn = LocalDateTime.now().minusMinutes(1)))
        service.register(requestValido())
        assertEquals(1, fakeRegistro.size())
    }

    // ── Política de contraseña (REQ-FUN-01 CA2, V3) ─────────────────────────────────

    @Test
    fun `contrasena de 73 caracteres es rechazada por el tope de 72 de bcrypt`() {
        val contrasena = "A" + "x".repeat(70) + "1#" // 73 caracteres, cumple las demás reglas
        val ex =
            assertFailsWith<ValidationException> {
                service.register(requestValido().copy(contrasena = contrasena, confirmarContrasena = contrasena))
            }
        assertEquals(listOf("contrasena"), ex.details.map { it.field })
        assertTrue(ex.details.any { it.message == "Máximo 72 caracteres." })
        // Nunca se llegó a tocar la BD.
        assertEquals(0, fakeRegistro.size())
        assertTrue(fakeNotifier.envios.isEmpty())
    }

    @Test
    fun `contrasena corta agrupa todas las reglas incumplidas`() {
        val ex =
            assertFailsWith<ValidationException> {
                service.register(requestValido().copy(contrasena = "abc", confirmarContrasena = "abc"))
            }
        val mensajes = ex.details.map { it.message }
        assertTrue("Debe tener al menos 8 caracteres." in mensajes)
        assertTrue("Debe incluir al menos una mayúscula." in mensajes)
        assertTrue("Debe incluir al menos un número." in mensajes)
        assertTrue("Debe incluir al menos un símbolo." in mensajes)
    }

    @Test
    fun `contrasena no puede ser igual al username`() {
        val contrasena = "mariacamila"
        val ex =
            assertFailsWith<ValidationException> {
                service.register(requestValido().copy(contrasena = contrasena, confirmarContrasena = contrasena))
            }
        assertTrue(ex.details.any { it.message == "No puede ser igual al nombre de usuario." })
    }

    @Test
    fun `contrasena no puede contener datos personales del menor (V3)`() {
        val contrasena = "María2025#x"
        val ex =
            assertFailsWith<ValidationException> {
                service.register(requestValido().copy(contrasena = contrasena, confirmarContrasena = contrasena))
            }
        // Cumple longitud, mayúscula, minúscula, número y símbolo: el único error es V3.
        assertEquals(listOf("No puede contener datos personales."), ex.details.map { it.message })
    }
}
