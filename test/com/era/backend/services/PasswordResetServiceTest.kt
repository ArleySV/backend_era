package com.era.backend.services

import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import com.era.backend.config.JwtConfig
import com.era.backend.exceptions.OtpInvalidException
import com.era.backend.exceptions.OtpResendThrottledException
import com.era.backend.exceptions.PasswordReuseException
import com.era.backend.exceptions.ResetTokenInvalidException
import com.era.backend.exceptions.ValidationException
import com.era.backend.models.dto.PasswordResetConfirmRequestDto
import com.era.backend.models.dto.PasswordResetRequestDto
import com.era.backend.models.dto.PasswordResetVerifyRequestDto
import com.era.backend.models.entities.CodigoVerificacionRow
import com.era.backend.models.entities.EstadoUsuario
import com.era.backend.models.entities.TokensReseteoRow
import com.era.backend.models.entities.UsuarioRow
import com.era.backend.repositories.FakeCodigoVerificacionRepository
import com.era.backend.repositories.FakeTokensReseteoRepository
import com.era.backend.repositories.FakeUsuarioRepository
import com.era.backend.repositories.TransactionRunner
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests de [PasswordResetService] (Módulo C, `modulo-c-analisis.md` §6). Sin MySQL:
 * el service se construye con fakes en memoria y el `JwtTokenService` real con secreto de
 * test (para assertar también las claims del token puente). El `OtpService` se crea en
 * modo determinista (`123456`, espejo de dev V10) para que los flujos no dependan de leer
 * el correo.
 *
 * Cobertura de los riesgos críticos del Módulo C: anti-enumeración en /request y /verify
 * (C-1), throttle 60 s (C-2), single-use del OTP y del token puente con doble vínculo
 * jti+sub (C-3), veto a repetir la contraseña anterior (C-4) y política compartida (C-6).
 */
class PasswordResetServiceTest {

    private fun contexto(
        seedUsuario: (FakeUsuarioRepository) -> Unit = {},
        seedCodigos: (FakeCodigoVerificacionRepository) -> Unit = {},
        seedTokens: (FakeTokensReseteoRepository) -> Unit = {},
    ): Contexto {
        val usuarios = FakeUsuarioRepository()
        val codigos = FakeCodigoVerificacionRepository()
        val tokens = FakeTokensReseteoRepository()
        seedUsuario(usuarios)
        seedCodigos(codigos)
        seedTokens(tokens)
        val notifier = FakeOtpNotifier()
        val otpService = OtpService(notifier, otpDeterminista = true)
        val servicio =
            PasswordResetService(
                usuarios,
                codigos,
                tokens,
                otpService,
                JwtTokenService(JWT_CONFIG_TEST),
                JWT_CONFIG_TEST,
                TransactionRunner { it() },
            )
        return Contexto(servicio, usuarios, codigos, tokens, notifier)
    }

    private fun usuario(
        id: Long = 1L,
        hash: String = HASH_CONTRASENA,
        estado: EstadoUsuario = EstadoUsuario.ACTIVO,
    ): UsuarioRow =
        UsuarioRow(
            idUsuario = id,
            nombreMenor = "María Camila",
            fechaNacimiento = LocalDate.of(2017, 4, 10),
            correo = "laura.perez@example.com",
            nombreUsuario = "mariacamila",
            contrasenaHash = hash,
            avatar = null,
            intentosLoginFallidos = 0,
            bloqueadoHasta = null,
            estado = estado,
            creadoEn = LocalDateTime.now().minusDays(10),
            actualizadoEn = LocalDateTime.now().minusDays(10),
        )

    private fun codigo(
        id: Long = 1L,
        idUsuario: Long = 1L,
        hash: String = HASH_CODIGO,
        intentosFallidos: Int = 0,
        expiraEn: LocalDateTime = LocalDateTime.now().plusMinutes(10),
        ultimoEnvioEn: LocalDateTime? = LocalDateTime.now().minusMinutes(1),
        usado: Boolean = false,
    ): CodigoVerificacionRow =
        CodigoVerificacionRow(
            idCodigo = id,
            idUsuario = idUsuario,
            codigoHash = hash,
            intentosFallidos = intentosFallidos,
            expiraEn = expiraEn,
            ultimoEnvioEn = ultimoEnvioEn,
            usado = usado,
            creadoEn = LocalDateTime.now().minusMinutes(5),
        )

