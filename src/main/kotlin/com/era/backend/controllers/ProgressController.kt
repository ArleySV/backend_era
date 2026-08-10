package com.era.backend.controllers

import com.era.backend.exceptions.FieldError
import com.era.backend.exceptions.ValidationException
import com.era.backend.models.SesionPrincipal
import com.era.backend.models.dto.ProgresoSyncRequestDto
import com.era.backend.models.dto.ProgresoSyncResponseDto
import com.era.backend.models.entities.EstadoNivel
import com.era.backend.services.ProgressSyncService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond

/**
 * Handler de los endpoints de sincronización de progreso (Módulo G: `GET`/`POST
 * /api/v1/progress/sync`). Protegido por el proveedor `session-jwt`
 * (`plugins/AuthenticationConfig.kt`): la identidad llega como [SesionPrincipal], nunca como
 * parámetro del cliente.
 *
 * Valida la forma del input (primera barrera, §5.1 de `modulo-g-analisis.md`) y delega las
 * reglas de negocio en [ProgressSyncService] (merge, integridad contra el catálogo §5.2,
 * atomicidad §6, resumen §7). No decide políticas de negocio.
 */
class ProgressController(
    private val progressSyncService: ProgressSyncService,
) {

    /**
     * Endpoint `GET /api/v1/progress/sync` (§4.1).
     *
     * Sin body que validar: la identidad proviene del token de sesión. El service verifica
     * el estado de la cuenta (403 `ACCOUNT_INACTIVE`, §8), construye el snapshot con el
     * resumen agregado (§7) y responde 200.
     */
    suspend fun getSync(call: ApplicationCall): Unit {
        val sesion = call.principal<SesionPrincipal>()
            ?: throw IllegalStateException("Sesión no resuelta en ruta autenticada.")
        val respuesta: ProgresoSyncResponseDto = progressSyncService.obtenerSnapshot(sesion.idUsuario)
        call.respond(HttpStatusCode.OK, respuesta)
    }

    /**
     * Endpoint `POST /api/v1/progress/sync` (§4.2).
     *
     * Validaciones de forma (§5.1), antes de tocar la base de datos: `progreso` presente;
     * por ítem `orden` entero en `1..20`, `estadoNivel` ∈ enum, contadores ≥ 0; sin `orden`
     * duplicado. La integridad contra el catálogo (§5.2), el merge (§3) y la atomicidad (§6)
     * son del service.
     *
     * Respuestas (mapeadas por StatusPages): 200 con el snapshot mergeado · 400
     * `VALIDATION_ERROR` · 401 challenge del proveedor · 403 `ACCOUNT_INACTIVE`.
     */
    suspend fun postSync(call: ApplicationCall): Unit {
        val sesion = call.principal<SesionPrincipal>()
            ?: throw IllegalStateException("Sesión no resuelta en ruta autenticada.")
        val request = call.receive<ProgresoSyncRequestDto>()

        val errores = mutableListOf<FieldError>()
        val items = request.progreso
        if (items == null) {
            errores += FieldError("progreso", "Es obligatorio.")
        } else {
            if (items.map { it.orden }.size != items.map { it.orden }.distinct().size) {
                errores += FieldError("progreso", "No se permiten órdenes duplicados.")
            }
            items.forEachIndexed { indice, item ->
                if (item.orden !in 1..ProgressSyncService.TOTAL_NIVELES) {
                    errores += FieldError("progreso[$indice].orden", "Debe estar entre 1 y 20.")
                }
                if (EstadoNivel.fromValor(item.estadoNivel) == null) {
                    errores += FieldError("progreso[$indice].estadoNivel", "Valor inválido.")
                }
                if (item.intentosTotales < 0) {
                    errores += FieldError("progreso[$indice].intentosTotales", "No puede ser negativo.")
                }
                if (item.intentosFallidosConsecutivos < 0) {
                    errores += FieldError(
                        "progreso[$indice].intentosFallidosConsecutivos",
                        "No puede ser negativo.",
                    )
                }
            }
        }

        if (errores.isNotEmpty()) {
            throw ValidationException("Datos de sincronización inválidos.", errores)
        }

        // Tras el chequeo de forma, `progreso` no puede ser null (cubierto por `errores`);
        // el `?:` es solo para satisfacer el smart-cast del compilador.
        val itemsValidos = items ?: throw ValidationException(
            "Datos de sincronización inválidos.",
            listOf(FieldError("progreso", "Es obligatorio.")),
        )

        val respuesta: ProgresoSyncResponseDto =
            progressSyncService.sincronizar(sesion.idUsuario, itemsValidos)
        call.respond(HttpStatusCode.OK, respuesta)
    }
}
