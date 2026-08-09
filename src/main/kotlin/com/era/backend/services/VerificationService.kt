package com.era.backend.services

import com.era.backend.exceptions.EmailAlreadyVerifiedException
import com.era.backend.exceptions.NotFoundException
import com.era.backend.exceptions.OtpInvalidException
import com.era.backend.exceptions.OtpResendThrottledException
import com.era.backend.models.dto.ResendOtpResponseDto
import com.era.backend.models.dto.VerifyEmailResponseDto
import com.era.backend.models.entities.AcudienteRow
import com.era.backend.models.entities.ConfiguracionRow
import com.era.backend.models.entities.EstadoUsuario
import com.era.backend.models.entities.UsuarioRow
import com.era.backend.repositories.AcudienteRepository
import com.era.backend.repositories.ConfiguracionRepository
import com.era.backend.repositories.RegistroPendienteRepository
import com.era.backend.repositories.TransactionRunner
import com.era.backend.repositories.UsuarioRepository
import java.time.Duration
import java.time.LocalDateTime

/**
 * Reglas de negocio del Módulo A.1 — verificación de correo y reenvío de OTP
 * (REQ-FUN-01 paso 3, CU-11, HU-01 CA3, HU-15 CA2). Puro de Ktor y de SQL: recibe DTOs,
 * lanza excepciones de dominio y delega el acceso a datos en los repositorios
 * (ARQUITECTURA_BASE.md §2.3).
 *
 * Políticas aplicadas:
 * - **P1** (3 intentos fallidos → código invalidado): vive en [OtpService.verificar]
 *   (reutilizable con el Módulo C); aquí solo se persiste el contador de fallos.
 * - **P2** (mín. 60 s entre reenvíos → 429 `OTP_RESEND_THROTTLED`): comparación contra
 *   `registro_pendiente.ultimo_envio_en` (migración V2/P3).
 */
