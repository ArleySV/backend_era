-- ============================================================================
-- ERA — Prueba CRUD: registro_pendiente
-- Base: era_db_test · Motor: MySQL 8.0
-- Constraints: UNIQUE(correo), UNIQUE(nombre_usuario)
-- Tabla temporal: se consume al verificar el código (Módulo A.1)
-- ============================================================================

USE era_db_test;

-- ---------------------------------------------------------------------------
-- C: INSERT (simula un registro no verificado)
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO registro_pendiente (correo, nombre_usuario, contrasena_hash, nombre_menor, fecha_nacimiento,
                                nombre_acudiente, cedula_acudiente, avatar, codigo_hash, intentos_fallidos, expira_en)
VALUES ('pendiente@test.com', 'pendiente_usr', 'hash_ejemplo', 'Niño Pendiente', '2015-07-07',
        'Adulto Pendiente', '1000000001', 'preset:2', 'hash_otp_123456', 0,
        DATE_ADD(NOW(), INTERVAL 10 MINUTE));

SELECT id_registro, correo, nombre_usuario, nombre_menor, avatar, intentos_fallidos
FROM registro_pendiente WHERE correo = 'pendiente@test.com';
-- Esperado: 1 fila

ROLLBACK;

-- ---------------------------------------------------------------------------
-- R: SELECT
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO registro_pendiente (correo, nombre_usuario, contrasena_hash, nombre_menor, fecha_nacimiento,
                                nombre_acudiente, cedula_acudiente, codigo_hash, intentos_fallidos, expira_en)
VALUES ('otro@test.com', 'otro_usr', 'hash', 'Otro Niño', '2016-01-01',
        'Otro Adulto', '2000000001', 'hash_otp', 0, DATE_ADD(NOW(), INTERVAL 10 MINUTE));

SELECT COUNT(*) AS total_pendientes FROM registro_pendiente;
-- Esperado: 1

SELECT correo, nombre_usuario FROM registro_pendiente WHERE nombre_menor = 'Otro Niño';
-- Esperado: 1 fila

ROLLBACK;

-- ---------------------------------------------------------------------------
-- U: UPDATE (simula intento fallido de verificación)
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO registro_pendiente (correo, nombre_usuario, contrasena_hash, nombre_menor, fecha_nacimiento,
                                nombre_acudiente, cedula_acudiente, codigo_hash, intentos_fallidos, expira_en)
VALUES ('fallidos@test.com', 'fallidos_usr', 'hash', 'Test', '2015-01-01',
        'Adulto', '3000000001', 'hash_otp', 0, DATE_ADD(NOW(), INTERVAL 10 MINUTE));

UPDATE registro_pendiente SET intentos_fallidos = intentos_fallidos + 1 WHERE correo = 'fallidos@test.com';
SELECT intentos_fallidos FROM registro_pendiente WHERE correo = 'fallidos@test.com';
-- Esperado: 1

ROLLBACK;

-- ---------------------------------------------------------------------------
-- D: DELETE (se consume al verificar → el service borra esta fila)
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO registro_pendiente (correo, nombre_usuario, contrasena_hash, nombre_menor, fecha_nacimiento,
                                nombre_acudiente, cedula_acudiente, codigo_hash, intentos_fallidos, expira_en)
VALUES ('consumir@test.com', 'consumir_usr', 'hash', 'A Borrar', '2015-01-01',
        'Borrar', '4000000001', 'hash_otp', 0, DATE_ADD(NOW(), INTERVAL 10 MINUTE));

DELETE FROM registro_pendiente WHERE correo = 'consumir@test.com';
SELECT COUNT(*) AS restantes FROM registro_pendiente WHERE correo = 'consumir@test.com';
-- Esperado: 0

ROLLBACK;

-- ---------------------------------------------------------------------------
-- CONSTRAINT: UNIQUE(correo) — duplicado
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO registro_pendiente (correo, nombre_usuario, contrasena_hash, nombre_menor, fecha_nacimiento,
                                nombre_acudiente, cedula_acudiente, codigo_hash, intentos_fallidos, expira_en)
VALUES ('unico@test.com', 'unico_pend', 'hash', 'Primero', '2015-01-01',
        'A', '5000000001', 'hash_otp', 0, DATE_ADD(NOW(), INTERVAL 10 MINUTE));

-- ERROR esperado: Duplicate entry 'unico@test.com' for key 'uq_registro_pendiente_correo'
INSERT INTO registro_pendiente (correo, nombre_usuario, contrasena_hash, nombre_menor, fecha_nacimiento,
                                nombre_acudiente, cedula_acudiente, codigo_hash, intentos_fallidos, expira_en)
VALUES ('unico@test.com', 'otro_pend', 'hash', 'Segundo', '2015-02-02',
        'B', '6000000001', 'hash_otp', 0, DATE_ADD(NOW(), INTERVAL 10 MINUTE));

ROLLBACK;

-- ---------------------------------------------------------------------------
-- CONSTRAINT: UNIQUE(nombre_usuario) — duplicado
-- ---------------------------------------------------------------------------
START TRANSACTION;

INSERT INTO registro_pendiente (correo, nombre_usuario, contrasena_hash, nombre_menor, fecha_nacimiento,
                                nombre_acudiente, cedula_acudiente, codigo_hash, intentos_fallidos, expira_en)
VALUES ('a@test.com', 'mismo_usr', 'hash', 'A', '2015-01-01',
        'A', '7000000001', 'hash_otp', 0, DATE_ADD(NOW(), INTERVAL 10 MINUTE));

-- ERROR esperado: Duplicate entry 'mismo_usr' for key 'uq_registro_pendiente_nombre_usuario'
INSERT INTO registro_pendiente (correo, nombre_usuario, contrasena_hash, nombre_menor, fecha_nacimiento,
                                nombre_acudiente, cedula_acudiente, codigo_hash, intentos_fallidos, expira_en)
VALUES ('b@test.com', 'mismo_usr', 'hash', 'B', '2015-02-02',
        'B', '8000000001', 'hash_otp', 0, DATE_ADD(NOW(), INTERVAL 10 MINUTE));

ROLLBACK;
