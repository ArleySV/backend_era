package com.era.backend.repositories

import com.era.backend.models.entities.CodigoVerificacionRow
import java.time.LocalDateTime

/**
 * Acceso a datos de `codigo_verificacion` (Módulo C, ARQUITECTURA_BASE.md §2.4).
 *
 * Es una **interfaz** para que `PasswordResetService` (y los tests) dependan de la
 * abstracción, no de Exposed: en producción se inyecta
 * [ExposedCodigoVerificacionRepository] y en tests un `FakeCodigoVerificacionRepository`
 * en memoria (sin MySQL). El service nunca ve queries ni columnas.
 *
 * En producción, los métodos deben ejecutarse dentro de una transacción (la provee
 * `PasswordResetService` vía `TransactionRunner`) para que cada paso del reseteo sea
 * atómico y las lecturas con lock cubran las escrituras posteriores.
 */
interface CodigoVerificacionRepository {

    /**
     * Persiste un código OTP de recuperación con hash bcrypt, `intentos_fallidos = 0`,
     * `expira_en = now + 10 min`, `ultimo_envio_en = ahora` y `usado = false`
     * (REQ-FUN-07; Módulo C paso 1).
     *
     * Seguridad (CLAUDE.md §6): nunca loguear [CodigoVerificacionRow.codigoHash].
     *
     * @param row fila a insertar; `idCodigo` y `creadoEn` los asigna la base de datos.
     * @return id del código creado.
     */
    fun insert(row: CodigoVerificacionRow): Long

    /**
     * Busca el **último** código del usuario tomando un **lock de escritura**
     * (`SELECT ... FOR UPDATE`, ordenado por `id_codigo DESC` limitado a 1). Es la barrera
     * anti-TOCTOU del flujo de reseteo (C-3): serializa lecturas concurrentes del mismo
     * usuario para que solo una verifique/reenvíe sobre el código vigente y las demás
     * re-lean el estado real (usado/consumido) tras el lock.
     *
     * Debe ejecutarse dentro de la misma transacción que escribe el código.
     */
    fun findUltimoPorUsuarioForUpdate(idUsuario: Long): CodigoVerificacionRow?

    /**
     * Reenvío de OTP (P2): emite un código nuevo (hash bcrypt), reinicia `expira_en` a
     * `now + 10 min`, pone `intentos_fallidos` en 0 (el código anterior queda invalidado)
     * y registra [ahora] como último envío (`ultimo_envio_en`) para el throttle de 60 s.
     * Espejo de `RegistroPendienteRepository.actualizarCodigoReenvio` (V2).
     */
    fun actualizarEnvio(
        idCodigo: Long,
        codigoHash: String,
        expiraEn: LocalDateTime,
        ahora: LocalDateTime,
    )

    /**
     * Persiste el contador de intentos fallidos de verificación (P1). Se usa al fallar la
     * verificación del OTP para registrar el fallo **antes** de responder; el throw de la
     * excepción de dominio ocurre fuera de la transacción para que este incremento se
     * commitee (un throw interno haría rollback de todo el bloque).
     */
    fun actualizarIntentosFallidos(idCodigo: Long, nuevosIntentos: Int)

    /**
     * Marca el código como usado (single-use del OTP) tras una verificación exitosa
     * (Módulo C paso 2). Solo se invoca dentro de la transacción de verificación.
     */
    fun marcarUsado(idCodigo: Long)
}
