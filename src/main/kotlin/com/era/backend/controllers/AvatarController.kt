package com.era.backend.controllers

import com.era.backend.exceptions.FieldError
import com.era.backend.exceptions.ValidationException
import com.era.backend.models.SesionPrincipal
import com.era.backend.models.dto.MensajeResponseDto
import com.era.backend.services.AvatarService
import com.era.backend.storage.ContenidoAvatar
import com.era.backend.utils.AvatarValidador
import com.era.backend.utils.FormatoAvatar
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import kotlinx.io.Source

/**
 * Handler de los endpoints de avatar personalizado (Módulo I: `PUT` y
 * `GET /api/v1/users/me/avatar`, CU-06 3a, REQ-FUN-06 CA4/CA5). Protegido por el proveedor
 * `session-jwt`: la identidad llega como [SesionPrincipal], nunca como parámetro del cliente.
 *
 * El PUT es multipart (`multipart/form-data`) y aplica la **primera barrera de tamaño** con
 * lectura limitada del stream ([leerParteLimitada]): se consume el canal en fragmentos y se
 * aborta en cuanto se superan los 2 MB, sin acumular el archivo completo en RAM (corte antes
 * de procesar la parte por completo, `modulo-i-analisis.md` §2.1). El service aplica la
 * segunda barrera (magic bytes + `Content-Type`) y las reglas de negocio.
 *
 * Nota de Ktor 3.4.3: `PartData` vive en `io.ktor.http.content` y la iteración de multipart
 * se hace con `readPart()` (no existe `forEachPart`).
 */
