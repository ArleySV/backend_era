-- ============================================================================
-- ERA — Prueba CRUD: tokens_reseteo (token puente JWT, Módulo C)
-- Base: era_db_test · Motor: MySQL 8.0
-- Constraints: FK→usuario RESTRICT, UNIQUE(jti)
-- ============================================================================

USE era_db_test;

-- ---------------------------------------------------------------------------
-- C: INSERT
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Elena Vargas', '2013-06-30', 'elena@test.com', 'elena_2013', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();

INSERT INTO tokens_reseteo (jti, id_usuario, expira_en, consumido)
VALUES ('jti_abc123_001', @id_usuario, DATE_ADD(NOW(), INTERVAL 10 MINUTE), 0);

SELECT id_token, jti, id_usuario, expira_en, consumido
FROM tokens_reseteo WHERE jti = 'jti_abc123_001';
-- Esperado: 1 fila con consumido=0

ROLLBACK;

-- ---------------------------------------------------------------------------
-- R: SELECT
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Consulta TR', '2015-01-01', 'consulta_tr@test.com', 'consulta_tr', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();
INSERT INTO tokens_reseteo (jti, id_usuario, expira_en, consumido)
VALUES ('jti_xyz789_001', @id_usuario, DATE_ADD(NOW(), INTERVAL 10 MINUTE), 0);

SELECT t.jti, t.consumido, u.nombre_usuario
FROM tokens_reseteo t
INNER JOIN usuario u ON u.id_usuario = t.id_usuario
WHERE t.jti = 'jti_xyz789_001';
-- Esperado: 1 fila con nombre_usuario

ROLLBACK;

-- ---------------------------------------------------------------------------
-- U: UPDATE (consumir token tras confirmar nueva contraseña)
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Consumir', '2015-01-01', 'consumir_tr@test.com', 'consumir_tr', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();
INSERT INTO tokens_reseteo (jti, id_usuario, expira_en, consumido)
VALUES ('jti_consumir_001', @id_usuario, DATE_ADD(NOW(), INTERVAL 10 MINUTE), 0);

UPDATE tokens_reseteo SET consumido = 1 WHERE jti = 'jti_consumir_001';
SELECT consumido FROM tokens_reseteo WHERE jti = 'jti_consumir_001';
-- Esperado: consumido=1

ROLLBACK;

-- ---------------------------------------------------------------------------
-- D: DELETE (limpieza de tokens expirados)
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Limpiar TR', '2015-01-01', 'limpiar_tr@test.com', 'limpiar_tr', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();
INSERT INTO tokens_reseteo (jti, id_usuario, expira_en, consumido)
VALUES ('jti_viejo_001', @id_usuario, DATE_SUB(NOW(), INTERVAL 1 HOUR), 1);

-- NOTA: Workbench tiene safe update mode activo; se agrega id_token (PK) al WHERE.
DELETE FROM tokens_reseteo WHERE id_token > 0 AND consumido = 1 AND expira_en < NOW();
SELECT COUNT(*) AS restantes FROM tokens_reseteo WHERE jti = 'jti_viejo_001';
-- Esperado: 0

ROLLBACK;

-- ---------------------------------------------------------------------------
-- CONSTRAINT: UNIQUE(jti) — duplicado
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Único TR', '2015-01-01', 'unico_tr@test.com', 'unico_tr', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();
INSERT INTO tokens_reseteo (jti, id_usuario, expira_en, consumido)
VALUES ('jti_duplicado_001', @id_usuario, DATE_ADD(NOW(), INTERVAL 10 MINUTE), 0);

-- ERROR esperado: Duplicate entry 'jti_duplicado_001' for key 'uq_tokens_reseteo_jti'
INSERT INTO tokens_reseteo (jti, id_usuario, expira_en, consumido)
VALUES ('jti_duplicado_001', @id_usuario, DATE_ADD(NOW(), INTERVAL 10 MINUTE), 0);

ROLLBACK;

-- ---------------------------------------------------------------------------
-- CONSTRAINT: FK→usuario RESTRICT — no borrar usuario con token activo
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Protegido TR', '2015-01-01', 'protegido_tr@test.com', 'protegido_tr', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();
INSERT INTO tokens_reseteo (jti, id_usuario, expira_en, consumido)
VALUES ('jti_protegido_001', @id_usuario, DATE_ADD(NOW(), INTERVAL 10 MINUTE), 0);

-- ERROR esperado: Cannot delete or update a parent row (FK 'fk_tokens_reseteo_usuario')
DELETE FROM usuario WHERE id_usuario = @id_usuario;

ROLLBACK;
