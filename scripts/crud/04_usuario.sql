-- ============================================================================
-- ERA — Prueba CRUD: usuario
-- Base: era_db_test · Motor: MySQL 8.0
-- Constraints: UNIQUE(correo), UNIQUE(nombre_usuario), soft delete via estado
-- ============================================================================

USE era_db_test;

-- ---------------------------------------------------------------------------
-- C: INSERT
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, avatar, estado)
VALUES ('Luna Pérez', '2015-06-15', 'luna@test.com', 'luna_2015', 'hash_bcrypt_ejemplo', 'preset:1', 'activo');

SELECT id_usuario, nombre_menor, fecha_nacimiento, correo, nombre_usuario, avatar, estado
FROM usuario WHERE correo = 'luna@test.com';
-- Esperado: 1 fila con estado 'activo'

ROLLBACK;

-- ---------------------------------------------------------------------------
-- R: SELECT
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Mateo García', '2014-03-20', 'mateo@test.com', 'mateo_2014', 'hash_ejemplo', 'activo');

SELECT COUNT(*) AS total FROM usuario;
-- Esperado: 1

SELECT nombre_usuario, correo FROM usuario WHERE fecha_nacimiento > '2014-01-01';
-- Esperado: 1 fila (Mateo)

ROLLBACK;

-- ---------------------------------------------------------------------------
-- U: UPDATE (soft delete + username + avatar)
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Sofía López', '2016-01-10', 'sofia@test.com', 'sofia_2016', 'hash_ejemplo', 'activo');

-- Soft delete
UPDATE usuario SET estado = 'eliminado' WHERE correo = 'sofia@test.com';
SELECT estado FROM usuario WHERE correo = 'sofia@test.com';
-- Esperado: 'eliminado'

-- Actualizar username
UPDATE usuario SET nombre_usuario = 'sofia_nueva' WHERE correo = 'sofia@test.com';
SELECT nombre_usuario FROM usuario WHERE correo = 'sofia@test.com';
-- Esperado: 'sofia_nueva'

-- Actualizar avatar
UPDATE usuario SET avatar = 'custom:abc-123' WHERE correo = 'sofia@test.com';
SELECT avatar FROM usuario WHERE correo = 'sofia@test.com';
-- Esperado: 'custom:abc-123'

ROLLBACK;

-- ---------------------------------------------------------------------------
-- D: soft delete (estado = 'eliminado')
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Para Borrar', '2013-05-05', 'borrar@test.com', 'borrar_user', 'hash', 'activo');

UPDATE usuario SET estado = 'eliminado' WHERE correo = 'borrar@test.com';
SELECT estado FROM usuario WHERE correo = 'borrar@test.com';
-- Esperado: 'eliminado'

-- El usuario aún existe (soft delete, no borrado físico)
SELECT COUNT(*) AS sigue_existiendo FROM usuario WHERE correo = 'borrar@test.com';
-- Esperado: 1

ROLLBACK;

-- ---------------------------------------------------------------------------
-- CONSTRAINT: UNIQUE(correo) — duplicado
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Primero', '2015-01-01', 'unico@test.com', 'user_unico', 'hash', 'activo');

-- ERROR esperado: Duplicate entry 'unico@test.com' for key 'uq_usuario_correo'
INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Segundo', '2015-02-02', 'unico@test.com', 'user_otro', 'hash', 'activo');

ROLLBACK;

-- ---------------------------------------------------------------------------
-- CONSTRAINT: UNIQUE(nombre_usuario) — duplicado
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('A', '2015-01-01', 'a@test.com', 'mismo_username', 'hash', 'activo');

-- ERROR esperado: Duplicate entry 'mismo_username' for key 'uq_usuario_nombre_usuario'
INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('B', '2015-02-02', 'b@test.com', 'mismo_username', 'hash', 'activo');

ROLLBACK;