    private fun token(
        jti: String = "jti-test",
        idUsuario: Long = 1L,
        expiraEn: LocalDateTime = LocalDateTime.now().plusMinutes(10),
        consumido: Boolean = false,
    ): TokensReseteoRow =
        TokensReseteoRow(
            idToken = 1L,
            jti = jti,
            idUsuario = idUsuario,
            expiraEn = expiraEn,
            consumido = consumido,
            creadoEn = LocalDateTime.now().minusMinutes(1),
        )

    /** Emite un token puente real firmado con el secreto de test para el `jti` sembrado. */
    private fun tokenPuente(jti: String = "jti-test", idUsuario: Long = 1L): String =
        JwtTokenService(JWT_CONFIG_TEST).emitirReseteo(idUsuario, jti)

    /** Verifica firma, iss, aud, purpose y vigencia del token puente; devuelve el JWT decodificado. */
    private fun verificarTokenPuente(token: String, idUsuarioEsperado: Long): DecodedJWT {
        val decoded =
            JWT.require(Algorithm.HMAC256(JWT_CONFIG_TEST.secret))
                .withIssuer("era-backend")
                .withAudience("era-app-reset")
                .withClaim("purpose", "PASSWORD_RESET")
                .build()
                .verify(token)
        assertEquals(idUsuarioEsperado.toString(), decoded.subject)
        assertEquals(10L, (decoded.expiresAt.time - decoded.issuedAt.time) / 60_000L)
        assertNotNull(decoded.id, "el token puente debe llevar el jti persistido")
        return decoded
    }

    // ── Paso 1: solicitarReseteo ───────────────────────────────────────────────────────

    @Test
    fun `correo inexistente responde mensaje generico sin insertar ni enviar (anti-enumeracion)`() {
        val ctx = contexto()
        val respuesta = ctx.servicio.solicitarReseteo(PasswordResetRequestDto("no.existe@example.com"))
        assertEquals(MENSAJE_REQUEST, respuesta.message)
        assertEquals(0, ctx.codigos.size(), "no debe insertar código para un correo inexistente")
        assertEquals(0, ctx.envios.size, "no debe enviar nada para un correo inexistente")
    }

    @Test
    fun `correo de cuenta eliminada responde 200 generico sin insertar ni enviar (anti-enumeracion)`() {
        val ctx = contexto(seedUsuario = { it.seed(usuario(estado = EstadoUsuario.ELIMINADO)) })
        val respuesta = ctx.servicio.solicitarReseteo(PasswordResetRequestDto("laura.perez@example.com"))
        assertEquals(MENSAJE_REQUEST, respuesta.message)
        assertEquals(0, ctx.codigos.size(), "una cuenta ELIMINADO no puede recuperar su contraseña (REQ-FUN-05)")
        assertEquals(0, ctx.envios.size)
    }

    @Test
    fun `primer envio inserta codigo hasheado vigente y envia el correo`() {
        val ctx = contexto(seedUsuario = { it.seed(usuario()) })
        val respuesta = ctx.servicio.solicitarReseteo(PasswordResetRequestDto("laura.perez@example.com"))
        assertEquals(MENSAJE_REQUEST, respuesta.message)
        val fila = ctx.codigos.ultimoDe(1L)!!
        assertEquals(0, fila.intentosFallidos)
        assertEquals(false, fila.usado)
        assertNotNull(fila.ultimoEnvioEn, "el envío registra su momento (C-2)")
        assertTrue(fila.expiraEn.isAfter(LocalDateTime.now().plusMinutes(9)), "vigencia debe ser ~now + 10 min")
        assertTrue(fila.expiraEn.isBefore(LocalDateTime.now().plusMinutes(11)))
        assertTrue(
            BCrypt.verifyer().verify("123456".toCharArray(), fila.codigoHash).verified,
            "el hash persistido debe verificar el código emitido",
        )
        assertEquals(listOf("laura.perez@example.com" to "123456"), ctx.envios)
    }

