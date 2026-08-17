-- ============================================================================
-- ERA — Prueba CRUD: configuracion (1:1 con usuario)
-- Base: era_db_test · Motor: MySQL 8.0
-- Constraints: FK→usuario RESTRICT, UNIQUE(id_usuario)
-- ============================================================================

USE era_db_test;

-- ---------------------------------------------------------------------------
-- C: INSERT
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Santiago Díaz', '2015-09-20', 'santiago@test.com', 'santi_2015', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();

INSERT INTO configuracion (id_usuario, sonido, musica, tema_visual, tamano_texto)
VALUES (@id_usuario, 1, 1, 'claro', 'mediano');

SELECT id_config, sonido, musica, tema_visual, tamano_texto
FROM configuracion WHERE id_usuario = @id_usuario;
-- Esperado: 1 fila con valores por defecto

ROLLBACK;

-- ---------------------------------------------------------------------------
-- R: SELECT
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Camila Reina', '2014-04-05', 'camila@test.com', 'camila_2014', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();
INSERT INTO configuracion (id_usuario, sonido, musica, tema_visual, tamano_texto)
VALUES (@id_usuario, 0, 1, 'oscuro', 'grande');

SELECT u.nombre_usuario, c.tema_visual, c.tamano_texto
FROM configuracion c
INNER JOIN usuario u ON u.id_usuario = c.id_usuario;
-- Esperado: 1 fila con tema_visual='oscuro', tamano_texto='grande'

ROLLBACK;

-- ---------------------------------------------------------------------------
-- U: UPDATE
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Para Update', '2016-01-01', 'update_conf@test.com', 'conf_update', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();
INSERT INTO configuracion (id_usuario, sonido, musica, tema_visual, tamano_texto)
VALUES (@id_usuario, 1, 1, 'claro', 'mediano');

UPDATE configuracion SET tema_visual = 'oscuro', sonido = 0 WHERE id_usuario = @id_usuario;
SELECT tema_visual, sonido FROM configuracion WHERE id_usuario = @id_usuario;
-- Esperado: tema_visual='oscuro', sonido=0

ROLLBACK;

-- ---------------------------------------------------------------------------
-- D: DELETE (borrar configuración)
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Borrar Config', '2015-06-06', 'borrar_conf@test.com', 'borrar_conf', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();
INSERT INTO configuracion (id_usuario, sonido, musica, tema_visual, tamano_texto)
VALUES (@id_usuario, 1, 1, 'claro', 'mediano');

DELETE FROM configuracion WHERE id_usuario = @id_usuario;
SELECT COUNT(*) AS restantes FROM configuracion WHERE id_usuario = @id_usuario;
-- Esperado: 0

ROLLBACK;

-- ---------------------------------------------------------------------------
-- CONSTRAINT: UNIQUE(id_usuario) — 1:1, no dos configuraciones por usuario
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Único Conf', '2015-01-01', 'unico_conf@test.com', 'unico_conf', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();
INSERT INTO configuracion (id_usuario, sonido, musica, tema_visual, tamano_texto)
VALUES (@id_usuario, 1, 1, 'claro', 'mediano');

-- ERROR esperado: Duplicate entry for key 'uq_config_usuario'
INSERT INTO configuracion (id_usuario, sonido, musica, tema_visual, tamano_texto)
VALUES (@id_usuario, 0, 0, 'oscuro', 'grande');

ROLLBACK;

-- ---------------------------------------------------------------------------
-- CONSTRAINT: FK→usuario RESTRICT — no borrar usuario con configuración
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Protegido Conf', '2015-01-01', 'protegido_conf@test.com', 'protegido_conf', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();
INSERT INTO configuracion (id_usuario, sonido, musica, tema_visual, tamano_texto)
VALUES (@id_usuario, 1, 1, 'claro', 'mediano');

-- ERROR esperado: Cannot delete or update a parent row (FK 'fk_configuracion_usuario')
DELETE FROM usuario WHERE id_usuario = @id_usuario;

ROLLBACK;