class AvatarController(
    private val avatarService: AvatarService,
) {

    /**
     * `PUT /api/v1/users/me/avatar` (CU-06 3a): recibe multipart con una única parte `avatar`.
     *
     * Validaciones de forma (§4.1): parte ausente → 400 "Se requiere un archivo."; tamaño > 2 MB
     * → 400 "Máximo 2 MB." (ambos con `FieldError("avatar", …)`). El `filename` del cliente se
     * ignora (anonimización §2.3). El formato (magic bytes + doble validación) lo decide el
     * service.
     *
     * Respuestas (mapeadas por StatusPages): 200 `MensajeResponseDto` · 400 `VALIDATION_ERROR` ·
     * 401 challenge del proveedor · 403 `ACCOUNT_INACTIVE`.
     */
    suspend fun subirAvatar(call: ApplicationCall): Unit {
        val sesion = call.principal<SesionPrincipal>()
            ?: throw IllegalStateException("Sesión no resuelta en ruta autenticada.")

        var bytes: ByteArray? = null
        var contentType: String? = null

        val multipart = call.receiveMultipart()
        while (true) {
            val parte = multipart.readPart() ?: break
            // Ktor 3.4.3 (engine CIO): una parte con `filename` llega como `FileItem`; una parte
            // sin `filename` se lee entera y se decodifica como texto → `FormItem` (sin bytes
            // utilizables), por lo que se ignora. `BinaryItem` no lo produce este engine; se
            // acepta por defensa si algún engine/cliente lo entregara.
            val esArchivo = parte is PartData.FileItem || parte is PartData.BinaryItem
            if (bytes == null && esArchivo && parte.name == CAMPO_AVATAR) {
                bytes = leerParteLimitada(parte)
                contentType = parte.contentType?.toString()
            }
            parte.dispose()
        }

        val contenido = bytes
            ?: throw ValidationException(MENSAJE_SIN_ARCHIVO, listOf(FieldError(CAMPO_AVATAR, MENSAJE_SIN_ARCHIVO)))

        val respuesta: MensajeResponseDto =
            avatarService.subirAvatar(sesion.idUsuario, contenido, contentType)
        call.respond(HttpStatusCode.OK, respuesta)
    }

    /**
     * `GET /api/v1/users/me/avatar` (CU-06): sirve el binario de la foto personalizada.
     *
     * Solo tiene sentido cuando `GET /me` devuelve un valor `custom:*`. Headers §3.2: tipo MIME
     * canónico (el persistido al subir, nunca el del cliente), `Cache-Control: private, no-store`
     * (foto de un menor, §5), `X-Content-Type-Options: nosniff` y `Content-Disposition` con
     * nombre opaco. En Ktor 3.4.3 los headers de respuesta se fijan vía `call.response.headers`
     * (el lambda de `respondBytes` solo configura el `OutgoingContent`, cuyos `headers` son
     * inmutables).
     *
     * Respuestas: 200 bytes · 401 challenge · 403 `ACCOUNT_INACTIVE` · 404 `NOT_FOUND`.
     */
    suspend fun obtenerAvatar(call: ApplicationCall): Unit {
        val sesion = call.principal<SesionPrincipal>()
            ?: throw IllegalStateException("Sesión no resuelta en ruta autenticada.")

        val contenido: ContenidoAvatar = avatarService.obtenerAvatar(sesion.idUsuario)
        val extension = FormatoAvatar.entries
            .firstOrNull { it.contentType == contenido.contentType }
            ?.extension ?: EXTENSION_FALLBACK

        call.response.headers.append(HttpHeaders.CacheControl, "private, no-store")
        call.response.headers.append(HttpHeaders.ContentDisposition, "inline; filename=\"avatar.$extension\"")
        call.response.headers.append(HEADER_X_CONTENT_TYPE_OPTIONS, "nosniff")

        call.respondBytes(
            bytes = contenido.bytes,
            contentType = ContentType.parse(contenido.contentType),
            status = HttpStatusCode.OK,
        )
    }

    /**
     * Lee el canal de la parte multipart con tope de [AvatarValidador.MAX_TAMANO_BYTES], en
     * fragmentos, y **aborta en cuanto se supera el límite** (sin cargar el resto en RAM).
     * Devuelve `null` si la parte no es un archivo (campo de formulario u otra clase de `PartData`);
     * el flujo [subirAvatar] lo interpreta como "no hay foto". Soportan `PartData.FileItem`
     * (`provider()` es [ByteReadChannel]) y `PartData.BinaryItem` (`provider()` es un
     * `kotlinx.io.Source`); ambos se leen por fragmentos con el mismo tope.
     */
    private suspend fun leerParteLimitada(parte: PartData): ByteArray? {
        val limite = AvatarValidador.MAX_TAMANO_BYTES
        val salida = ByteArrayOutputStream(limite)
        val fragmento = ByteArray(TAMANO_FRAGMENTO)
        var total = 0

        val acumular: (Int) -> Unit = { leidos ->
            total += leidos
            if (total > limite) {
                throw ValidationException(
                    MENSAJE_TAMANO_EXCEDIDO,
                    listOf(FieldError(CAMPO_AVATAR, MENSAJE_TAMANO_EXCEDIDO)),
                )
            }
            salida.write(fragmento, 0, leidos)
        }

        when (parte) {
            is PartData.FileItem -> {
                val canal: ByteReadChannel = parte.provider()
                while (true) {
                    val leidos = canal.readAvailable(fragmento)
                    if (leidos < 0) break
                    if (leidos == 0) continue
                    acumular(leidos)
                }
            }
            is PartData.BinaryItem -> {
                val fuente: Source = parte.provider()
                while (true) {
                    val leidos = fuente.readAtMostTo(fragmento, 0, fragmento.size)
                    if (leidos < 0) break
                    if (leidos == 0) continue
                    acumular(leidos)
                }
            }
            else -> return null
        }
        return salida.toByteArray()
    }

    companion object {
        /** Nombre de la parte multipart que porta el archivo (§3.1). */
        const val CAMPO_AVATAR = "avatar"

        /** Header de seguridad (§3.2): Ktor 3.4.3 no expone la constante en `HttpHeaders`. */
        private const val HEADER_X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"

        /** Tamaño de fragmento de lectura del canal multipart (RAM acotada, §2.1). */
        private const val TAMANO_FRAGMENTO = 8 * 1024

        /** Fallback cosmético del `Content-Disposition` si el tipo no matchea (§3.2). */
        private const val EXTENSION_FALLBACK = "img"

        /** 400 de parte ausente (§4.1). */
        private const val MENSAJE_SIN_ARCHIVO = "Se requiere un archivo."

        /** 400 de tamaño (§4.1). */
        private const val MENSAJE_TAMANO_EXCEDIDO = "Máximo 2 MB."
    }
}