    @Test
    fun `reenvio antes de 60 s lanza 429 sin tocar el codigo previo (throttle C-2)`() {
        val codigoVigente = codigo(ultimoEnvioEn = LocalDateTime.now())
        val ctx = contexto(
            seedUsuario = { it.seed(usuario()) },
            seedCodigos = { it.seed(codigoVigente) },
        )
        assertFailsWith<OtpResendThrottledException> {
            ctx.servicio.solicitarReseteo(PasswordResetRequestDto("laura.perez@example.com"))
        }
        val fila = ctx.codigos.ultimoDe(1L)!!
        assertEquals(codigoVigente.codigoHash, fila.codigoHash, "el throttle no debe sobrescribir el código")
        assertEquals(0, ctx.envios.size, "el throttle no debe enviar correo")
    }

    @Test
    fun `reenvio tras 60 s actualiza el codigo y reinicia el contador P1`() {
        val ctx = contexto(
            seedUsuario = { it.seed(usuario()) },
            seedCodigos = { it.seed(codigo(intentosFallidos = 2, ultimoEnvioEn = LocalDateTime.now().minusSeconds(61))) },
        )
        ctx.servicio.solicitarReseteo(PasswordResetRequestDto("laura.perez@example.com"))
        val fila = ctx.codigos.ultimoDe(1L)!!
        assertEquals(1, ctx.codigos.size(), "el reenvío actualiza la misma fila, no crea otra")
        assertEquals(0, fila.intentosFallidos, "un reenvío reinicia la política P1")
        assertTrue(BCrypt.verifyer().verify("123456".toCharArray(), fila.codigoHash).verified)
        assertEquals(1, ctx.envios.size)
    }

    @Test
    fun `reenvio sin ultimo_envio registrado se permite (filas previas a V3)`() {
        val ctx = contexto(
            seedUsuario = { it.seed(usuario()) },
            seedCodigos = { it.seed(codigo(ultimoEnvioEn = null)) },
        )
        ctx.servicio.solicitarReseteo(PasswordResetRequestDto("laura.perez@example.com"))
        assertEquals(1, ctx.codigos.size())
        assertEquals(1, ctx.envios.size)
    }

    // ── Paso 2: verificarReseteo ───────────────────────────────────────────────────────

    @Test
    fun `correo inexistente en verify lanza 401 generico sin crear token (anti-enumeracion)`() {
        val ctx = contexto()
        assertFailsWith<OtpInvalidException> {
            ctx.servicio.verificarReseteo(
                PasswordResetVerifyRequestDto("no.existe@example.com", "123456"),
            )
        }
        assertEquals(0, ctx.tokens.size(), "no debe emitirse token puente para un correo inexistente")
    }

    @Test
    fun `correo de cuenta eliminada en verify lanza 401 generico (anti-enumeracion)`() {
        val ctx = contexto(seedUsuario = { it.seed(usuario(estado = EstadoUsuario.ELIMINADO)) })
        assertFailsWith<OtpInvalidException> {
            ctx.servicio.verificarReseteo(
                PasswordResetVerifyRequestDto("laura.perez@example.com", "123456"),
            )
        }
        assertEquals(0, ctx.tokens.size())
    }

    @Test
    fun `codigo incorrecto lanza 401 generico e incrementa el contador P1`() {
        val ctx = contexto(
            seedUsuario = { it.seed(usuario()) },
            seedCodigos = { it.seed(codigo()) },
        )
        assertFailsWith<OtpInvalidException> {
            ctx.servicio.verificarReseteo(PasswordResetVerifyRequestDto("laura.perez@example.com", "999999"))
        }
        assertEquals(1, ctx.codigos.ultimoDe(1L)!!.intentosFallidos)
        assertEquals(0, ctx.tokens.size())
    }

