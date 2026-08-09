package com.era.backend.services

import at.favre.lib.crypto.bcrypt.BCrypt
import com.era.backend.exceptions.EmailAlreadyVerifiedException
import com.era.backend.exceptions.NotFoundException
import com.era.backend.exceptions.OtpInvalidException
import com.era.backend.exceptions.OtpResendThrottledException
import com.era.backend.models.entities.EstadoUsuario
import com.era.backend.models.entities.RegistroPendienteRow
import com.era.backend.repositories.FakeAcudienteRepository
import com.era.backend.repositories.FakeConfiguracionRepository
import com.era.backend.repositories.FakeRegistroPendienteRepository
import com.era.backend.repositories.FakeUsuarioRepository
import com.era.backend.repositories.TransactionRunner
import com.era.backend.repositories.UsuarioRepository
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests de `VerificationService` (Módulo A.1): verificación de correo con
 * conversión transaccional `registro_pendiente` → `usuario` + `acudiente` +
 * `configuracion`, política de intentos (P1), throttle de reenvío (P2) y
 * anti-enumeración del reenvío. Usan fakes en memoria (sin MySQL) y el runner
 * transaccional fake `{ it() }`.
 */
class VerificationServiceTest {

    private class Fixture(runner: TransactionRunner = TransactionRunner { it() }) {
        val registro = FakeRegistroPendienteRepository()
        val usuario = FakeUsuarioRepository()
        val acudiente = FakeAcudienteRepository()
        val configuracion = FakeConfiguracionRepository()
        val notifier = FakeOtpNotifier()
        val otpService = OtpService(notifier)
        val service = VerificationService(registro, usuario, acudiente, configuracion, otpService, runner)

        fun pendiente(
            codigo: String = "123456",
            intentosFallidos: Int = 0,
            expiraEn: LocalDateTime = LocalDateTime.now().plusMinutes(5),
            ultimoEnvioEn: LocalDateTime? = LocalDateTime.now().minusMinutes(1),
            correo: String = "laura.perez@example.com",
        ): RegistroPendienteRow =
            RegistroPendienteRow(
                idRegistro = 1L,
                correo = correo,
                nombreUsuario = "mariacamila",
                contrasenaHash = "hash-bcrypt-contrasena",
                nombreMenor = "María Camila",
                fechaNacimiento = LocalDate.of(2017, 4, 10),
                nombreAcudiente = "Laura Pérez",
                cedulaAcudiente = "1032456789",
                avatar = null,
                codigoHash = otpService.hash(codigo),
                intentosFallidos = intentosFallidos,
                expiraEn = expiraEn,
                ultimoEnvioEn = ultimoEnvioEn,
                creadoEn = LocalDateTime.now().minusHours(1),
            )
    }

    // ── Verificación exitosa: conversión transaccional ───────────────────────────────

    @Test
    fun `codigo correcto activa la cuenta y consume el pendiente de forma atomica`() {
        val fx = Fixture()
        fx.registro.seed(fx.pendiente(codigo = "123456"))

        val respuesta = fx.service.verificarEmail("laura.perez@example.com", "123456")

        assertTrue(respuesta.message.contains("verificado"))

        // El pendiente se consumió y el usuario existe con estado ACTIVO.
        assertEquals(0, fx.registro.size())
        val usuario = fx.usuario.findByEmail("laura.perez@example.com")
        assertNotNull(usuario)
        assertEquals(EstadoUsuario.ACTIVO, usuario.estado)
        assertEquals("mariacamila", usuario.nombreUsuario)

        // Acudiente y configuración quedaron ligados al mismo usuario (1:1).
        val acudiente = fx.acudiente.todas().single()
        assertEquals(usuario.idUsuario, acudiente.idUsuario)
        assertEquals("Laura Pérez", acudiente.nombreCompleto)
        assertEquals("1032456789", acudiente.numeroCedula)
        val configuracion = fx.configuracion.todas().single()
        assertEquals(usuario.idUsuario, configuracion.idUsuario)

        // El hash bcrypt de la contraseña se preserva del registro (nunca el plano).
        assertEquals("hash-bcrypt-contrasena", usuario.contrasenaHash)

        // Verificar no envía correo (ya se envió en el alta).
        assertEquals(0, fx.notifier.envios.size)
    }

