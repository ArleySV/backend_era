-- ============================================================================
-- ERA — Prueba CRUD: intento (intento de juego, Módulo G)
-- Base: era_db_test · Motor: MySQL 8.0
-- Constraints: FK→progreso_usuario RESTRICT, FK→opcion_respuesta SET NULL
-- ============================================================================

USE era_db_test;

-- ---------------------------------------------------------------------------
-- C: INSERT (crea toda la cadena: usuario → nivel → pregunta → opciones → progreso → intento)
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Intento User', '2015-03-10', 'intento@test.com', 'intento_2015', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();

INSERT INTO nivel (titulo, orden) VALUES ('Intento Nivel', 17);
SET @id_nivel = LAST_INSERT_ID();

INSERT INTO pregunta (id_nivel, enunciado) VALUES (@id_nivel, 'Pregunta de intento');
SET @id_pregunta = LAST_INSERT_ID();

INSERT INTO opcion_respuesta (id_pregunta, texto_opcion, es_correcta) VALUES
  (@id_pregunta, 'Opción A', 0),
  (@id_pregunta, 'Opción B', 1);
SET @id_opcion_correcta = LAST_INSERT_ID();

INSERT INTO progreso_usuario (id_usuario, id_nivel, estado_nivel, intentos_totales, intentos_fallidos_consecutivos)
VALUES (@id_usuario, @id_nivel, 'disponible', 0, 0);
SET @id_progreso = LAST_INSERT_ID();

INSERT INTO intento (id_progreso, id_opcion_elegida, fue_correcto, segundos_restantes)
VALUES (@id_progreso, @id_opcion_correcta, 1, 25);

SELECT id_intento, fue_correcto, segundos_restantes, registrado_en
FROM intento WHERE id_progreso = @id_progreso;
-- Esperado: 1 fila con fue_correcto=1, segundos_restantes=25

ROLLBACK;

-- ---------------------------------------------------------------------------
-- R: SELECT con JOINs (historial completo de intentos)
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Historial', '2014-07-20', 'historial@test.com', 'historial_2014', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();

INSERT INTO nivel (titulo, orden) VALUES ('Historial Nivel', 18);
SET @id_nivel = LAST_INSERT_ID();

INSERT INTO pregunta (id_nivel, enunciado) VALUES (@id_nivel, 'Pregunta historial');
SET @id_pregunta = LAST_INSERT_ID();

INSERT INTO opcion_respuesta (id_pregunta, texto_opcion, es_correcta) VALUES
  (@id_pregunta, 'X', 0),
  (@id_pregunta, 'Y', 1);
SET @id_opcion = LAST_INSERT_ID();

INSERT INTO progreso_usuario (id_usuario, id_nivel, estado_nivel, intentos_totales, intentos_fallidos_consecutivos)
VALUES (@id_usuario, @id_nivel, 'disponible', 0, 0);
SET @id_progreso = LAST_INSERT_ID();

INSERT INTO intento (id_progreso, id_opcion_elegida, fue_correcto, segundos_restantes) VALUES
  (@id_progreso, @id_opcion, 1, 30),
  (@id_progreso, NULL, 0, 0);

SELECT i.id_intento, i.fue_correcto, i.segundos_restantes, o.texto_opcion
FROM intento i
LEFT JOIN opcion_respuesta o ON o.id_opcion = i.id_opcion_elegida
WHERE i.id_progreso = @id_progreso
ORDER BY i.registrado_en;
-- Esperado: 2 filas: una con opción Y y otra con NULL (sin opción elegida)

ROLLBACK;

-- ---------------------------------------------------------------------------
-- U: UPDATE (corregir tiempo restante)
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Update Intento', '2015-01-01', 'update_intento@test.com', 'update_intento', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();

INSERT INTO nivel (titulo, orden) VALUES ('Update Nivel', 19);
SET @id_nivel = LAST_INSERT_ID();

INSERT INTO progreso_usuario (id_usuario, id_nivel, estado_nivel, intentos_totales, intentos_fallidos_consecutivos)
VALUES (@id_usuario, @id_nivel, 'disponible', 0, 0);
SET @id_progreso = LAST_INSERT_ID();

