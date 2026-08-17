-- ============================================================================
-- ERA — Prueba CRUD: nivel (catálogo de trivia)
-- Base: era_db_test · Motor: MySQL 8.0
-- Constraints: UNIQUE(orden), CHECK(orden BETWEEN 1 AND 20)
-- Ejecutar bloque por bloque en MySQL Workbench. Cada bloque es independiente.
-- ============================================================================

USE era_db_test;

-- ---------------------------------------------------------------------------
-- C: INSERT
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO nivel (titulo, orden) VALUES ('Historia Mundial', 1);
INSERT INTO nivel (titulo, orden) VALUES ('Ciencias Naturales', 2);
INSERT INTO nivel (titulo, orden) VALUES ('Matemáticas Básicas', 3);

SELECT id_nivel, titulo, orden FROM nivel ORDER BY orden;
-- Esperado: 3 filas con orden 1, 2, 3

ROLLBACK;

-- ---------------------------------------------------------------------------
-- R: SELECT
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO nivel (titulo, orden) VALUES ('Geografía', 10);
INSERT INTO nivel (titulo, orden) VALUES ('Arte', 11);

SELECT * FROM nivel WHERE orden BETWEEN 10 AND 11;
-- Esperado: 2 filas

SELECT COUNT(*) AS total_niveles FROM nivel;
-- Esperado: 2

ROLLBACK;

-- ---------------------------------------------------------------------------
-- U: UPDATE
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO nivel (titulo, orden) VALUES ('Música', 15);
UPDATE nivel SET titulo = 'Historia del Arte' WHERE orden = 15;
SELECT titulo FROM nivel WHERE orden = 15;
-- Esperado: 'Historia del Arte'

ROLLBACK;

-- ---------------------------------------------------------------------------
-- D: DELETE
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO nivel (titulo, orden) VALUES ('Para Eliminar', 20);
DELETE FROM nivel WHERE orden = 20;
SELECT COUNT(*) AS restantes FROM nivel;
-- Esperado: 0

ROLLBACK;

-- ---------------------------------------------------------------------------
-- CONSTRAINT: UNIQUE(orden) — duplicado
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO nivel (titulo, orden) VALUES ('Primero', 1);
-- ERROR esperado: Duplicate entry '1' for key 'uq_nivel_orden'
INSERT INTO nivel (titulo, orden) VALUES ('Otro Primero', 1);

ROLLBACK;

-- ---------------------------------------------------------------------------
-- CONSTRAINT: CHECK(orden BETWEEN 1 AND 20) — fuera de rango
-- ---------------------------------------------------------------------------
START TRANSACTION;

-- ERROR esperado: Check constraint 'ck_nivel_orden' is violated
INSERT INTO nivel (titulo, orden) VALUES ('Fuera de rango', 0);

ROLLBACK;

-- ---------------------------------------------------------------------------
-- CONSTRAINT: CHECK — fuera de rango superior
-- ---------------------------------------------------------------------------
START TRANSACTION;

-- ERROR esperado: Check constraint 'ck_nivel_orden' is violated
INSERT INTO nivel (titulo, orden) VALUES ('Fuera de rango', 21);

ROLLBACK;
