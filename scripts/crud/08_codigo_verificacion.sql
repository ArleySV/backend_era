-- ============================================================================
-- ERA — Prueba CRUD: codigo_verificacion (recuperación de contraseña, Módulo C)
-- Base: era_db_test · Motor: MySQL 8.0
-- Constraints: FK→usuario RESTRICT
-- ============================================================================

USE era_db_test;

-- ---------------------------------------------------------------------------
-- C: INSERT
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Roberto Sánchez', '2014-11-11', 'roberto@test.com', 'rober_2014', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();

INSERT INTO codigo_verificacion (id_usuario, codigo_hash, intentos_fallidos, expira_en, usado)
VALUES (@id_usuario, 'hash_bcrypt_otp_654321', 0, DATE_ADD(NOW(), INTERVAL 10 MINUTE), 0);

SELECT id_codigo, id_usuario, intentos_fallidos, expira_en, usado
FROM codigo_verificacion WHERE id_usuario = @id_usuario;
-- Esperado: 1 fila con usado=0

ROLLBACK;

-- ---------------------------------------------------------------------------
-- R: SELECT
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Consulta CV', '2015-01-01', 'consulta_cv@test.com', 'consulta_cv', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();
INSERT INTO codigo_verificacion (id_usuario, codigo_hash, intentos_fallidos, expira_en, usado)
VALUES (@id_usuario, 'hash_otp_111', 2, DATE_ADD(NOW(), INTERVAL 10 MINUTE), 0);

SELECT c.intentos_fallidos, u.nombre_usuario
FROM codigo_verificacion c
INNER JOIN usuario u ON u.id_usuario = c.id_usuario
WHERE c.id_usuario = @id_usuario;
-- Esperado: intentos_fallidos=2, nombre_usuario='consulta_cv'

ROLLBACK;

-- ---------------------------------------------------------------------------
-- U: UPDATE (marcar como usado tras verificación exitosa)
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Verificar', '2015-01-01', 'verificar_cv@test.com', 'verificar_cv', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();
INSERT INTO codigo_verificacion (id_usuario, codigo_hash, intentos_fallidos, expira_en, usado)
VALUES (@id_usuario, 'hash_otp_222', 0, DATE_ADD(NOW(), INTERVAL 10 MINUTE), 0);

UPDATE codigo_verificacion SET usado = 1 WHERE id_usuario = @id_usuario;
SELECT usado FROM codigo_verificacion WHERE id_usuario = @id_usuario;
-- Esperado: usado=1

ROLLBACK;

-- ---------------------------------------------------------------------------
-- D: DELETE (limpieza de códigos expirados)
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Limpiar', '2015-01-01', 'limpiar_cv@test.com', 'limpiar_cv', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();
INSERT INTO codigo_verificacion (id_usuario, codigo_hash, intentos_fallidos, expira_en, usado)
VALUES (@id_usuario, 'hash_otp_333', 0, DATE_SUB(NOW(), INTERVAL 1 HOUR), 1);

DELETE FROM codigo_verificacion WHERE id_usuario = @id_usuario AND usado = 1;
SELECT COUNT(*) AS restantes FROM codigo_verificacion WHERE id_usuario = @id_usuario;
-- Esperado: 0

ROLLBACK;

-- ---------------------------------------------------------------------------
-- CONSTRAINT: FK→usuario RESTRICT — no borrar usuario con código activo
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Protegido CV', '2015-01-01', 'protegido_cv@test.com', 'protegido_cv', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();
INSERT INTO codigo_verificacion (id_usuario, codigo_hash, intentos_fallidos, expira_en, usado)
VALUES (@id_usuario, 'hash_otp', 0, DATE_ADD(NOW(), INTERVAL 10 MINUTE), 0);

-- ERROR esperado: Cannot delete or update a parent row (FK 'fk_codigo_usuario')
DELETE FROM usuario WHERE id_usuario = @id_usuario;

ROLLBACK;