INSERT INTO intento (id_progreso, id_opcion_elegida, fue_correcto, segundos_restantes)
VALUES (@id_progreso, NULL, 0, 10);

UPDATE intento SET segundos_restantes = 15 WHERE id_progreso = @id_progreso;
SELECT segundos_restantes FROM intento WHERE id_progreso = @id_progreso;
-- Esperado: 15

ROLLBACK;

-- ---------------------------------------------------------------------------
-- D: DELETE (limpiar intentos viejos)
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Borrar Intento', '2015-01-01', 'borrar_intento@test.com', 'borrar_intento', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();

INSERT INTO nivel (titulo, orden) VALUES ('Borrar Nivel Intento', 20);
SET @id_nivel = LAST_INSERT_ID();

INSERT INTO progreso_usuario (id_usuario, id_nivel, estado_nivel, intentos_totales, intentos_fallidos_consecutivos)
VALUES (@id_usuario, @id_nivel, 'bloqueado', 0, 0);
SET @id_progreso = LAST_INSERT_ID();

INSERT INTO intento (id_progreso, id_opcion_elegida, fue_correcto, segundos_restantes)
VALUES (@id_progreso, NULL, 0, 5);

DELETE FROM intento WHERE id_progreso = @id_progreso;
SELECT COUNT(*) AS restantes FROM intento WHERE id_progreso = @id_progreso;
-- Esperado: 0

ROLLBACK;

-- ---------------------------------------------------------------------------
-- CONSTRAINT: FK→progreso_usuario RESTRICT — no borrar progreso con intentos
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('Protegido Int', '2015-01-01', 'protegido_int@test.com', 'protegido_int', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();

INSERT INTO nivel (titulo, orden) VALUES ('Protegido Nivel Int', 17);
SET @id_nivel = LAST_INSERT_ID();

INSERT INTO progreso_usuario (id_usuario, id_nivel, estado_nivel, intentos_totales, intentos_fallidos_consecutivos)
VALUES (@id_usuario, @id_nivel, 'disponible', 1, 0);
SET @id_progreso = LAST_INSERT_ID();

INSERT INTO intento (id_progreso, id_opcion_elegida, fue_correcto, segundos_restantes)
VALUES (@id_progreso, NULL, 0, 10);

-- ERROR esperado: Cannot delete or update a parent row (FK 'fk_intento_progreso')
DELETE FROM progreso_usuario WHERE id_progreso = @id_progreso;

ROLLBACK;

-- ---------------------------------------------------------------------------
-- CONSTRAINT: FK→opcion_respuesta SET NULL — borrar opción elegida
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO usuario (nombre_menor, fecha_nacimiento, correo, nombre_usuario, contrasena_hash, estado)
VALUES ('SetNull Int', '2015-01-01', 'setnull_int@test.com', 'setnull_int', 'hash', 'activo');
SET @id_usuario = LAST_INSERT_ID();

INSERT INTO nivel (titulo, orden) VALUES ('SetNull Nivel', 18);
SET @id_nivel = LAST_INSERT_ID();

INSERT INTO pregunta (id_nivel, enunciado) VALUES (@id_nivel, 'SetNull Pregunta');
SET @id_pregunta = LAST_INSERT_ID();

INSERT INTO opcion_respuesta (id_pregunta, texto_opcion, es_correcta) VALUES (@id_pregunta, 'Op', 1);
SET @id_opcion = LAST_INSERT_ID();

INSERT INTO progreso_usuario (id_usuario, id_nivel, estado_nivel, intentos_totales, intentos_fallidos_consecutivos)
VALUES (@id_usuario, @id_nivel, 'disponible', 1, 0);
SET @id_progreso = LAST_INSERT_ID();

INSERT INTO intento (id_progreso, id_opcion_elegida, fue_correcto, segundos_restantes)
VALUES (@id_progreso, @id_opcion, 1, 20);

-- Borrar la opción elegida → FK SET NULL pone id_opcion_elegida = NULL
DELETE FROM opcion_respuesta WHERE id_opcion = @id_opcion;

SELECT id_opcion_elegida FROM intento WHERE id_progreso = @id_progreso;
-- Esperado: NULL (SET NULL activado)

ROLLBACK;
