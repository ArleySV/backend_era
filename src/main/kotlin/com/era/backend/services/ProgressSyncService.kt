package com.era.backend.services

import com.era.backend.exceptions.AccountInactiveException
import com.era.backend.exceptions.FieldError
import com.era.backend.exceptions.NotFoundException
import com.era.backend.exceptions.ValidationException
import com.era.backend.models.dto.NivelProgresoDto
import com.era.backend.models.dto.ProgresoSyncItemDto
import com.era.backend.models.dto.ProgresoSyncResponseDto
import com.era.backend.models.dto.ResumenProgresoDto
import com.era.backend.models.entities.EstadoNivel
import com.era.backend.models.entities.EstadoUsuario
import com.era.backend.models.entities.ProgresoUsuarioRow
import com.era.backend.repositories.NivelRepository
import com.era.backend.repositories.ProgresoRepository
import com.era.backend.repositories.TransactionRunner
import com.era.backend.repositories.UsuarioRepository
import java.time.LocalDateTime

/**
 * Reglas de negocio del Módulo G (Sincronización de progreso, REQ-FUN-10/11/12, CU-08,
 * CU-12, HU-10/11/12 — `modulo-g-analisis.md`). Puro de Ktor y de SQL: recibe DTOs, lanza
 * excepciones de dominio y delega el acceso a datos en [ProgresoRepository] y
 * [NivelRepository] (ARQUITECTURA_BASE.md §2.3).
 *
 * Reglas centrales:
 * - **Merge hacia adelante (§3.4):** el estado de la fuente de verdad jamás retrocede
 *   (`max` por precedencia `bloqueado < disponible < completado`); los contadores solo crecen
 *   (`max` cliente/servidor). No se valida la cadena de desbloqueo (lenient, §5.3).
 * - **Atomicidad (§6):** el POST procesa el lote completo dentro de UNA transacción de
 *   `TransactionRunner`; cualquier fallo (cuenta inactiva, `orden` inexistente, error de BD)
 *   revierte todo y no deja estados parciales.
 * - **Integridad contra el catálogo (§5.2):** antes de escribir se valida que todo `orden`
 *   recibido exista en `nivel`; si falta alguno → 400 `VALIDATION_ERROR` con **cero** escrituras.
 * - **Resumen en el servidor (§7):** `totalReintentos = SUM(intentos_totales)`,
 *   `nivelesCompletados = COUNT(completado)` — el cliente nunca suma.
 * - **Mínimo privilegio (§8):** la respuesta no expone `id_usuario`, `id_nivel` ni
 *   `id_progreso`; cero logs de datos personales (CLAUDE.md §6).
 */
