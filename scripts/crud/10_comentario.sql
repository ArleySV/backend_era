-- ============================================================================
-- ERA — Prueba CRUD: comentario (feedback, Módulo H)
-- Base: era_db_test · Motor: MySQL 8.0
-- Constraints: FK→usuario RESTRICT
-- ============================================================================

USE era_db_test;

-- ---------------------------------------------------------------------------
-- C: INSERT
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Diego Morales', '2015-02-28', 'diego@test.com', 'diego_2015', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();

INSERT INTO comentario (id_usuario, contenido)
VALUES (@id_usuario, 'Me encanta el juego de trivia, ¡aprendo mucho!');

SELECT id_comentario, id_usuario, contenido, enviado_en
FROM comentario WHERE id_usuario = @id_usuario;
-- Esperado: 1 fila con el contenido y fecha automática

ROLLBACK;

-- ---------------------------------------------------------------------------
-- R: SELECT
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Consulta Com', '2016-01-01', 'consulta_com@test.com', 'consulta_com', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();
INSERT INTO comentario (id_usuario, contenido) VALUES (@id_usuario, 'Primer comentario');
INSERT INTO comentario (id_usuario, contenido) VALUES (@id_usuario, 'Segundo comentario');

SELECT c.id_comentario, c.contenido, u.nombre_usuario
FROM comentario c
INNER JOIN usuario u ON u.id_usuario = c.id_usuario
WHERE c.id_usuario = @id_usuario
ORDER BY c.enviado_en;
-- Esperado: 2 filas, ambas con nombre_usuario

ROLLBACK;

-- ---------------------------------------------------------------------------
-- U: UPDATE (corregir contenido)
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Update Com', '2015-01-01', 'update_com@test.com', 'update_com', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();
INSERT INTO comentario (id_usuario, contenido) VALUES (@id_usuario, 'Contenido original');

UPDATE comentario SET contenido = 'Contenido corregido' WHERE id_usuario = @id_usuario;
SELECT contenido FROM comentario WHERE id_usuario = @id_usuario;
-- Esperado: 'Contenido corregido'

ROLLBACK;

-- ---------------------------------------------------------------------------
-- D: DELETE (borrar comentario específico)
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Borrar Com', '2015-01-01', 'borrar_com@test.com', 'borrar_com', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();
INSERT INTO comentario (id_usuario, contenido) VALUES (@id_usuario, 'A borrar');

DELETE FROM comentario WHERE id_usuario = @id_usuario;
SELECT COUNT(*) AS restantes FROM comentario WHERE id_usuario = @id_usuario;
-- Esperado: 0

ROLLBACK;

-- ---------------------------------------------------------------------------
-- CONSTRAINT: FK→usuario RESTRICT — no borrar usuario con comentarios
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Protegido Com', '2015-01-01', 'protegido_com@test.com', 'protegido_com', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();
INSERT INTO comentario (id_usuario, contenido) VALUES (@id_usuario, 'Comentario que protege');

-- ERROR esperado: Cannot delete or update a parent row (FK 'fk_comentario_usuario')
DELETE FROM usuario WHERE id_usuario = @id_usuario;

ROLLBACK;
