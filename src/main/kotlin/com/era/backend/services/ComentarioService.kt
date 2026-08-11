package com.era.backend.services

import com.era.backend.exceptions.AccountInactiveException
import com.era.backend.exceptions.NotFoundException
import com.era.backend.models.dto.MensajeResponseDto
import com.era.backend.models.entities.EstadoUsuario
import com.era.backend.repositories.ComentarioRepository
import com.era.backend.repositories.TransactionRunner
import com.era.backend.repositories.UsuarioRepository
import org.slf4j.LoggerFactory

/**
 * Reglas de negocio del Módulo H (Comentarios, REQ-FUN-14, CU-10, HU-14 —
 * `modulo-h-analisis.md`). Puro de Ktor y de SQL: recibe el `id_usuario` del
 * [com.era.backend.models.SesionPrincipal], lanza excepciones de dominio y delega el acceso
 * a datos en [ComentarioRepository] (ARQUITECTURA_BASE.md §2.3).
 *
 * Reglas centrales:
 * - **403 cuenta inactiva (§3.2):** se verifica el estado de la cuenta ANTES de insertar;
 *   una cuenta en soft delete no puede escribir.
 * - **Sanitización (§7.3):** `.trim()` del contenido antes de pasarlo al repositorio, para
 *   no almacenar espacios innecesarios.
 * - **Transaccionalidad (§7.3):** la inserción va dentro de `TransactionRunner` aunque sea
 *   una sola escritura (estándar de manejo de excepciones de Exposed).
 * - **Regla de oro (§5):** el contenido del comentario NUNCA se loguea; la auditoría usa
 *   solo `idUsuario` e `idComentario`.
 */
class ComentarioService(
    private val usuarioRepository: UsuarioRepository,
    private val comentarioRepository: ComentarioRepository,
    private val transactionRunner: TransactionRunner,
) {

    private val log = LoggerFactory.getLogger(ComentarioService::class.java)

    /**
     * Persiste el comentario del usuario autenticado y devuelve la confirmación de recepción
     * (REQ-FUN-14 CA3, CU-10 paso 5).
     *
     * Respuestas: 200 `MensajeResponseDto` · 403 `ACCOUNT_INACTIVE` · 404 defensivo.
     */
    fun enviarComentario(idUsuario: Long, contenido: String): MensajeResponseDto {
        var idComentario: Long = 0L
        transactionRunner.run {
            verificarCuentaActiva(idUsuario)
            idComentario = comentarioRepository.insertar(idUsuario, contenido.trim())
        }
        // Auditoría sin datos sensibles (CLAUDE.md §6): nunca el contenido del comentario.
        log.info("Comentario registrado idComentario={} idUsuario={}", idComentario, idUsuario)
        return MensajeResponseDto(MENSAJE_CONFIRMACION)
    }

    /** 403 si la cuenta está en soft delete (H §3.2, REQ-FUN-05 CA5); 404 defensivo. */
    private fun verificarCuentaActiva(idUsuario: Long) {
        val usuario = usuarioRepository.findById(idUsuario)
            ?: throw NotFoundException("Usuario no encontrado.")
        if (usuario.estado != EstadoUsuario.ACTIVO) {
            throw AccountInactiveException(MENSAJE_CUENTA_INACTIVA)
        }
    }

    companion object {
        /** Mensaje de cuenta en soft delete (H §3.2): idéntico al de los Módulos D y G. */
        private const val MENSAJE_CUENTA_INACTIVA = "La cuenta no está activa."

        /** Confirmación de recepción (REQ-FUN-14 CA3, CU-10 paso 5). */
        const val MENSAJE_CONFIRMACION = "Comentario enviado con éxito."
    }
}
