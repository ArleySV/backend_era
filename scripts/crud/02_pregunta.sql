-- ============================================================================
-- ERA — Prueba CRUD: pregunta (1 por nivel, REQ-FUN-10 CA4)
-- Base: era_db_test · Motor: MySQL 8.0
-- Constraints: UNIQUE(id_nivel), FK→nivel CASCADE
-- ============================================================================

USE era_db_test;

-- ---------------------------------------------------------------------------
-- C: INSERT (crea nivel padre primero)
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO nivel (titulo, orden) VALUES ('Ciencia', 1);
SET @id_nivel = LAST_INSERT_ID();

INSERT INTO pregunta (id_nivel, enunciado, imagen_url)
VALUES (@id_nivel, '¿Cuál es el planeta más grande del sistema solar?', NULL);

SELECT id_pregunta, id_nivel, enunciado, imagen_url FROM pregunta;
-- Esperado: 1 fila con enunciado correcto

ROLLBACK;

-- ---------------------------------------------------------------------------
-- R: SELECT
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO nivel (titulo, orden) VALUES ('Geografía', 5);
SET @id_nivel = LAST_INSERT_ID();
INSERT INTO pregunta (id_nivel, enunciado) VALUES (@id_nivel, '¿Cuál es el río más largo del mundo?');

SELECT p.id_pregunta, n.titulo, p.enunciado
FROM pregunta p
INNER JOIN nivel n ON n.id_nivel = p.id_nivel;
-- Esperado: 1 fila con 'Geografía'

ROLLBACK;

-- ---------------------------------------------------------------------------
-- U: UPDATE
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO nivel (titulo, orden) VALUES ('Historia', 7);
SET @id_nivel = LAST_INSERT_ID();
INSERT INTO pregunta (id_nivel, enunciado) VALUES (@id_nivel, 'Pregunta vieja');

UPDATE pregunta SET enunciado = 'Pregunta actualizada' WHERE id_nivel = @id_nivel;
SELECT enunciado FROM pregunta WHERE id_nivel = @id_nivel;
-- Esperado: 'Pregunta actualizada'

ROLLBACK;

-- ---------------------------------------------------------------------------
-- D: DELETE (CASCADE desde nivel)
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO nivel (titulo,orden) VALUES ('Temporal', 8);
SET @id_nivel = LAST_INSERT_ID();
INSERT INTO pregunta (id_nivel, enunciado) VALUES (@id_nivel, 'Se borra con nivel');

DELETE FROM nivel WHERE id_nivel = @id_nivel;
SELECT COUNT(*) AS preguntas_restantes FROM pregunta;
-- Esperado: 0 (CASCADE borró la pregunta)

ROLLBACK;

-- ---------------------------------------------------------------------------
-- CONSTRAINT: UNIQUE(id_nivel) — solo 1 pregunta por nivel
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO nivel (titulo, orden) VALUES ('Único', 9);
SET @id_nivel = LAST_INSERT_ID();
INSERT INTO pregunta (id_nivel, enunciado) VALUES (@id_nivel, 'Primera');

-- ERROR esperado: Duplicate entry para 'uq_pregunta_nivel'
INSERT INTO pregunta (id_nivel, enunciado) VALUES (@id_nivel, 'Segunda');

ROLLBACK;

-- ---------------------------------------------------------------------------
-- CONSTRAINT: FK→nivel — nivel inexistente
-- ---------------------------------------------------------------------------
START TRANSACTION;

-- ERROR esperado: Cannot add or update a child row (FK constraint 'fk_pregunta_nivel')
INSERT INTO pregunta (id_nivel, enunciado) VALUES (99999, 'Nivel fantasma');

ROLLBACK;
