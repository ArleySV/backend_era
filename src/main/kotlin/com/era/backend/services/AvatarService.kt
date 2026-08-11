package com.era.backend.services

import com.era.backend.exceptions.AccountInactiveException
import com.era.backend.exceptions.FieldError
import com.era.backend.exceptions.NotFoundException
import com.era.backend.exceptions.ValidationException
import com.era.backend.models.dto.MensajeResponseDto
import com.era.backend.models.entities.EstadoUsuario
import com.era.backend.repositories.TransactionRunner
import com.era.backend.repositories.UsuarioRepository
import com.era.backend.storage.AvatarStorage
import com.era.backend.storage.ContenidoAvatar
import com.era.backend.utils.AvatarValidador
import com.era.backend.utils.FormatoAvatar
import java.util.UUID
import org.slf4j.LoggerFactory

/**
 * Reglas de negocio del Módulo I (Avatar personalizado, REQ-FUN-06 CA4/CA5, CU-06 3a, HU-06 —
 * `modulo-i-analisis.md`). Puro de Ktor y de SQL: recibe el `id_usuario` del
 * [com.era.backend.models.SesionPrincipal], lanza excepciones de dominio y delega el acceso a
 * datos en [UsuarioRepository] y la persistencia de archivos en [AvatarStorage] (interfaz
 * independiente aprobada, §2.3/§7.3: esta clase JAMÁS conoce la implementación concreta).
 *
 * Reglas centrales:
 * - **Validación mandatoria (§2.1):** el formato se detecta por magic bytes + fourCC WebP y se
 *   exige que coincida con el `Content-Type` declarado; el binario es la fuente de verdad.
 * - **Ciclo de vida con compensación (§2.4):** el archivo se escribe FUERA de la transacción y,
 *   si `usuarioRepository.actualizarAvatar` falla, se elimina el archivo recién escrito para no
 *   dejar huérfanos en disco.
 * - **403 cuenta inactiva:** verificada ANTES de escribir cualquier archivo (no se persiste nada
 *   de una cuenta en soft delete).
 * - **Regla de oro (§5):** se loguea el evento (`idUsuario`) pero NUNCA la clave `custom:*`
 *   completa ni el path del archivo en logs INFO públicos.
 */
