package com.era.backend.routes

import com.era.backend.controllers.AvatarController
import com.era.backend.controllers.UsuarioController
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * Declaración del contrato de usuario autenticado (Módulos D, E e I, ARQUITECTURA_BASE.md
 * §2.1). Solo path + verbo + delegación al controller; sin validaciones, negocio ni SQL.
 *
 * Todo el bloque vive dentro de `authenticate("session-jwt")`: sin un token de sesión
 * válido (`plugins/AuthenticationConfig.kt`) la petición no llega al controller y el
 * `challenge` responde 401 `UNAUTHORIZED` (D-6). La subida y el servido de la foto
 * personalizada (Módulo I) quedan por construcción bajo esta misma barrera: sin URL pública,
 * `modulo-i-analisis.md` §5.
 */
fun Route.userRoutes(
    usuarioController: UsuarioController,
    avatarController: AvatarController,
) {

    /**
     * `GET /api/v1/users/me` — consulta del perfil del usuario autenticado
     * (Módulo D, REQ-FUN-06, CU-06, HU-06).
     *
     * `PATCH /api/v1/users/me` — actualización del nombre de usuario (Módulo D, REQ-FUN-06
     * CA5, CU-06, HU-06). Único campo editable junto al avatar; el resto se ignora.
     *
     * `DELETE /api/v1/users/me` — eliminación de la propia cuenta por soft delete con
     * reverificación de contraseña (Módulo E, REQ-FUN-05, CU-07, HU-05).
     *
     * `PUT /api/v1/users/me/avatar` — sube/reemplaza la foto personalizada (Módulo I,
     * REQ-FUN-06 CA4, CU-06 3a).
     *
     * `GET /api/v1/users/me/avatar` — sirve el binario de la foto personalizada
     * (Módulo I, CU-06; 404 si no hay foto `custom:*`).
     */
    route("/api/v1/users") {
        authenticate("session-jwt") {
            get("/me") { usuarioController.obtenerPerfil(call) }
            patch("/me") { usuarioController.actualizarPerfil(call) }
            delete("/me") { usuarioController.eliminarCuenta(call) }
            put("/me/avatar") { avatarController.subirAvatar(call) }
            get("/me/avatar") { avatarController.obtenerAvatar(call) }
        }
    }
}