    @Test
    fun `al tercer fallo el codigo queda permanentemente invalidado (P1)`() {
        val ctx = contexto(
            seedUsuario = { it.seed(usuario()) },
            seedCodigos = { it.seed(codigo(intentosFallidos = 2)) },
        )
        assertFailsWith<OtpInvalidException> {
            ctx.servicio.verificarReseteo(PasswordResetVerifyRequestDto("laura.perez@example.com", "999999"))
        }
        assertEquals(3, ctx.codigos.ultimoDe(1L)!!.intentosFallidos)
        // Incluso con el código correcto, el límite P1 lo invalida.
        assertFailsWith<OtpInvalidException> {
            ctx.servicio.verificarReseteo(PasswordResetVerifyRequestDto("laura.perez@example.com", "123456"))
        }
        assertEquals(3, ctx.codigos.ultimoDe(1L)!!.intentosFallidos, "el tope P1 no puede superarse")
    }

    @Test
    fun `codigo vencido lanza 401 generico y registra el fallo`() {
        val ctx = contexto(
            seedUsuario = { it.seed(usuario()) },
            seedCodigos = { it.seed(codigo(expiraEn = LocalDateTime.now().minusMinutes(1))) },
        )
        assertFailsWith<OtpInvalidException> {
            ctx.servicio.verificarReseteo(PasswordResetVerifyRequestDto("laura.perez@example.com", "123456"))
        }
        assertEquals(1, ctx.codigos.ultimoDe(1L)!!.intentosFallidos)
    }

    @Test
    fun `codigo ya usado lanza 401 (single-use del OTP)`() {
        val ctx = contexto(
            seedUsuario = { it.seed(usuario()) },
            seedCodigos = { it.seed(codigo(usado = true)) },
        )
        assertFailsWith<OtpInvalidException> {
            ctx.servicio.verificarReseteo(PasswordResetVerifyRequestDto("laura.perez@example.com", "123456"))
        }
        assertEquals(0, ctx.codigos.ultimoDe(1L)!!.intentosFallidos, "un código usado no cuenta como fallo")
    }

    @Test
    fun `sin codigo previo lanza 401 generico`() {
        val ctx = contexto(seedUsuario = { it.seed(usuario()) })
        assertFailsWith<OtpInvalidException> {
            ctx.servicio.verificarReseteo(PasswordResetVerifyRequestDto("laura.perez@example.com", "123456"))
        }
    }

    @Test
    fun `codigo correcto emite token puente firmado y consume el OTP en la misma transaccion`() {
        val ctx = contexto(
            seedUsuario = { it.seed(usuario()) },
            seedCodigos = { it.seed(codigo()) },
        )
        val respuesta =
            ctx.servicio.verificarReseteo(PasswordResetVerifyRequestDto("laura.perez@example.com", "123456"))
        assertTrue(respuesta.resetToken.isNotBlank())
        val decoded = verificarTokenPuente(respuesta.resetToken, idUsuarioEsperado = 1L)
        val filaCodigo = ctx.codigos.ultimoDe(1L)!!
        assertEquals(true, filaCodigo.usado, "el OTP verificado queda consumido (single-use)")
        val filaToken = ctx.tokens.ultimoDe(1L)!!
        assertEquals(decoded.id, filaToken.jti, "el jti del token debe estar persistido para el single-use")
        assertEquals(1L, filaToken.idUsuario)
        assertEquals(false, filaToken.consumido)
    }

    // ── Paso 3: confirmarReseteo ───────────────────────────────────────────────────────

    @Test
    fun `token correcto cambia la contrasena y consume el token`() {
        val ctx = contexto(
            seedUsuario = { it.seed(usuario()) },
            seedTokens = { it.seed(token()) },
        )
        val respuesta =
            ctx.servicio.confirmarReseteo(
                PasswordResetConfirmRequestDto(tokenPuente(), NUEVA_CONTRASENA, NUEVA_CONTRASENA),
            )
        assertEquals(MENSAJE_CONFIRMADO, respuesta.message)
        val usuario = ctx.usuarios.findById(1L)!!
        assertTrue(
            BCrypt.verifyer().verify(NUEVA_CONTRASENA.toCharArray(), usuario.contrasenaHash).verified,
            "el hash persistido debe verificar la nueva contraseña",
        )
        assertTrue(
            !BCrypt.verifyer().verify(CONTRASENA.toCharArray(), usuario.contrasenaHash).verified,
            "la contraseña anterior ya no debe servir",
        )
        assertEquals(true, ctx.tokens.ultimoDe(1L)!!.consumido, "el token puente es single-use (C-3)")
    }