class AvatarService(
    private val usuarioRepository: UsuarioRepository,
    private val avatarStorage: AvatarStorage,
    private val transactionRunner: TransactionRunner,
) {

    private val log = LoggerFactory.getLogger(AvatarService::class.java)

    /**
     * Sube (o reemplaza) la foto personalizada del usuario autenticado (CU-06 3a).
     *
     * Flujo (§2.4): 1) valida formato/tamaño (sin tocar BD ni disco); 2) transacción: verifica
     * cuenta activa y lee la clave vieja con `FOR UPDATE`; 3) escribe el archivo nuevo con clave
     * `custom:<uuid>`; 4) transacción: `actualizarAvatar`; 5) tras commit, elimina best-effort
     * el archivo viejo si era `custom:*`. Si el paso 4 falla → **compensación**: se elimina el
     * archivo recién escrito y se propaga el error.
     *
     * Respuestas: 200 `MensajeResponseDto` · 400 `VALIDATION_ERROR` · 403 `ACCOUNT_INACTIVE` ·
     * 404 defensivo · 500 por I/O vía StatusPages.
     */
    fun subirAvatar(idUsuario: Long, bytes: ByteArray, contentTypeCliente: String?): MensajeResponseDto {
        val formato = validarEntrada(bytes, contentTypeCliente)
        val claveNueva = generarClave(formato)
        val claveVieja = obtenerAvatarActual(idUsuario)

        avatarStorage.guardar(claveNueva, bytes, formato.contentType)

        try {
            transactionRunner.run {
                usuarioRepository.actualizarAvatar(idUsuario, claveNueva)
            }
        } catch (e: Exception) {
            // Compensación (§2.4): el UPDATE de BD falló → el archivo recién escrito no debe
            // quedar huérfano en disco. No enmascarar el error original.
            try {
                avatarStorage.eliminar(claveNueva)
            } catch (eliminarError: Exception) {
                log.warn("Compensación de avatar: no se pudo eliminar el archivo recién escrito.", eliminarError)
            }
            throw e
        }

        eliminarAvatarSiPersonalizado(claveVieja)

        log.info("Avatar actualizado idUsuario={}", idUsuario)
        return MensajeResponseDto(MENSAJE_AVATAR_ACTUALIZADO)
    }

    /**
     * Devuelve el binario y su tipo MIME canónico para servirlo (§3.2, GET autenticado).
     *
     * Respuestas: 200 bytes · 403 `ACCOUNT_INACTIVE` · 404 `NOT_FOUND` si no hay foto
     * personalizada (`avatar` NULL o `preset:*`) o el archivo no existe (404 defensivo).
     */
    fun obtenerAvatar(idUsuario: Long): ContenidoAvatar {
        var clave: String? = null
        transactionRunner.run {
            val usuario = usuarioRepository.findById(idUsuario)
                ?: throw NotFoundException("Usuario no encontrado.")
            if (usuario.estado != EstadoUsuario.ACTIVO) {
                throw AccountInactiveException(MENSAJE_CUENTA_INACTIVA)
            }
            clave = usuario.avatar
        }
        val claveAvatar: String? = clave
        if (claveAvatar == null || !claveAvatar.startsWith(PREFIJO_CUSTOM)) {
            throw NotFoundException(MENSAJE_SIN_AVATAR)
        }
        return avatarStorage.leer(claveAvatar) ?: throw NotFoundException(MENSAJE_SIN_AVATAR)
    }

    /**
     * Bloque reutilizable por el futuro PATCH de username (§2.4): elimina el archivo solo si
     * [clave] es `custom:*`. Los presets (`preset:1|2|3`) y `NULL` no tienen archivo en disco.
     * Best-effort: un fallo de I/O se loguea (sin la clave) y no corta la operación.
     */
    fun eliminarAvatarSiPersonalizado(clave: String?) {
        if (clave == null || !clave.startsWith(PREFIJO_CUSTOM)) return
        try {
            avatarStorage.eliminar(clave)
        } catch (e: Exception) {
            log.warn("No se pudo eliminar el archivo de avatar anterior del usuario.", e)
        }
    }

    /** Paso 1 y 2 del PUT (§2.4): verifica cuenta activa y lee la clave actual con `FOR UPDATE`. */
    private fun obtenerAvatarActual(idUsuario: Long): String? {
        var clave: String? = null
        transactionRunner.run {
            val usuario = usuarioRepository.findByIdForUpdate(idUsuario)
                ?: throw NotFoundException("Usuario no encontrado.")
            if (usuario.estado != EstadoUsuario.ACTIVO) {
                throw AccountInactiveException(MENSAJE_CUENTA_INACTIVA)
            }
            clave = usuario.avatar
        }
        return clave
    }

    /**
     * Validación mandatoria de entrada (§2.1): tamaño ≤ 2 MB, magic bytes + fourCC WebP, y
     * concordancia con el `Content-Type` declarado (doble validación de la decisión 7). La
     * primera barrera de tamaño ya se aplicó al leer el multipart en el controller; esta es la
     * barrera de negocio (defensa en profundidad).
     */
    private fun validarEntrada(bytes: ByteArray, contentTypeCliente: String?): FormatoAvatar {
        if (bytes.size > AvatarValidador.MAX_TAMANO_BYTES) {
            throw ValidationException(MENSAJE_TAMANO_EXCEDIDO, listOf(FieldError(CAMPO_AVATAR, MENSAJE_TAMANO_EXCEDIDO)))
        }
        val formato = AvatarValidador.detectarFormato(bytes)
            ?: throw ValidationException(MENSAJE_FORMATO_INVALIDO, listOf(FieldError(CAMPO_AVATAR, MENSAJE_FORMATO_INVALIDO)))
        val declarado = FormatoAvatar.entries.firstOrNull { it.contentType == contentTypeCliente }
        if (declarado == null || declarado != formato) {
            throw ValidationException(MENSAJE_FORMATO_INVALIDO, listOf(FieldError(CAMPO_AVATAR, MENSAJE_FORMATO_INVALIDO)))
        }
        return formato
    }

    /** Clave de storage opaca y anonimizada (§2.2): `custom:<uuid>.<ext>`, extensión canónica. */
    private fun generarClave(formato: FormatoAvatar): String =
        "custom:${UUID.randomUUID()}.${formato.extension}"

    companion object {
        /** Prefijo reservado de foto personalizada (§2.2), simétrico a `preset:` de AvatarPreset. */
        const val PREFIJO_CUSTOM = "custom:"

        /** Mensaje de cuenta en soft delete: idéntico a los Módulos D/G/H. */
        private const val MENSAJE_CUENTA_INACTIVA = "La cuenta no está activa."

        /** Confirmación de la subida (decisión aprobada: reutiliza `MensajeResponseDto`, §3.1). */
        const val MENSAJE_AVATAR_ACTUALIZADO = "Avatar actualizado con éxito."

        /** 404 del GET: solo aplica cuando `GET /me` devuelve `custom:*` (§4.1). */
        private const val MENSAJE_SIN_AVATAR = "No hay avatar personalizado."

        /** 400 de formato: genérico, sin detallar cuál firma falló (anti-enumeración, §4.1). */
        private const val MENSAJE_FORMATO_INVALIDO = "Formato no permitido: jpeg, png o webp."

        /** 400 de tamaño (§4.1). */
        private const val MENSAJE_TAMANO_EXCEDIDO = "Máximo 2 MB."

        private const val CAMPO_AVATAR = "avatar"
    }
}
