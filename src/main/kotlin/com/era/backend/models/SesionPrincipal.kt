package com.era.backend.models

/**
 * Principal de sesión de los Módulos D/E (consulta de perfil y eliminación de cuenta),
 * producido por el proveedor `session-jwt` (`plugins/AuthenticationConfig.kt`,
 * `modulo-d-analisis.md` §2).
 *
 * Mínimo privilegio (CLAUDE.md §6): porta **solo** el `id_usuario`. No lleva correo,
 * username ni claims; el perfil se lee de la BD en cada petición (un token obsoleto no es
 * fuente de verdad) y el `sub` del JWT se traduce a `Long` en el `validate` del plugin
 * (`null` si no es numérico → rechazo de autenticación).
 *
 * No implementa la interfaz marcadora `Principal` de Ktor: está deprecada en 3.4.x
 * ("can be safely removed") y `validate`/`principal<T>()` tipan por cast genérico.
 */
data class SesionPrincipal(
    val idUsuario: Long,
)