    @Test
    fun `reutilizar el mismo token lanza 401 y la contrasena no cambia (single-use)`() {
        val ctx = contexto(
            seedUsuario = { it.seed(usuario()) },
            seedTokens = { it.seed(token()) },
        )
        val peticion = PasswordResetConfirmRequestDto(tokenPuente(), NUEVA_CONTRASENA, NUEVA_CONTRASENA)
        ctx.servicio.confirmarReseteo(peticion)
        assertFailsWith<ResetTokenInvalidException> { ctx.servicio.confirmarReseteo(peticion) }
        val usuario = ctx.usuarios.findById(1L)!!
        assertTrue(
            BCrypt.verifyer().verify(NUEVA_CONTRASENA.toCharArray(), usuario.contrasenaHash).verified,
            "el segundo uso no debe alterar la contraseña ya cambiada",
        )
        assertEquals(true, ctx.tokens.ultimoDe(1L)!!.consumido)
    }

    @Test
    fun `token con firma invalida lanza 401 generico`() {
        val tokenFalsificado =
            JWT.create()
                .withSubject("1")
                .withIssuer("era-backend")
                .withAudience("era-app-reset")
                .withClaim("purpose", "PASSWORD_RESET")
                .withExpiresAt(Date.from(Instant.now().plusSeconds(600)))
                .withJWTId("jti-x")
                .sign(Algorithm.HMAC256("otro-secreto"))
        val ctx = contexto(
            seedUsuario = { it.seed(usuario()) },
            seedTokens = { it.seed(token(jti = "jti-x")) },
        )
        assertFailsWith<ResetTokenInvalidException> {
            ctx.servicio.confirmarReseteo(
                PasswordResetConfirmRequestDto(tokenFalsificado, NUEVA_CONTRASENA, NUEVA_CONTRASENA),
            )
        }
        assertEquals(false, ctx.tokens.ultimoDe(1L)!!.consumido, "un token inválido no se consume")
    }

    @Test
    fun `token expirado lanza 401 generico`() {
        val tokenExpirado =
            JWT.create()
                .withSubject("1")
                .withIssuer("era-backend")
                .withAudience("era-app-reset")
                .withClaim("purpose", "PASSWORD_RESET")
                .withIssuedAt(Date.from(Instant.now().minusSeconds(120)))
                .withExpiresAt(Date.from(Instant.now().minusSeconds(60)))
                .withJWTId("jti-expirado")
                .sign(Algorithm.HMAC256(JWT_CONFIG_TEST.secret))
        val ctx = contexto(
            seedUsuario = { it.seed(usuario()) },
            seedTokens = { it.seed(token(jti = "jti-expirado", expiraEn = LocalDateTime.now().minusMinutes(1))) },
        )
        assertFailsWith<ResetTokenInvalidException> {
            ctx.servicio.confirmarReseteo(
                PasswordResetConfirmRequestDto(tokenExpirado, NUEVA_CONTRASENA, NUEVA_CONTRASENA),
            )
        }
    }

    @Test
    fun `token con purpose distinto lanza 401 generico`() {
        val tokenOtroProposito =
            JWT.create()
                .withSubject("1")
                .withIssuer("era-backend")
                .withAudience("era-app-reset")
                .withClaim("purpose", "OTRO_PROPOSITO")
                .withExpiresAt(Date.from(Instant.now().plusSeconds(600)))
                .withJWTId("jti-purpose")
                .sign(Algorithm.HMAC256(JWT_CONFIG_TEST.secret))
        val ctx = contexto(
            seedUsuario = { it.seed(usuario()) },
            seedTokens = { it.seed(token(jti = "jti-purpose")) },
        )
        assertFailsWith<ResetTokenInvalidException> {
            ctx.servicio.confirmarReseteo(
                PasswordResetConfirmRequestDto(tokenOtroProposito, NUEVA_CONTRASENA, NUEVA_CONTRASENA),
            )
        }
    }

    @Test
    fun `token con jti no registrado lanza 401 generico`() {
        val ctx = contexto(seedUsuario = { it.seed(usuario()) })
        assertFailsWith<ResetTokenInvalidException> {
            ctx.servicio.confirmarReseteo(
                PasswordResetConfirmRequestDto(tokenPuente("jti-sin-registrar"), NUEVA_CONTRASENA, NUEVA_CONTRASENA),
            )
        }
    }

