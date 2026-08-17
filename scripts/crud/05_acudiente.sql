-- ============================================================================
-- ERA — Prueba CRUD: acudiente (1:1 con usuario)
-- Base: era_db_test · Motor: MySQL 8.0
-- Constraints: FK→usuario RESTRICT, UNIQUE(id_usuario)
-- ============================================================================

USE era_db_test;

-- ---------------------------------------------------------------------------
-- C: INSERT (crea usuario padre primero)
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Valentina Cruz', '2014-08-10', 'valentina@test.com', 'vale_2014', 'hash_ejemplo', 'activo');
SET @id_usuario = LAST_INSERT_ID();

INSERT INTO acudiente (id_usuario, nombre_completo, numero_cedula)
VALUES (@id_usuario, 'María Cruz Ramírez', '1023456789');

SELECT a.id_acudiente, a.nombre_completo, a.numero_cedula, u.nombre_usuario
FROM acudiente a
INNER JOIN usuario u ON u.id_usuario = a.id_usuario;
-- Esperado: 1 fila con nombre del acudiente y usuario

ROLLBACK;

-- ---------------------------------------------------------------------------
-- R: SELECT con JOIN
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Andrés López', '2013-12-01', 'andres@test.com', 'andi_2013', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();
INSERT INTO acudiente (id_usuario, nombre_completo, numero_cedula)
VALUES (@id_usuario, 'Carlos López Martínez', '80123456');

SELECT u.nombre_menor, a.nombre_completo, a.numero_cedula
FROM usuario u
INNER JOIN acudiente a ON a.id_usuario = u.id_usuario
WHERE u.correo = 'andres@test.com';
-- Esperado: 1 fila con ambos nombres

ROLLBACK;

-- ---------------------------------------------------------------------------
-- U: UPDATE
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Lucía Torres', '2015-03-15', 'lucia@test.com', 'lucia_2015', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();
INSERT INTO acudiente (id_usuario, nombre_completo, numero_cedula)
VALUES (@id_usuario, 'Pedro Torres', '1098765432');

UPDATE acudiente SET nombre_completo = 'Pedro Torres Gómez' WHERE id_usuario = @id_usuario;
SELECT nombre_completo FROM acudiente WHERE id_usuario = @id_usuario;
-- Esperado: 'Pedro Torres Gómez'

ROLLBACK;

-- ---------------------------------------------------------------------------
-- D: DELETE (usuario → acudiente por RESTRICT impide borrar usuario)
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Borrar', '2010-01-01', 'borrar_ac@test.com', 'borrar_ac', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();
INSERT INTO acudiente (id_usuario, nombre_completo, numero_cedula)
VALUES (@id_usuario, 'Adulto Borrar', '12345678');

-- Primero borrar acudiente (hijo), luego usuario (padre)
DELETE FROM acudiente WHERE id_usuario = @id_usuario;
DELETE FROM usuario WHERE id_usuario = @id_usuario;
SELECT COUNT(*) AS restantes FROM usuario;
-- Esperado: 0

ROLLBACK;

-- ---------------------------------------------------------------------------
-- CONSTRAINT: UNIQUE(id_usuario) — 1:1, no dos acudientes por usuario
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Único', '2015-01-01', 'unico_ac@test.com', 'unico_ac', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();
INSERT INTO acudiente (id_usuario, nombre_completo, numero_cedula)
VALUES (@id_usuario, 'Primero', '11111111');

-- ERROR esperado: Duplicate entry for key 'uq_acudiente_usuario'
INSERT INTO acudiente (id_usuario, nombre_completo, numero_cedula)
VALUES (@id_usuario, 'Segundo', '22222222');

ROLLBACK;

-- ---------------------------------------------------------------------------
-- CONSTRAINT: FK→usuario RESTRICT — no borrar usuario con acudiente
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Protegido', '2015-01-01', 'protegido@test.com', 'protegido', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();
INSERT INTO acudiente (id_usuario, nombre_completo, numero_cedula)
VALUES (@id_usuario, 'Protege', '33333333');

-- ERROR esperado: Cannot delete or update a parent row (FK 'fk_acudiente_usuario')
DELETE FROM usuario WHERE id_usuario = @id_usuario;

ROLLBACK;
