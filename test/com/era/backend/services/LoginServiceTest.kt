package com.era.backend.services

import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import com.era.backend.config.JwtConfig
import com.era.backend.exceptions.AccountInactiveException
import com.era.backend.exceptions.AccountLockedException
import com.era.backend.exceptions.InvalidCredentialsException
import com.era.backend.models.dto.LoginRequestDto
import com.era.backend.models.entities.EstadoUsuario
import com.era.backend.models.entities.UsuarioRow
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
 * Unit tests de [LoginService] (Módulo B, `modulo-b-analisis.md` §5). Sin MySQL: se usa
 * `FakeUsuarioRepository` (espejo en memoria de `findByEmailForUpdate`,
 * `findByUsernameForUpdate` y `actualizarEstadoLogin`) y el `JwtTokenService` real con
 * secreto de test, para assertar también las claims del token emitido.
 */
class LoginServiceTest {

    private fun servicioCon(
        seed: (FakeUsuarioRepository) -> Unit,
    ): Pair<LoginService, FakeUsuarioRepository> {
        val fake = FakeUsuarioRepository()
        seed(fake)
        val servicio = LoginService(fake, TransactionRunner { it() }, JwtTokenService(JWT_CONFIG_TEST))
        return servicio to fake
    }

    private fun login(servicio: LoginService, identificador: String, contrasena: String) =
        servicio.login(LoginRequestDto(usuarioOCorreo = identificador, contrasena = contrasena))

    private fun usuario(
        id: Long = 1L,
        correo: String = "laura.perez@example.com",
        username: String = "mariacamila",
        hash: String = HASH_CONTRASENA,
        intentos: Int = 0,
        bloqueadoHasta: LocalDateTime? = null,
        estado: EstadoUsuario = EstadoUsuario.ACTIVO,
    ): UsuarioRow =
        UsuarioRow(
            idUsuario = id,
            nombreMenor = "María Camila",
            fechaNacimiento = LocalDate.of(2017, 4, 10),
            correo = correo,
            nombreUsuario = username,
            contrasenaHash = hash,
            avatar = null,
            intentosLoginFallidos = intentos,
            bloqueadoHasta = bloqueadoHasta,
            estado = estado,
            creadoEn = LocalDateTime.now(),
            actualizadoEn = LocalDateTime.now(),
        )

    /** Verifica firma (HMAC256 con el secreto de test), issuer, audience, exp a 30 días y jti. */
    private fun verificarToken(token: String, idUsuarioEsperado: Long): DecodedJWT {
        val decoded =
            JWT.require(Algorithm.HMAC256(JWT_CONFIG_TEST.secret))
                .withIssuer("era-backend")
                .withAudience("era-app-session")
                .build()
                .verify(token)
        assertEquals(idUsuarioEsperado.toString(), decoded.subject)
        assertEquals(30 * 24 * 60L, (decoded.expiresAt.time - decoded.issuedAt.time) / 60_000L)
        assertNotNull(decoded.id, "el token debe llevar un jti único por emisión")
        return decoded
    }

    // ── Flujo feliz ───────────────────────────────────────────────────────────────────

    @Test
    fun `login por correo emite token firmado con claims de sesion`() {
        val (servicio, _) = servicioCon { it.seed(usuario()) }
        val respuesta = login(servicio, "laura.perez@example.com", CONTRASENA)
        assertTrue(respuesta.token.isNotBlank())
        verificarToken(respuesta.token, idUsuarioEsperado = 1L)
    }

    @Test
    fun `login por username emite token`() {
        val (servicio, _) = servicioCon { it.seed(usuario()) }
        val respuesta = login(servicio, "mariacamila", CONTRASENA)
        verificarToken(respuesta.token, idUsuarioEsperado = 1L)
    }

    @Test
    fun `username es case-insensitive (B-6)`() {
        val (servicio, _) = servicioCon { it.seed(usuario()) }
        val respuesta = login(servicio, "Mariacamila", CONTRASENA)
        verificarToken(respuesta.token, idUsuarioEsperado = 1L)
    }

    @Test
    fun `login exitoso resetea el contador y la ventana`() {
        val (servicio, fake) = servicioCon {
            it.seed(usuario(intentos = 4, bloqueadoHasta = LocalDateTime.now().minusMinutes(5)))
        }
        verificarToken(login(servicio, "mariacamila", CONTRASENA).token, 1L)
        val estado = fake.findById(1L)!!
        assertEquals(0, estado.intentosLoginFallidos)
        assertEquals(null, estado.bloqueadoHasta)
    }

    // ── Intentos fallidos y bloqueo (B-2 / B-3) ───────────────────────────────────────

    @Test
    fun `contrasena incorrecta incrementa el contador y responde 401 generico`() {
        val (servicio, fake) = servicioCon { it.seed(usuario(intentos = 2)) }
        val ex = assertFailsWith<InvalidCredentialsException> { login(servicio, "mariacamila", "Clave-Erronea#1") }
        assertEquals("Credenciales incorrectas.", ex.message)
        val estado = fake.findById(1L)!!
        assertEquals(3, estado.intentosLoginFallidos)
        assertEquals(null, estado.bloqueadoHasta)
    }