    @Test
    fun `token cuyo id_usuario no coincide con el sub se rechaza (doble vinculo C-3)`() {
        val ctx = contexto(
            seedUsuario = { it.seed(usuario(id = 1L)) },
            seedTokens = { it.seed(token(jti = "jti-ajeno", idUsuario = 2L)) },
        )
        // El JWT pertenece a la cuenta 1, pero la fila en tokens_reseteo es de la cuenta 2.
        assertFailsWith<ResetTokenInvalidException> {
            ctx.servicio.confirmarReseteo(
                PasswordResetConfirmRequestDto(tokenPuente("jti-ajeno", idUsuario = 1L), NUEVA_CONTRASENA, NUEVA_CONTRASENA),
            )
        }
    }

    @Test
    fun `token valido sin cuenta subyacente lanza 401 generico`() {
        val ctx = contexto(seedTokens = { it.seed(token()) })
        assertFailsWith<ResetTokenInvalidException> {
            ctx.servicio.confirmarReseteo(
                PasswordResetConfirmRequestDto(tokenPuente(), NUEVA_CONTRASENA, NUEVA_CONTRASENA),
            )
        }
    }

    @Test
    fun `nueva contrasena igual a la anterior lanza 409 sin tocar la bd (REQ-FUN-07 CA5)`() {
        val ctx = contexto(
            seedUsuario = { it.seed(usuario()) },
            seedTokens = { it.seed(token()) },
        )
        val ex =
            assertFailsWith<PasswordReuseException> {
                ctx.servicio.confirmarReseteo(
                    PasswordResetConfirmRequestDto(tokenPuente(), CONTRASENA, CONTRASENA),
                )
            }
        assertEquals("La nueva contraseña no puede ser igual a la anterior.", ex.message)
        assertEquals(false, ctx.tokens.ultimoDe(1L)!!.consumido, "el veto no debe consumir el token")
        assertTrue(
            BCrypt.verifyer().verify(CONTRASENA.toCharArray(), ctx.usuarios.findById(1L)!!.contrasenaHash).verified,
            "la contraseña anterior debe seguir vigente",
        )
    }

    @Test
    fun `nueva contrasena que incumple la politica lanza 400 con detalles (C-6)`() {
        val ctx = contexto(
            seedUsuario = { it.seed(usuario()) },
            seedTokens = { it.seed(token()) },
        )
        val ex =
            assertFailsWith<ValidationException> {
                ctx.servicio.confirmarReseteo(
                    PasswordResetConfirmRequestDto(tokenPuente(), "corta", "corta"),
                )
            }
        assertTrue(ex.details.any { it.field == "contrasena" && it.message.contains("al menos 8") })
        assertEquals(false, ctx.tokens.ultimoDe(1L)!!.consumido, "la política falla antes de consumir el token")
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

        val CONTRASENA = "Trivia#2025"
        val NUEVA_CONTRASENA = "Nueva#2026"

        /** Hash bcrypt real de la contraseña sembrada (coste 8, solo test). */
        val HASH_CONTRASENA: String = BCrypt.withDefaults().hashToString(8, CONTRASENA.toCharArray())

        /** Hash bcrypt real del OTP determinista "123456" (coste 12, igual que OtpService). */
        val HASH_CODIGO: String = BCrypt.withDefaults().hashToString(12, "123456".toCharArray())

        val MENSAJE_REQUEST = "Si el correo está registrado, recibirás un código de verificación."
        val MENSAJE_CONFIRMADO = "Contraseña actualizada. Ya puedes iniciar sesión."
    }
}

/** Estado mutable del [PasswordResetServiceTest]: service + fakes + notifier para aserciones. */
private class Contexto(
    val servicio: PasswordResetService,
    val usuarios: FakeUsuarioRepository,
    val codigos: FakeCodigoVerificacionRepository,
    val tokens: FakeTokensReseteoRepository,
    val notifier: FakeOtpNotifier,
) {
    val envios: MutableList<Pair<String, String>>
        get() = notifier.envios
}
