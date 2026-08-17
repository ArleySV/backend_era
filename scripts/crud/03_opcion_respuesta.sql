-- ============================================================================
-- ERA — Prueba CRUD: opcion_respuesta
-- Base: era_db_test · Motor: MySQL 8.0
-- Constraints: FK→pregunta CASCADE
-- ============================================================================

USE era_db_test;

-- ---------------------------------------------------------------------------
-- C: INSERT (crea nivel + pregunta padre)
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO nivel (titulo, orden) VALUES ('Ciencias', 1);
SET @id_nivel = LAST_INSERT_ID();
INSERT INTO pregunta (id_nivel, enunciado) VALUES (@id_nivel, '¿Cuántos planetas tiene el sistema solar?');
SET @id_pregunta = LAST_INSERT_ID();

INSERT INTO opcion_respuesta (id_pregunta, texto_opcion, es_correcta) VALUES
  (@id_pregunta, '7',  0),
  (@id_pregunta, '8',  1),
  (@id_pregunta, '9',  0),
  (@id_pregunta, '10', 0);

SELECT id_opcion, texto_opcion, es_correcta FROM opcion_respuesta WHERE id_pregunta = @id_pregunta;
-- Esperado: 4 filas, solo la opción '8' tiene es_correcta=1

ROLLBACK;

-- ---------------------------------------------------------------------------
-- R: SELECT con JOIN
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO nivel (titulo, orden) VALUES ('Geografía', 2);
SET @id_nivel = LAST_INSERT_ID();
INSERT INTO pregunta (id_nivel, enunciado) VALUES (@id_nivel, '¿Capital de Francia?');
SET @id_pregunta = LAST_INSERT_ID();
INSERT INTO opcion_respuesta (id_pregunta, texto_opcion, es_correcta) VALUES
  (@id_pregunta, 'Londres', 0),
  (@id_pregunta, 'París',   1);

SELECT p.enunciado, o.texto_opcion, o.es_correcta
FROM pregunta p
INNER JOIN opcion_respuesta o ON o.id_pregunta = p.id_pregunta
WHERE p.id_pregunta = @id_pregunta;
-- Esperado: 2 filas con enunciado y opciones

ROLLBACK;

-- ---------------------------------------------------------------------------
-- U: UPDATE
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO nivel (titulo, orden) VALUES ('Matemáticas', 3);
SET @id_nivel = LAST_INSERT_ID();
INSERT INTO pregunta (id_nivel, enunciado) VALUES (@id_nivel, '2+2=?');
SET @id_pregunta = LAST_INSERT_ID();
INSERT INTO opcion_respuesta (id_pregunta, texto_opcion, es_correcta) VALUES
  (@id_pregunta, '3', 0),
  (@id_pregunta, '4', 1);

UPDATE opcion_respuesta SET texto_opcion = 'Cuatro' WHERE id_pregunta = @id_pregunta AND es_correcta = 1;
SELECT texto_opcion FROM opcion_respuesta WHERE id_pregunta = @id_pregunta AND es_correcta = 1;
-- Esperado: 'Cuatro'

ROLLBACK;

-- ---------------------------------------------------------------------------
-- D: DELETE (CASCADE desde pregunta)
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO nivel (titulo, orden) VALUES ('Borrar', 4);
SET @id_nivel = LAST_INSERT_ID();
INSERT INTO pregunta (id_nivel, enunciado) VALUES (@id_nivel, 'Pregunta temporal');
SET @id_pregunta = LAST_INSERT_ID();
INSERT INTO opcion_respuesta (id_pregunta, texto_opcion, es_correcta) VALUES
  (@id_pregunta, 'A', 0);

DELETE FROM nivel WHERE id_nivel = @id_nivel;
SELECT COUNT(*) AS opciones_restantes FROM opcion_respuesta;
-- Esperado: 0 (CASCADE: nivel→pregunta→opcion)

ROLLBACK;

-- ---------------------------------------------------------------------------
-- CONSTRAINT: FK→pregunta — pregunta inexistente
-- ---------------------------------------------------------------------------
START TRANSACTION;

-- ERROR esperado: Cannot add or update a child row (FK 'fk_opcion_pregunta')
INSERT INTO opcion_respuesta (id_pregunta, texto_opcion, es_correcta) VALUES (99999, 'Fantasma', 0);

ROLLBACK;