    @Test
    fun `la conversion ocurre en una unica transaccion`() {
        var invocaciones = 0
        val fx = Fixture(runner = TransactionRunner { invocaciones++; it() })
        fx.registro.seed(fx.pendiente(codigo = "123456"))

        fx.service.verificarEmail("laura.perez@example.com", "123456")

        // Lectura con lock + conversión en UNA transacción; sin transacciones extra.
        assertEquals(1, invocaciones)
    }

    // ── Fallos del código (P1) ───────────────────────────────────────────────────────

    @Test
    fun `codigo incorrecto responde generico e incrementa el contador P1`() {
        val fx = Fixture()
        fx.registro.seed(fx.pendiente(codigo = "123456", intentosFallidos = 0))

        val ex = assertFailsWith<OtpInvalidException> {
            fx.service.verificarEmail("laura.perez@example.com", "000000")
        }
        assertEquals("OTP_INVALID_OR_EXPIRED", ex.errorCode)

        // El fallo quedó persistido aunque la excepción se lanzó tras la transacción.
        val fila = fx.registro.findByEmail("laura.perez@example.com")
        assertNotNull(fila)
        assertEquals(1, fila.intentosFallidos)
        assertEquals(0, fx.usuario.size())
    }

    @Test
    fun `al tercer fallo el codigo queda permanentemente invalidado (P1)`() {
        val fx = Fixture()
        fx.registro.seed(fx.pendiente(codigo = "123456", intentosFallidos = 2))

        // 3.er fallo: el contador llega a 3.
        assertFailsWith<OtpInvalidException> {
            fx.service.verificarEmail("laura.perez@example.com", "000000")
        }
        assertEquals(3, fx.registro.findByEmail("laura.perez@example.com")?.intentosFallidos)

        // Incluso el código CORRECTO ya no sirve: el código quedó invalidado.
        assertFailsWith<OtpInvalidException> {
            fx.service.verificarEmail("laura.perez@example.com", "123456")
        }
        assertEquals(3, fx.registro.findByEmail("laura.perez@example.com")?.intentosFallidos)
        assertEquals(0, fx.usuario.size())
    }

    @Test
    fun `codigo vencido responde generico y registra el fallo`() {
        val fx = Fixture()
        fx.registro.seed(fx.pendiente(codigo = "123456", expiraEn = LocalDateTime.now().minusMinutes(1)))

        assertFailsWith<OtpInvalidException> {
            fx.service.verificarEmail("laura.perez@example.com", "123456")
        }
        assertEquals(1, fx.registro.findByEmail("laura.perez@example.com")?.intentosFallidos)
    }

    // ── Sin pendiente ────────────────────────────────────────────────────────────────

    @Test
    fun `sin pendiente pero con usuario activo responde 409 EMAIL_ALREADY_VERIFIED`() {
        val fx = Fixture()
        fx.usuario.insert(
            fx.usuario.usuarioRow(estado = EstadoUsuario.ACTIVO),
        )

        val ex = assertFailsWith<EmailAlreadyVerifiedException> {
            fx.service.verificarEmail("laura.perez@example.com", "123456")
        }
        assertEquals("EMAIL_ALREADY_VERIFIED", ex.errorCode)
    }

    @Test
    fun `sin pendiente ni usuario responde 404 NOT_FOUND`() {
        val fx = Fixture()

        assertFailsWith<NotFoundException> {
            fx.service.verificarEmail("laura.perez@example.com", "123456")
        }
    }

    // ── Reenvío de OTP (P2) ──────────────────────────────────────────────────────────

