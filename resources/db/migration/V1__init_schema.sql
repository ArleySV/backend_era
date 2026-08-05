-- ============================================================================
-- ERA — Migración V1: esquema inicial
-- Motor: MySQL 8.0 · Migrador: Flyway (resources/db/migration/V1__init_schema.sql)
-- Base: modelo .mwb del equipo, ajustado en revisión conjunta (ver historial de
-- decisiones en el chat: sin sesión server-side, registro_pendiente, PK INT,
-- FK RESTRICT hacia usuario, OTP hasheado, límite de intentos en OTP).
--
-- Convenciones:
--   - InnoDB + utf8mb4 en todas las tablas.
--   - Ningún borrado físico de usuario/datos del menor (soft delete: usuario.estado).
--   - FK que cuelgan de `usuario` (directa o transitivamente): RESTRICT/CASCADE(update).
--   - FK del catálogo de trivia (contenido, no datos de un menor): CASCADE.
--   - Catálogo de trivia SÍ se incluye en esta migración (ya estaba en el .mwb
--     del equipo), a diferencia de mi borrador anterior que lo dejaba para el
--     módulo G.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- usuario
-- ----------------------------------------------------------------------------
CREATE TABLE usuario (
    id_usuario              INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    nombre_menor            VARCHAR(120) NOT NULL,
    fecha_nacimiento        DATE NOT NULL,
    correo                  VARCHAR(255) NOT NULL,
    nombre_usuario          VARCHAR(60)  NOT NULL,
    contrasena_hash         VARCHAR(255) NOT NULL,
    avatar                  VARCHAR(255) NULL,
    intentos_login_fallidos TINYINT UNSIGNED NOT NULL DEFAULT 0,
    bloqueado_hasta         DATETIME NULL,
    estado                  ENUM('activo','eliminado') NOT NULL DEFAULT 'activo',
    creado_en               DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actualizado_en          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uq_usuario_correo UNIQUE (correo),
    CONSTRAINT uq_usuario_nombre_usuario UNIQUE (nombre_usuario)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------------------------------------------------------
-- acudiente  (1:1 con usuario)
-- ----------------------------------------------------------------------------
CREATE TABLE acudiente (
    id_acudiente     INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    id_usuario       INT UNSIGNED NOT NULL,
    nombre_completo  VARCHAR(120) NOT NULL,
    numero_cedula    VARCHAR(20)  NOT NULL,
    creado_en        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actualizado_en   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uq_acudiente_usuario UNIQUE (id_usuario),
    CONSTRAINT fk_acudiente_usuario
        FOREIGN KEY (id_usuario) REFERENCES usuario(id_usuario)
        ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------------------------------------------------------
-- registro_pendiente — datos de registro mientras el correo no está verificado.
-- Ninguna fila de `usuario` existe todavía; al verificar el código, el service
-- crea usuario + acudiente en una transacción y borra/consume esta fila.
-- ----------------------------------------------------------------------------
CREATE TABLE registro_pendiente (
    id_registro        INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    correo             VARCHAR(255) NOT NULL,
    nombre_usuario     VARCHAR(60)  NOT NULL,
    contrasena_hash    VARCHAR(255) NOT NULL,
    nombre_menor       VARCHAR(120) NOT NULL,
    fecha_nacimiento   DATE NOT NULL,
    nombre_acudiente   VARCHAR(120) NOT NULL,
    cedula_acudiente   VARCHAR(20)  NOT NULL,
    avatar             VARCHAR(255) NULL,
    codigo_hash        VARCHAR(255) NOT NULL,
    intentos_fallidos  TINYINT UNSIGNED NOT NULL DEFAULT 0,
    expira_en          DATETIME NOT NULL,
    creado_en          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_registro_pendiente_correo UNIQUE (correo),
    CONSTRAINT uq_registro_pendiente_nombre_usuario UNIQUE (nombre_usuario)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------------------------------------------------------
-- codigo_verificacion — exclusivo de recuperación de contraseña (Módulo C).
-- La verificación de correo en el registro usa registro_pendiente.codigo_hash.
-- ----------------------------------------------------------------------------
CREATE TABLE codigo_verificacion (
    id_codigo          INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    id_usuario         INT UNSIGNED NOT NULL,
    codigo_hash        VARCHAR(255) NOT NULL,      -- hash bcrypt del código de 6 dígitos
    intentos_fallidos  TINYINT UNSIGNED NOT NULL DEFAULT 0,
    expira_en          DATETIME NOT NULL,
    usado              TINYINT(1) NOT NULL DEFAULT 0,
    creado_en          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_codigo_usuario
        FOREIGN KEY (id_usuario) REFERENCES usuario(id_usuario)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    INDEX idx_codigo_usuario_usado (id_usuario, usado)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------------------------------------------------------
-- tokens_reseteo — jti del token puente emitido por password-reset/verify,
-- consumido por password-reset/confirm (ver ARQUITECTURA_BASE.md §2.3).
-- ----------------------------------------------------------------------------
CREATE TABLE tokens_reseteo (
    id_token    INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    jti         VARCHAR(64) NOT NULL,
    id_usuario  INT UNSIGNED NOT NULL,
    expira_en   DATETIME NOT NULL,
    consumido   TINYINT(1) NOT NULL DEFAULT 0,
    creado_en   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_tokens_reseteo_jti UNIQUE (jti),
    CONSTRAINT fk_tokens_reseteo_usuario
        FOREIGN KEY (id_usuario) REFERENCES usuario(id_usuario)
        ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------------------------------------------------------
-- configuracion  (1:1 con usuario)
-- ----------------------------------------------------------------------------
CREATE TABLE configuracion (
    id_config       INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    id_usuario      INT UNSIGNED NOT NULL,
    sonido          TINYINT(1) NOT NULL DEFAULT 1,
    musica          TINYINT(1) NOT NULL DEFAULT 1,
    tema_visual     ENUM('claro','oscuro') NOT NULL DEFAULT 'claro',
    tamano_texto    ENUM('pequeno','mediano','grande') NOT NULL DEFAULT 'mediano',
    actualizado_en  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uq_config_usuario UNIQUE (id_usuario),
    CONSTRAINT fk_configuracion_usuario
        FOREIGN KEY (id_usuario) REFERENCES usuario(id_usuario)
        ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------------------------------------------------------
-- comentario
-- ----------------------------------------------------------------------------
CREATE TABLE comentario (
    id_comentario  INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    id_usuario     INT UNSIGNED NOT NULL,
    contenido      TEXT NOT NULL,
    enviado_en     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_comentario_usuario
        FOREIGN KEY (id_usuario) REFERENCES usuario(id_usuario)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    INDEX idx_comentario_usuario (id_usuario)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------------------------------------------------------
-- nivel  (catálogo de trivia)
-- ----------------------------------------------------------------------------
CREATE TABLE nivel (
    id_nivel  INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    titulo    VARCHAR(120) NOT NULL,
    orden     TINYINT UNSIGNED NOT NULL,
    CONSTRAINT uq_nivel_orden UNIQUE (orden),
    CONSTRAINT ck_nivel_orden CHECK (orden BETWEEN 1 AND 20)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------------------------------------------------------
-- pregunta  (exactamente 1 por nivel — REQ-FUN-10 CA4)
-- ----------------------------------------------------------------------------
CREATE TABLE pregunta (
    id_pregunta  INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    id_nivel     INT UNSIGNED NOT NULL,
    enunciado    TEXT NOT NULL,
    imagen_url   VARCHAR(255) NULL,
    CONSTRAINT uq_pregunta_nivel UNIQUE (id_nivel),
    CONSTRAINT fk_pregunta_nivel
        FOREIGN KEY (id_nivel) REFERENCES nivel(id_nivel)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------------------------------------------------------
-- opcion_respuesta
-- ----------------------------------------------------------------------------
CREATE TABLE opcion_respuesta (
    id_opcion      INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    id_pregunta    INT UNSIGNED NOT NULL,
    texto_opcion   VARCHAR(255) NOT NULL,
    es_correcta    TINYINT(1) NOT NULL DEFAULT 0,
    CONSTRAINT fk_opcion_pregunta
        FOREIGN KEY (id_pregunta) REFERENCES pregunta(id_pregunta)
        ON DELETE CASCADE ON UPDATE CASCADE,
    INDEX idx_opcion_pregunta (id_pregunta)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------------------------------------------------------
-- progreso_usuario
-- ----------------------------------------------------------------------------
CREATE TABLE progreso_usuario (
    id_progreso                     INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    id_usuario                      INT UNSIGNED NOT NULL,
    id_nivel                        INT UNSIGNED NOT NULL,
    estado_nivel                    ENUM('bloqueado','disponible','completado') NOT NULL DEFAULT 'bloqueado',
    intentos_totales                INT UNSIGNED NOT NULL DEFAULT 0,
    intentos_fallidos_consecutivos  TINYINT UNSIGNED NOT NULL DEFAULT 0,
    pausa_activa                    TINYINT(1) NOT NULL DEFAULT 0,
    pausa_hasta                     DATETIME NULL,
    completado_en                   DATETIME NULL,
    ultima_interaccion              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uq_progreso_usuario_nivel UNIQUE (id_usuario, id_nivel),
    CONSTRAINT fk_progreso_usuario
        FOREIGN KEY (id_usuario) REFERENCES usuario(id_usuario)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT fk_progreso_nivel
        FOREIGN KEY (id_nivel) REFERENCES nivel(id_nivel)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    INDEX idx_progreso_estado (id_usuario, estado_nivel)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------------------------------------------------------
-- intento
-- ----------------------------------------------------------------------------
CREATE TABLE intento (
    id_intento           INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    id_progreso          INT UNSIGNED NOT NULL,
    id_opcion_elegida    INT UNSIGNED NULL,
    fue_correcto         TINYINT(1) NOT NULL DEFAULT 0,
    segundos_restantes   TINYINT UNSIGNED NOT NULL DEFAULT 0,
    registrado_en        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_intento_progreso
        FOREIGN KEY (id_progreso) REFERENCES progreso_usuario(id_progreso)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT fk_intento_opcion
        FOREIGN KEY (id_opcion_elegida) REFERENCES opcion_respuesta(id_opcion)
        ON DELETE SET NULL ON UPDATE CASCADE,
    INDEX idx_intento_progreso (id_progreso),
    INDEX idx_intento_fecha (registrado_en)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