class ProgressSyncService(
    private val usuarioRepository: UsuarioRepository,
    private val nivelRepository: NivelRepository,
    private val progresoRepository: ProgresoRepository,
    private val transactionRunner: TransactionRunner,
) {

    companion object {
        /** Número de niveles del catálogo (REQ-FUN-10, `nivel.orden` 1..20, V1). */
        const val TOTAL_NIVELES = 20

        /** Mensaje de cuenta en soft delete (G §8): idéntico al del Módulo D. */
        private const val MENSAJE_CUENTA_INACTIVA = "La cuenta no está activa."

        /** Mensaje de nivel inexistente en el catálogo (G §5.2). */
        private const val MENSAJE_NIVEL_INEXISTENTE = "Nivel inexistente en el catálogo."
    }

    /**
     * Snapshot autoritativo del progreso del usuario (GET, `modulo-g-analisis.md` §4.1).
     *
     * Respuestas: 200 con `ProgresoSyncResponseDto` · 403 `ACCOUNT_INACTIVE` · 404
     * `NOT_FOUND` (defensivo: token válido pero fila inexistente). Lectura en una
     * transacción (Exposed exige contexto transaccional incluso para `SELECT`); el
     * snapshot se captura en `var` y se consume fuera (patrón de `UsuarioService`).
     */
    fun obtenerSnapshot(idUsuario: Long): ProgresoSyncResponseDto {
        var snapshot: ProgresoSyncResponseDto? = null
        transactionRunner.run {
            verificarCuentaActiva(idUsuario)
            snapshot = construirSnapshot(idUsuario)
        }
        return snapshot ?: throw IllegalStateException("Snapshot no generado en transacción.")
    }

    /**
     * Mergea hacia adelante, persiste y devuelve el snapshot resultante (POST,
     * `modulo-g-analisis.md` §4.2) en un **único round-trip** (CU-12 paso 3).
     *
     * Orden dentro de la transacción (§6): verificar cuenta activa → validar catálogo de
     * todo el lote (§5.2, **antes** de escribir) → merge/upsert por nivel (§3) → snapshot.
     *
     * Respuestas: 200 con el snapshot mergeado · 400 `VALIDATION_ERROR` (integridad §5.2,
     * con rollback total) · 403 `ACCOUNT_INACTIVE` · 404 defensivo.
     */
    fun sincronizar(idUsuario: Long, items: List<ProgresoSyncItemDto>): ProgresoSyncResponseDto {
        var snapshot: ProgresoSyncResponseDto? = null
        transactionRunner.run {
            verificarCuentaActiva(idUsuario)

            // §5.2 — validación de catálogo de TODO el lote antes de escribir nada:
            // si un `orden` no existe, la sincronización no persiste ningún nivel.
            validarCatalogo(items)

            for (item in items) {
                // Invariante: `validarCatalogo` garantizó la existencia del `orden`; si el
                // literal de estado fuera inválido (defensa en profundidad, el controller ya
                // validó la forma §5.1) el throw aborta la transacción → rollback total (§6).
                val idNivel = nivelRepository.findByIdOrden(item.orden)
                    ?: throw IllegalStateException("Orden validado sin nivel en catálogo.")
                val estadoCliente = EstadoNivel.fromValor(item.estadoNivel)
                    ?: throw ValidationException(
                        "Estado de nivel inválido.",
                        listOf(FieldError("progreso.estadoNivel", "Valor inválido.")),
                    )
                val existente = progresoRepository.findByIdUsuarioYNivel(idUsuario, idNivel)
                if (existente == null) {
                    insertarNivelNuevo(idUsuario, idNivel, estadoCliente, item)
                } else {
                    actualizarNivelExistente(existente, estadoCliente, item)
                }
            }

            snapshot = construirSnapshot(idUsuario)
        }
        return snapshot ?: throw IllegalStateException("Snapshot no generado en transacción.")
    }

    /** 403 si la cuenta está en soft delete (G §8, REQ-FUN-05 CA5); 404 defensivo. */
    private fun verificarCuentaActiva(idUsuario: Long) {
        val usuario = usuarioRepository.findById(idUsuario)
            ?: throw NotFoundException("Usuario no encontrado.")
        if (usuario.estado != EstadoUsuario.ACTIVO) {
            throw AccountInactiveException(MENSAJE_CUENTA_INACTIVA)
        }
    }

    /** 400 `VALIDATION_ERROR` si algún `orden` recibido no existe en `nivel` (§5.2). */
    private fun validarCatalogo(items: List<ProgresoSyncItemDto>) {
        val ordenes = items.map { it.orden }.toSet()
        val existentes = nivelRepository.ordenesExistentes(ordenes)
        val faltantes = ordenes - existentes
        if (faltantes.isNotEmpty()) {
            throw ValidationException(
                MENSAJE_NIVEL_INEXISTENTE,
                faltantes.sorted().map { orden ->
                    FieldError("progreso.orden", "El nivel $orden no existe en el catálogo.")
                },
            )
        }
    }

    /**
     * Alta de una fila de progreso (nivel sin estado previo). `completado_en` lo fija el
     * servidor una sola vez (§4.4) solo si el estado resultante es `completado`.
     */
    private fun insertarNivelNuevo(
        idUsuario: Long,
        idNivel: Long,
        estado: EstadoNivel,
        item: ProgresoSyncItemDto,
    ) {
        val completadoEn = if (estado == EstadoNivel.COMPLETADO) LocalDateTime.now() else null
        progresoRepository.insertar(
            ProgresoUsuarioRow(
                idProgreso = 0,
                idUsuario = idUsuario,
                idNivel = idNivel,
                estadoNivel = estado,
                intentosTotales = item.intentosTotales,
                intentosFallidosConsecutivos = item.intentosFallidosConsecutivos,
                pausaActiva = false,
                pausaHasta = null,
                completadoEn = completadoEn,
                ultimaInteraccion = LocalDateTime.now(),
            ),
        )
    }

    /**
     * Actualización por merge hacia adelante (§3.4): estado = max por precedencia, contadores
     * = max cliente/servidor. `completadoEn` jamás se resetea; se fija solo en la transición
     * a `completado` y si aún no tenía valor (§4.4).
     */
    private fun actualizarNivelExistente(
        existente: ProgresoUsuarioRow,
        estadoCliente: EstadoNivel,
        item: ProgresoSyncItemDto,
    ) {
        val estado = EstadoNivel.maxDe(existente.estadoNivel, estadoCliente)
        val completadoEn =
            existente.completadoEn
                ?: if (estado == EstadoNivel.COMPLETADO) LocalDateTime.now() else null
        progresoRepository.actualizar(
            idProgreso = existente.idProgreso,
            estadoNivel = estado,
            intentosTotales = maxOf(existente.intentosTotales, item.intentosTotales),
            intentosFallidosConsecutivos =
                maxOf(existente.intentosFallidosConsecutivos, item.intentosFallidosConsecutivos),
            completadoEn = completadoEn,
        )
    }

    /**
     * Snapshot de respuesta (§4.1): una entrada por nivel con actividad (niveles sin fila se
     * omiten) + `resumen` agregado por el servidor (§7). El wire usa `orden`, nunca la PK
     * interna (mínimo privilegio §8).
     */
    private fun construirSnapshot(idUsuario: Long): ProgresoSyncResponseDto {
        val filas = progresoRepository.findByIdUsuario(idUsuario)
        val ordenPorId = nivelRepository.findOrdenesByIdNiveles(filas.map { it.idNivel })
        val progreso =
            filas.map { fila ->
                NivelProgresoDto(
                    orden = ordenPorId[fila.idNivel]
                        ?: throw IllegalStateException("Nivel de progreso sin catálogo."),
                    estadoNivel = fila.estadoNivel.valor,
                    intentosTotales = fila.intentosTotales,
                    completadoEn = fila.completadoEn?.toString(),
                    ultimaInteraccion = fila.ultimaInteraccion.toString(),
                )
            }
        val resumen =
            ResumenProgresoDto(
                nivelesCompletados = progresoRepository.contarCompletados(idUsuario),
                totalNiveles = TOTAL_NIVELES,
                totalReintentos = progresoRepository.sumarIntentosTotales(idUsuario),
            )
        return ProgresoSyncResponseDto(progreso, resumen)
    }
}