class VerificationService(
    private val registroRepository: RegistroPendienteRepository,
    private val usuarioRepository: UsuarioRepository,
    private val acudienteRepository: AcudienteRepository,
    private val configuracionRepository: ConfiguracionRepository,
    private val otpService: OtpService,
    private val transactionRunner: TransactionRunner,
) {

    companion object {
        private const val MENSAJE_VERIFICADO = "Correo verificado. Cuenta activada."
        private const val MENSAJE_INVALIDO = "El código de verificación es inválido o ha expirado."
        private const val MENSAJE_REENVIO = "Código de verificación enviado al correo."
        private const val VIGENCIA_OTP_MINUTOS = 10L
        private const val THROTTLE_REENVIO_SEGUNDOS = 60L
    }

    private enum class ResultadoVerificacion {
        VERIFICADO,
        OTP_INVALIDO,
        YA_VERIFICADO,
        SIN_PENDIENTE,
    }

    /**
     * Verifica el OTP de 6 dígitos y, si es correcto, convierte el registro pendiente en
     * la cuenta activa del menor (CU-11, HU-15 CA2).
     *
     * **Conversión transaccional (obligatorio, V1:55-57):** dentro de un único
     * [TransactionRunner] se lee el pendiente con lock de escritura (`FOR UPDATE`,
     * anti-TOCTOU), y al validar el código se insertan `usuario` + `acudiente` +
     * `configuracion` y se consume el pendiente. Todo atómico: si algo falla a mitad,
     * no queda ninguna fila a medias.
     *
     * Sobre los fallos del código (P1): el contador `intentos_fallidos` se persiste
     * DENTRO de la transacción y la excepción se lanza FUERA, para que el incremento se
     * commitee (un throw interno haría rollback de todo el bloque). Al llegar a 3,
     * [OtpService.verificar] rechaza cualquier verificación posterior: el código queda
     * permanentemente invalidado hasta un reenvío (P2).
     *
     * Respuestas:
     * - 200 con mensaje de éxito tras la conversión.
     * - 401 `OTP_INVALID_OR_EXPIRED` genérico si el código es incorrecto, está vencido o
     *   se superó el límite de intentos (no se revela la causa).
     * - 409 `EMAIL_ALREADY_VERIFIED` si no hay pendiente pero el usuario ya existe
     *   (decisión del propietario).
     * - 404 genérico si no hay pendiente ni usuario.
     */
    fun verificarEmail(correo: String, codigo: String): VerifyEmailResponseDto {
        var resultado = ResultadoVerificacion.SIN_PENDIENTE

        transactionRunner.run {
            val pendiente = registroRepository.findByEmailForUpdate(correo)
            if (pendiente == null) {
                // El pendiente ya fue consumido (verificado) o nunca existió. Solo las
                // lecturas van en la transacción; el throw se decide fuera.
                resultado =
                    if (usuarioRepository.findByEmail(correo) != null) {
                        ResultadoVerificacion.YA_VERIFICADO
                    } else {
                        ResultadoVerificacion.SIN_PENDIENTE
                    }
                return@run
            }

            try {
                otpService.verificar(codigo, pendiente.codigoHash, pendiente.intentosFallidos, pendiente.expiraEn)

                // Conversión atómica: usuario + acudiente + configuracion, y se consume el pendiente.
                val idUsuario =
                    usuarioRepository.insert(
                        UsuarioRow(
                            idUsuario = 0L, // lo asigna la BD
                            nombreMenor = pendiente.nombreMenor,
                            fechaNacimiento = pendiente.fechaNacimiento,
                            correo = pendiente.correo,
                            nombreUsuario = pendiente.nombreUsuario,
                            contrasenaHash = pendiente.contrasenaHash, // hash bcrypt ya calculado en el registro
                            avatar = pendiente.avatar,
                            intentosLoginFallidos = 0,
                            bloqueadoHasta = null,
                            estado = EstadoUsuario.ACTIVO,
                            creadoEn = LocalDateTime.now(),
                            actualizadoEn = LocalDateTime.now(),
                        ),
                    )
                acudienteRepository.insert(
                    AcudienteRow(
                        idAcudiente = 0L,
                        idUsuario = idUsuario,
                        nombreCompleto = pendiente.nombreAcudiente,
                        numeroCedula = pendiente.cedulaAcudiente,
                        creadoEn = LocalDateTime.now(),
                        actualizadoEn = LocalDateTime.now(),
                    ),
                )
                configuracionRepository.insert(
                    ConfiguracionRow(
                        idConfig = 0L,
                        idUsuario = idUsuario,
                        sonido = true,
                        musica = true,
                        temaVisual = "claro",
                        tamanoTexto = "mediano",
                        actualizadoEn = LocalDateTime.now(),
                    ),
                )
                registroRepository.deleteById(pendiente.idRegistro)
                resultado = ResultadoVerificacion.VERIFICADO
            } catch (e: OtpInvalidException) {
                // Se persiste el fallo (P1) sin re-lanzar: la transacción commitea el
                // incremento; la excepción genérica se lanza tras el bloque. Una vez
                // alcanzado el límite (P1), los intentos posteriores NO incrementan más
                // el contador: el código ya quedó permanentemente invalidado.
                registroRepository.actualizarIntentosFallidos(
                    pendiente.idRegistro,
                    minOf(pendiente.intentosFallidos + 1, OtpService.MAX_INTENTOS_FALLIDOS),
                )
                resultado = ResultadoVerificacion.OTP_INVALIDO
            }
        }

        return when (resultado) {
            ResultadoVerificacion.VERIFICADO -> VerifyEmailResponseDto(MENSAJE_VERIFICADO)
            ResultadoVerificacion.OTP_INVALIDO -> throw OtpInvalidException(MENSAJE_INVALIDO)
            ResultadoVerificacion.YA_VERIFICADO ->
                throw EmailAlreadyVerifiedException("El correo ya fue verificado.")
            ResultadoVerificacion.SIN_PENDIENTE ->
                throw NotFoundException("No hay un registro pendiente para este correo.")
        }
    }

    /**
     * Reenvía el OTP de registro (P2, CU-11 flujo alternativo): emite un código nuevo,
     * invalida el anterior y responde 200 con el mismo mensaje de éxito del envío inicial.
     *
     * **Throttle (P2):** si el último envío (`ultimo_envio_en`, V2) fue hace menos de
     * 60 s, responde 429 `OTP_RESEND_THROTTLED` sin tocar nada.
     *
     * **Anti-enumeración (decisión del propietario):** si no existe un pendiente para el
     * correo, se responde 200 con el mensaje genérico de éxito y NO se envía nada. Así el
     * endpoint no confirma si un correo está en proceso de registro.
     *
     * El envío SMTP ocurre FUERA de la transacción (misma regla que `register`): no se
     * mantiene la conexión abierta durante la latencia del correo.
     */
    fun reenviarOtp(correo: String): ResendOtpResponseDto {
        val code = otpService.generate()
        var destino: String? = null

        transactionRunner.run {
            val pendiente = registroRepository.findByEmail(correo) ?: return@run

            val ahora = LocalDateTime.now()
            val ultimoEnvio = pendiente.ultimoEnvioEn
            if (ultimoEnvio != null && Duration.between(ultimoEnvio, ahora).seconds < THROTTLE_REENVIO_SEGUNDOS) {
                throw OtpResendThrottledException("Reintenta el envío en unos segundos.")
            }

            registroRepository.actualizarCodigoReenvio(
                idRegistro = pendiente.idRegistro,
                codigoHash = otpService.hash(code),
                expiraEn = ahora.plusMinutes(VIGENCIA_OTP_MINUTOS),
                ahora = ahora,
            )
            destino = pendiente.correo
        }

        if (destino != null) {
            otpService.send(destino, code)
        }
        return ResendOtpResponseDto(MENSAJE_REENVIO)
    }
}
