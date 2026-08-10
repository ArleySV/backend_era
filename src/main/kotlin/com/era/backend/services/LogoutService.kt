package com.era.backend.services

import com.era.backend.models.dto.MensajeResponseDto
import org.slf4j.LoggerFactory

/**
 * Cierre de sesión (Módulo F, REQ-FUN-04, CU-05, HU-04; `ARQUITECTURA_BASE.md` §5.1).
 *
 * Arquitectura stateless (ARQUITECTURA_BASE.md §5.4 Decisión 2): no existe sesión
 * server-side ni blacklist de tokens, por lo que la invalidación efectiva del token es
 * local (el cliente lo descarta, REQ-FUN-04 CA2). La responsabilidad de este service es
 * mínima y deliberada:
 * 1. Registrar el cierre en el log de aplicación con el `id_usuario` — nunca el token, el
 *    correo ni la cédula (CLAUDE.md §6).
 * 2. Devolver la confirmación formal ([MensajeResponseDto]) para que el cliente redirija
 *    al login con certeza de que el servidor reconoció la sesión.
 *
 * No consulta ni modifica la BD: el logout no expone datos y los datos/progreso se
 * conservan por construcción (REQ-FUN-04 CA4). El endpoint es idempotente: repetir la
 * petición con el mismo token vuelve a responder 200.
 */
class LogoutService {

    private val log = LoggerFactory.getLogger(LogoutService::class.java)

    /**
     * Confirma formalmente el cierre de sesión del usuario autenticado.
     *
     * @param idUsuario identificador del usuario autenticado ([SesionPrincipal]); solo se
     *   usa para el log de auditoría, nunca se expone en la respuesta.
     * @return confirmación formal para el cliente (REQ-FUN-04 CA2).
     */
    fun cerrarSesion(idUsuario: Long): MensajeResponseDto {
        log.info("Cierre de sesión del usuario idUsuario={}", idUsuario)
        return MensajeResponseDto(MENSAJE_SESION_CERRADA)
    }

    companion object {
        /** Confirmación formal del cierre de sesión (REQ-FUN-04 CA2). */
        const val MENSAJE_SESION_CERRADA = "Sesión cerrada."
    }
}