    @Test
    fun `reenvio permitido emite codigo nuevo, reinicia P1 y envia el correo`() {
        val fx = Fixture()
        val pendiente = fx.pendiente(codigo = "123456", intentosFallidos = 2, ultimoEnvioEn = LocalDateTime.now().minusMinutes(2))
        fx.registro.seed(pendiente)
        val hashAnterior = pendiente.codigoHash

        val respuesta = fx.service.reenviarOtp("laura.perez@example.com")

        assertTrue(respuesta.message.contains("enviado"))
        assertEquals(1, fx.notifier.envios.size)
        assertEquals("laura.perez@example.com", fx.notifier.envios.single().first)

        // Código nuevo (hash distinto), vigencia reiniciada a ~10 min, P1 a cero.
        val fila = fx.registro.findByEmail("laura.perez@example.com")
        assertNotNull(fila)
        assertTrue(fila.codigoHash != hashAnterior)
        assertTrue(fila.expiraEn.isAfter(LocalDateTime.now().plusMinutes(9)))
        assertEquals(0, fila.intentosFallidos)
        assertNotNull(fila.ultimoEnvioEn)
        assertTrue(Duration.between(fila.ultimoEnvioEn, LocalDateTime.now()).abs().seconds <= 2)

        // El código que se envió es exactamente el que quedó hasheado.
        val enviado = fx.notifier.ultimoCodigo()
        assertNotNull(enviado)
        assertTrue(BCrypt.verifyer().verify(enviado.toCharArray(), fila.codigoHash).verified)
    }

    @Test
    fun `reenvio antes de 60 s responde 429 OTP_RESEND_THROTTLED y no toca nada`() {
        val fx = Fixture()
        fx.registro.seed(fx.pendiente(codigo = "123456", ultimoEnvioEn = LocalDateTime.now().minusSeconds(30)))

        val ex = assertFailsWith<OtpResendThrottledException> {
            fx.service.reenviarOtp("laura.perez@example.com")
        }
        assertEquals("OTP_RESEND_THROTTLED", ex.errorCode)
        assertEquals(0, fx.notifier.envios.size)
        assertEquals(1, fx.registro.size())
    }

    @Test
    fun `reenvio sin ultimo_envio registrado se permite (filas previas a V2)`() {
        val fx = Fixture()
        fx.registro.seed(fx.pendiente(codigo = "123456", ultimoEnvioEn = null))

        fx.service.reenviarOtp("laura.perez@example.com")

        assertEquals(1, fx.notifier.envios.size)
        assertNotNull(fx.registro.findByEmail("laura.perez@example.com")?.ultimoEnvioEn)
    }

    @Test
    fun `reenvio sin pendiente responde 200 generico sin enviar nada (anti-enumeracion)`() {
        val fx = Fixture()

        val respuesta = fx.service.reenviarOtp("laura.perez@example.com")

        assertTrue(respuesta.message.contains("enviado"))
        assertEquals(0, fx.notifier.envios.size)
        assertEquals(0, fx.registro.size())
    }

    @Test
    fun `el envio del reenvio ocurre fuera de la transaccion`() {
        var invocaciones = 0
        val fx = Fixture(runner = TransactionRunner { invocaciones++; it() })
        fx.registro.seed(fx.pendiente(codigo = "123456", ultimoEnvioEn = LocalDateTime.now().minusMinutes(2)))

        fx.service.reenviarOtp("laura.perez@example.com")

        // Una sola transacción (persistir el nuevo código); el SMTP va después.
        assertEquals(1, invocaciones)
        assertEquals(1, fx.notifier.envios.size)
    }

    private fun UsuarioRepository.usuarioRow(
        estado: EstadoUsuario = EstadoUsuario.ACTIVO,
        correo: String = "laura.perez@example.com",
    ) =
        com.era.backend.models.entities.UsuarioRow(
            idUsuario = 0L,
            nombreMenor = "María Camila",
            fechaNacimiento = LocalDate.of(2017, 4, 10),
            correo = correo,
            nombreUsuario = "mariacamila",
            contrasenaHash = "hash-bcrypt-contrasena",
            avatar = null,
            intentosLoginFallidos = 0,
            bloqueadoHasta = null,
            estado = estado,
            creadoEn = LocalDateTime.now(),
            actualizadoEn = LocalDateTime.now(),
        )
}
