-- ============================================================================
-- ERA — Prueba CRUD: progreso_usuario (merge de progreso, Módulo G)
-- Base: era_db_test · Motor: MySQL 8.0
-- Constraints: FK→usuario RESTRICT, FK→nivel RESTRICT, UNIQUE(id_usuario, id_nivel)
-- ============================================================================

USE era_db_test;

-- ---------------------------------------------------------------------------
-- Setup: crear catálogo de niveles (dependencia mínima)
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO nivel (titulo, orden) VALUES
  ('Nivel CRUD 1', 1),
  ('Nivel CRUD 2', 2),
  ('Nivel CRUD 3', 3);

-- Guardamos ids para uso posterior
SELECT id_nivel, titulo, orden FROM nivel ORDER BY orden;
ROLLBACK;

-- ---------------------------------------------------------------------------
-- C: INSERT (progreso para un usuario)
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Progreso User', '2014-05-15', 'progreso@test.com', 'prog_2014', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();

INSERT INTO nivel (titulo, orden) VALUES ('Prog Nivel', 10);
SET @id_nivel = LAST_INSERT_ID();

INSERT INTO progreso_usuario (id_usuario, id_nivel, estado_nivel, intentos_totales, intentos_fallidos_consecutivos, completado_en)
VALUES (@id_usuario, @id_nivel, 'completado', 3, 0, NOW());

SELECT id_progreso, estado_nivel, intentos_totales, intentos_fallidos_consecutivos, completado_en
FROM progreso_usuario WHERE id_usuario = @id_usuario AND id_nivel = @id_nivel;
-- Esperado: 1 fila con estado_nivel='completado'

ROLLBACK;

-- ---------------------------------------------------------------------------
-- R: SELECT con JOINs (vista completa de progreso)
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Prog R', '2015-01-01', 'prog_r@test.com', 'prog_r', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();

INSERT INTO nivel (titulo, orden) VALUES ('Prog Nivel R', 11);
SET @id_nivel = LAST_INSERT_ID();

INSERT INTO progreso_usuario (id_usuario, id_nivel, estado_nivel, intentos_totales, intentos_fallidos_consecutivos)
VALUES (@id_usuario, @id_nivel, 'disponible', 1, 1);

SELECT u.nombre_usuario, n.titulo, p.estado_nivel, p.intentos_totales
FROM progreso_usuario p
INNER JOIN usuario u ON u.id_usuario = p.id_usuario
INNER JOIN nivel n ON n.id_nivel = p.id_nivel
WHERE p.id_usuario = @id_usuario;
-- Esperado: 1 fila con datos completos

ROLLBACK;

-- ---------------------------------------------------------------------------
-- U: UPDATE (simula merge: max de estado, max de contadores)
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Merge User', '2015-01-01', 'merge@test.com', 'merge_user', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();

INSERT INTO nivel (titulo, orden) VALUES ('Merge Nivel', 12);
SET @id_nivel = LAST_INSERT_ID();

INSERT INTO progreso_usuario (id_usuario, id_nivel, estado_nivel, intentos_totales, intentos_fallidos_consecutivos)
VALUES (@id_usuario, @id_nivel, 'disponible', 5, 2);

-- Simula merge: servidor usa MAX() para contadores
UPDATE progreso_usuario
SET estado_nivel = CASE
    WHEN 'completado' > estado_nivel THEN 'completado'
    ELSE estado_nivel
END,
intentos_totales = GREATEST(intentos_totales, 8),
intentos_fallidos_consecutivos = GREATEST(intentos_fallidos_consecutivos, 1)
WHERE id_usuario = @id_usuario AND id_nivel = @id_nivel;

SELECT intentos_totales, intentos_fallidos_consecutivos
FROM progreso_usuario WHERE id_usuario = @id_usuario AND id_nivel = @id_nivel;
-- Esperado: intentos_totales=8, intentos_fallidos_consecutivos=2

ROLLBACK;

-- ---------------------------------------------------------------------------
-- D: DELETE (limpiar progreso)
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Borrar Prog', '2015-01-01', 'borrar_prog@test.com', 'borrar_prog', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();

INSERT INTO nivel (titulo, orden) VALUES ('Borrar Nivel', 13);
SET @id_nivel = LAST_INSERT_ID();

INSERT INTO progreso_usuario (id_usuario, id_nivel, estado_nivel, intentos_totales, intentos_fallidos_consecutivos)
VALUES (@id_usuario, @id_nivel, 'bloqueado', 0, 0);

DELETE FROM progreso_usuario WHERE id_usuario = @id_usuario;
SELECT COUNT(*) AS restantes FROM progreso_usuario WHERE id_usuario = @id_usuario;
-- Esperado: 0

ROLLBACK;

-- ---------------------------------------------------------------------------
-- CONSTRAINT: UNIQUE(id_usuario, id_nivel) — duplicado
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Único Prog', '2015-01-01', 'unico_prog@test.com', 'unico_prog', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();

INSERT INTO nivel (titulo, orden) VALUES ('Único Nivel', 14);
SET @id_nivel = LAST_INSERT_ID();

INSERT INTO progreso_usuario (id_usuario, id_nivel, estado_nivel, intentos_totales, intentos_fallidos_consecutivos)
VALUES (@id_usuario, @id_nivel, 'bloqueado', 0, 0);

-- ERROR esperado: Duplicate entry for key 'uq_progreso_usuario_nivel'
INSERT INTO progreso_usuario (id_usuario, id_nivel, estado_nivel, intentos_totales, intentos_fallidos_consecutivos)
VALUES (@id_usuario, @id_nivel, 'disponible', 1, 0);

ROLLBACK;

-- ---------------------------------------------------------------------------
-- CONSTRAINT: FK→usuario RESTRICT — no borrar usuario con progreso
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Protegido Prog', '2015-01-01', 'protegido_prog@test.com', 'protegido_prog', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();

INSERT INTO nivel (titulo, orden) VALUES ('Protegido Nivel', 15);
SET @id_nivel = LAST_INSERT_ID();

INSERT INTO progreso_usuario (id_usuario, id_nivel, estado_nivel, intentos_totales, intentos_fallidos_consecutivos)
VALUES (@id_usuario, @id_nivel, 'disponible', 1, 0);

-- ERROR esperado: Cannot delete or update a parent row (FK 'fk_progreso_usuario')
DELETE FROM usuario WHERE id_usuario = @id_usuario;

ROLLBACK;

-- ---------------------------------------------------------------------------
-- CONSTRAINT: FK→nivel RESTRICT — no borrar nivel con progreso asociado
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO nivel (titulo, orden) VALUES ('Nivel Protegido', 16);
SET @id_nivel = LAST_INSERT_ID();

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Protege Nivel', '2015-01-01', 'protege_nivel@test.com', 'protege_nivel', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();

INSERT INTO progreso_usuario (id_usuario, id_nivel, estado_nivel, intentos_totales, intentos_fallidos_consecutivos)
VALUES (@id_usuario, @id_nivel, 'disponible', 1, 0);

-- ERROR esperado: Cannot delete or update a parent row (FK 'fk_progreso_nivel')
DELETE FROM nivel WHERE id_nivel = @id_nivel;

ROLLBACK;