    @Test
    fun `el quinto fallo abre la ventana de 2 min y responde bloqueado`() {
        val (servicio, fake) = servicioCon { it.seed(usuario(intentos = 4)) }
        assertFailsWith<AccountLockedException> { login(servicio, "mariacamila", "Clave-Erronea#1") }
        val estado = fake.findById(1L)!!
        assertEquals(0, estado.intentosLoginFallidos, "B-3: al abrir la ventana el contador vuelve a 0")
        val ventana = assertNotNull(estado.bloqueadoHasta)
        assertTrue(ventana.isAfter(LocalDateTime.now().plusMinutes(1)), "ventana debe ser ~now + 2 min")
        assertTrue(ventana.isBefore(LocalDateTime.now().plusMinutes(3)))
    }

    @Test
    fun `cuenta dentro de ventana activa queda bloqueada sin verificar ni incrementar`() {
        val ventanaActiva = LocalDateTime.now().plusMinutes(1)
        val (servicio, fake) = servicioCon {
            it.seed(usuario(intentos = 0, bloqueadoHasta = ventanaActiva))
        }
        assertFailsWith<AccountLockedException> { login(servicio, "mariacamila", CONTRASENA) }
        val estado = fake.findById(1L)!!
        assertEquals(0, estado.intentosLoginFallidos, "no se verifica ni se incrementa dentro de la ventana")
        assertEquals(ventanaActiva, estado.bloqueadoHasta)
    }

    @Test
    fun `ventana expirada se limpia de forma lazy y permite el login correcto`() {
        val (servicio, fake) = servicioCon {
            it.seed(usuario(intentos = 4, bloqueadoHasta = LocalDateTime.now().minusMinutes(1)))
        }
        verificarToken(login(servicio, "mariacamila", CONTRASENA).token, 1L)
        val estado = fake.findById(1L)!!
        assertEquals(0, estado.intentosLoginFallidos)
        assertEquals(null, estado.bloqueadoHasta)
    }

    // ── Soft delete (B-5) ─────────────────────────────────────────────────────────────

    @Test
    fun `cuenta eliminada con contrasena correcta responde ACCOUNT_INACTIVE`() {
        val (servicio, fake) = servicioCon { it.seed(usuario(intentos = 2, estado = EstadoUsuario.ELIMINADO)) }
        assertFailsWith<AccountInactiveException> { login(servicio, "mariacamila", CONTRASENA) }
        assertEquals(0, fake.findById(1L)!!.intentosLoginFallidos, "el reset tras éxito se aplica antes de evaluar el estado")
    }

    @Test
    fun `cuenta eliminada con contrasena incorrecta responde el mismo 401 generico`() {
        val (servicio, fake) = servicioCon { it.seed(usuario(estado = EstadoUsuario.ELIMINADO)) }
        val ex = assertFailsWith<InvalidCredentialsException> { login(servicio, "mariacamila", "Clave-Erronea#1") }
        assertEquals("Credenciales incorrectas.", ex.message)
        assertEquals(1, fake.findById(1L)!!.intentosLoginFallidos)
    }

    // ── Identificador inexistente (B-1 / B-4) ─────────────────────────────────────────

    @Test
    fun `identificador inexistente responde 401 generico sin emitir token`() {
        val (servicio, _) = servicioCon { }
        val ex = assertFailsWith<InvalidCredentialsException> { login(servicio, "no.existe@example.com", CONTRASENA) }
        assertEquals("Credenciales incorrectas.", ex.message, "no confirma ni niega la existencia de la cuenta")
    }

    @Test
    fun `el error generico es identico entre credenciales incorrectas e identificador inexistente`() {
        val (servicio, _) = servicioCon { it.seed(usuario()) }
        val exCredenciales =
            assertFailsWith<InvalidCredentialsException> { login(servicio, "mariacamila", "Clave-Erronea#1") }
        val exInexistente =
            assertFailsWith<InvalidCredentialsException> { login(servicio, "no.existe@example.com", CONTRASENA) }
        assertEquals(exInexistente.message, exCredenciales.message)
    }

    companion object {
        /** Config JWT de test: secreto dummy, solo para firmar tokens en los tests. */
        val JWT_CONFIG_TEST =
            JwtConfig(
                secret = "test-secret",
                sessionIssuer = "era-backend",
                sessionAudience = "era-app-session",
                sessionExpirationMinutes = 43200,
                resetIssuer = "era-backend",
                resetAudience = "era-app-reset",
                resetTtlMinutes = 10,
                resetPurpose = "PASSWORD_RESET",
            )

        /** Contraseña de prueba para los hashes sembrados en el fake. */
        val CONTRASENA = "Trivia#2025"

        /**
         * Hash bcrypt real generado una sola vez por suite (coste 8, solo test). Permite
         * probar el `BCrypt.verify` real del service sin pagar coste 12 por test.
         */
        val HASH_CONTRASENA: String = BCrypt.withDefaults().hashToString(8, CONTRASENA.toCharArray())
    }
}
