package com.era.backend.services

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.era.backend.exceptions.AccountInactiveException
import com.era.backend.exceptions.NotFoundException
import com.era.backend.models.entities.EstadoUsuario
import com.era.backend.models.entities.UsuarioRow
import com.era.backend.repositories.FakeComentarioRepository
import com.era.backend.repositories.FakeUsuarioRepository
import com.era.backend.repositories.TransactionRunner
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory

/**
 * Unit tests de [ComentarioService] (Módulo H, `modulo-h-analisis.md` §7.3 y §8). Sin MySQL:
 * `FakeUsuarioRepository` (cuenta activa/eliminada) y `FakeComentarioRepository`.
 *
 * Cobertura: persistencia con confirmación, sanitización `.trim()` antes de insertar, 403 de
 * cuenta en soft delete sin escrituras, 404 defensivo, una sola transacción por envío y la
 * regla de oro §5 (el contenido del comentario nunca aparece en el log).
 */
class ComentarioServiceTest {

    private fun servicioCon(
        seedUsuario: (FakeUsuarioRepository) -> Unit,
        seedComentarios: (FakeComentarioRepository) -> Unit = {},
        runner: TransactionRunner = TransactionRunner { it() },
    ): Contexto {
        val usuarios = FakeUsuarioRepository()
        seedUsuario(usuarios)
        val comentarios = FakeComentarioRepository()
        seedComentarios(comentarios)
        val servicio = ComentarioService(usuarios, comentarios, runner)
        return Contexto(servicio, comentarios, runner)
    }

    /** Contexto de una prueba: service + fakes + runner (para contar transacciones). */
    class Contexto(
        val servicio: ComentarioService,
        val comentarios: FakeComentarioRepository,
        val runner: TransactionRunner,
    )

    private fun usuario(
        id: Long = 1L,
        estado: EstadoUsuario = EstadoUsuario.ACTIVO,
    ): UsuarioRow =
        UsuarioRow(
            idUsuario = id,
            nombreMenor = "María Camila",
            fechaNacimiento = LocalDate.of(2017, 4, 10),
            correo = "laura.perez@example.com",
            nombreUsuario = "mariacamila",
            contrasenaHash = "hash-de-prueba",
            avatar = null,
            intentosLoginFallidos = 0,
            bloqueadoHasta = null,
            estado = estado,
            creadoEn = LocalDateTime.now(),
            actualizadoEn = LocalDateTime.now(),
        )

    private fun seedActivo(usuarios: FakeUsuarioRepository): FakeUsuarioRepository {
        usuarios.seed(usuario())
        return usuarios
    }

    // ── Happy path ────────────────────────────────────────────────────────────────────

    @Test
    fun `envio de usuario activo persiste y devuelve confirmacion`() {
        val ctx = servicioCon(seedUsuario = { seedActivo(it) })
        val respuesta = ctx.servicio.enviarComentario(1L, "La trivia de matemáticas me encantó")
        assertEquals(ComentarioService.MENSAJE_CONFIRMACION, respuesta.message)
        assertEquals(1, ctx.comentarios.size())
        assertEquals(1L, ctx.comentarios.todos().single().idUsuario)
    }

    @Test
    fun `el contenido se trimea antes de persistir`() {
        val ctx = servicioCon(seedUsuario = { seedActivo(it) })
        ctx.servicio.enviarComentario(1L, "   ¡Súper divertido!   ")
        assertEquals("¡Súper divertido!", ctx.comentarios.todos().single().contenido)
    }

    @Test
    fun `el envio corre en una sola transaccion`() {
        var llamadas = 0
        val runner = TransactionRunner { llamadas++; it() }
        val ctx = servicioCon(seedUsuario = { seedActivo(it) }, runner = runner)
        ctx.servicio.enviarComentario(1L, "Muy buena")
        assertEquals(1, llamadas)
    }

    // ── Cuenta inactiva / inexistente ─────────────────────────────────────────────────

    @Test
    fun `cuenta eliminada lanza AccountInactiveException y no inserta`() {
        val ctx =
            servicioCon(seedUsuario = { it.seed(usuario(estado = EstadoUsuario.ELIMINADO)) })
        assertFailsWith<AccountInactiveException> {
            ctx.servicio.enviarComentario(1L, "Hola")
        }
        assertEquals(0, ctx.comentarios.size(), "la cuenta en soft delete no puede escribir")
    }

    @Test
    fun `usuario inexistente lanza NotFoundException defensivo y no inserta`() {
        val ctx = servicioCon(seedUsuario = { seedActivo(it) })
        assertFailsWith<NotFoundException> {
            ctx.servicio.enviarComentario(99L, "Hola")
        }
        assertEquals(0, ctx.comentarios.size())
    }

    // ── Regla de oro: el contenido nunca se loguea (§5) ───────────────────────────────

    @Test
    fun `el log de auditoria no incluye el contenido del comentario`() {
        val contenidoSecreto = "Texto privado que jamás debe aparecer en un log"
        val ctx = servicioCon(seedUsuario = { seedActivo(it) })
        val logger = LoggerFactory.getLogger(ComentarioService::class.java) as Logger
        val captura = ListAppender<ILoggingEvent>()
        captura.start()
        logger.addAppender(captura)
        try {
            ctx.servicio.enviarComentario(1L, contenidoSecreto)
            assertTrue(captura.list.isNotEmpty(), "la auditoría escribe un log INFO")
            captura.list.forEach { evento ->
                assertFalse(
                    evento.formattedMessage.contains(contenidoSecreto),
                    "ningún evento puede contener el texto del comentario",
                )
            }
            assertTrue(
                captura.list.any { it.formattedMessage.contains("idComentario=") && it.formattedMessage.contains("idUsuario=") },
                "la auditoría usa solo idComentario e idUsuario",
            )
        } finally {
            logger.detachAppender(captura)
        }
    }
}
