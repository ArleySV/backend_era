#Requires -Version 5.1
<#
scripts/password_reset_test.ps1 - Prueba manual del flujo completo del Módulo C
(Recuperación de contraseña, REQ-FUN-07, CU-03, HU-07).

Flujo:
  1. Preflight: GET / para confirmar que el servidor está escuchando.
  2. Limpieza SQL del usuario de prueba (FK RESTRICT: registro_pendiente,
     configuracion -> acudiente -> usuario).
  3. POST /register -> valida 201.
  4. POST /verify-email con "123456" -> valida 200 (deja el usuario ACTIVO).
  5. POST /password-reset/request  (correo registrado)     -> 200 genérico.
  6. POST /password-reset/request  (correo inexistente)    -> 200 IDÉNTICO (C-1).
  7. POST /password-reset/request  (inmediato, reenvío)     -> 429 OTP_RESEND_THROTTLED (C-2).
  8. POST /password-reset/verify   (código incorrecto)     -> 401 OTP_INVALID_OR_EXPIRED (P1).
  9. POST /password-reset/verify   (código 123456)         -> 200 + resetToken (C-3).
 10. POST /password-reset/confirm  (misma contraseña)      -> 409 PASSWORD_REUSED (CA5, no consume token).
 11. POST /password-reset/confirm  (nueva contraseña)      -> 200 (token consumido).
 12. POST /password-reset/confirm  (repetido, single-use)  -> 401 RESET_TOKEN_INVALID (C-3).
 13. POST /login con la NUEVA contraseña                  -> 200 + token (Módulo B).
 14. POST /login con la ANTERIOR contraseña               -> 401 INVALID_CREDENTIALS.

Requisitos:
  - Servidor levantado (.\kotlin run) en http://localhost:$PORT con APP_DEV_MODE=true
    (OTP fijo 123456 y SMTP No-Op). Ver README "Pruebas de Humo".
  - Variables de entorno DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD (ver .env.example)
    para la limpieza y validacion SQL.
  - mysql.exe accesible en el PATH (MySQL Server 8.0).

Salida: OK/FAIL por paso; exit code 0 si el flujo completo fue correcto.
#>
$ErrorActionPreference = "Stop"

$script:port = if ($env:PORT) { [int]$env:PORT } else { 8080 }
$script:base = "http://localhost:$($script:port)/api/v1/auth"

$CORREO       = "test@example.com"
$NOMBRE_USR   = "test_user"
$CODIGO_OTP   = "123456"
$CONTRASENA_ANTERIOR = "Abcdef1!"
$CONTRASENA_NUEVA    = "Nueva#2026"
$CONTRASENA_DEBIL    = "corta"

function Assert-DbEnv {
    $required = @("DB_HOST", "DB_PORT", "DB_NAME", "DB_USER", "DB_PASSWORD")
    $faltantes = @($required | Where-Object { [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_)) })
    if ($faltantes.Count -gt 0) {
        Write-Host "Faltan variables DB_*: $($faltantes -join ', ')" -ForegroundColor Red
        Write-Host "Define las variables de entorno de MySQL (ver .env.example) y reintenta." -ForegroundColor Yellow
        exit 1
    }
}

function Resolve-DbParams {
    Assert-DbEnv
    $script:dbHost = $env:DB_HOST
    $script:dbPort = if ($env:DB_PORT) { $env:DB_PORT } else { 3306 }
    $script:dbName = $env:DB_NAME
    $script:dbUser = $env:DB_USER
    $env:MYSQL_PWD = $env:DB_PASSWORD   # evita la contraseña en la línea de comandos
}

function Invoke-EraPost {
    param(
        [string]$Path,
        [hashtable]$Body
    )
    try {
        $resp = Invoke-WebRequest -Method Post -Uri ($script:base + $Path) `
            -ContentType "application/json; charset=utf-8" `
            -Body ($Body | ConvertTo-Json) -UseBasicParsing
        # PS 5.1 decodifica .Content según la cabecera; Ktor responde UTF-8: bytes en crudo.
        $texto = [System.Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())
        return [pscustomobject]@{ Status = [int]$resp.StatusCode; Body = $texto }
    } catch {
        $code = $null
        if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
        $detalle = if ($_.ErrorDetails.Message) { $_.ErrorDetails.Message } else { $_.Exception.Message }
        return [pscustomobject]@{ Status = $code; Body = $detalle }
    }
}

# Assert genérico: espera un status y, opcionalmente, que el body contenga una subcadena.
function Assert-Era {
    param(
        [string]$Etiqueta,
        [pscustomobject]$Resp,
        [int]$Esperado,
        [string]$Contiene = $null,
        [string]$NoContiene = $null
    )
    if ($Resp.Status -ne $Esperado) {
        Write-Host "FAIL [$Etiqueta]: esperaba $Esperado, obtuvo $($Resp.Status). Detalle: $($Resp.Body)" -ForegroundColor Red
        exit 1
    }
    if ($Contiene -and -not $Resp.Body.Contains($Contiene)) {
        Write-Host "FAIL [$Etiqueta]: el body no contiene '$Contiene'. Body: $($Resp.Body)" -ForegroundColor Red
        exit 1
    }
    if ($NoContiene -and $Resp.Body.Contains($NoContiene)) {
        Write-Host "FAIL [$Etiqueta]: el body no debería contener '$NoContiene'. Body: $($Resp.Body)" -ForegroundColor Red
        exit 1
    }
    Write-Host "OK   [$Etiqueta]: $Esperado." -ForegroundColor Green
}

# ---------------------------------------------------------------------------
# Paso 1: preflight del servidor
# ---------------------------------------------------------------------------
function Test-Servidor {
    Write-Host "[1/15] Preflight: servidor en http://localhost:$($script:port)..." -ForegroundColor Cyan
    try {
        $null = Invoke-WebRequest -UseBasicParsing -Method Get `
            -Uri "http://localhost:$($script:port)/" -TimeoutSec 5
        Write-Host "Servidor OK" -ForegroundColor Green
    } catch {
        if ($_.Exception.Response) {
            Write-Host "Servidor OK (responde HTTP)" -ForegroundColor Green
        } else {
            Write-Host "Servidor no detectado. Levanta el servidor (.\kotlin run) primero." -ForegroundColor Red
            exit 1
        }
    }
}

# ---------------------------------------------------------------------------
# Paso 2: limpieza SQL (orden exacto por FK ON DELETE RESTRICT)
# ---------------------------------------------------------------------------
function Invoke-LimpiezaSql {
    Write-Host "[2/15] Limpieza SQL (${script:dbHost}:${script:dbPort}/${script:dbName})..." -ForegroundColor Cyan
    $sql = "DELETE FROM registro_pendiente WHERE correo = '$CORREO';"
    $id = (& $mysql -h $script:dbHost -P $script:dbPort -u $script:dbUser $script:dbName -N -B `
        -e "SELECT id_usuario FROM usuario WHERE correo = '$CORREO';" 2>&1) | Select-Object -First 1
    if ($LASTEXITCODE -ne 0) { throw "Fallo al consultar el usuario de prueba: $id" }
    $id = ("$id").Trim()
    if ($id) {
        # Hijas del usuario (FK ON DELETE RESTRICT) ANTES de usuario:
        # codigo_verificacion y tokens_reseteo (flujo C) + configuracion y acudiente.
        $sql += "DELETE FROM codigo_verificacion WHERE id_usuario = $id;"
        $sql += "DELETE FROM tokens_reseteo WHERE id_usuario = $id;"
        $sql += "DELETE FROM configuracion WHERE id_usuario = $id;"
        $sql += "DELETE FROM acudiente WHERE id_usuario = $id;"
        $sql += "DELETE FROM usuario WHERE id_usuario = $id;"
    }
    $salida = & $mysql -h $script:dbHost -P $script:dbPort -u $script:dbUser $script:dbName -e $sql 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Fallo en la limpieza SQL: $salida" -ForegroundColor Red
        exit 1
    }
    Write-Host "Limpieza OK" -ForegroundColor Green
}

# ---------------------------------------------------------------------------
# Pasos 3-4: registrar y verificar (deja el usuario ACTIVO para el flujo)
# ---------------------------------------------------------------------------
function Invoke-Registro {
    $body = @{
        nombreMenor         = "Test Menor"
        fechaNacimiento     = "2015-06-15"
        nombreAcudiente     = "Test Acudiente"
        cedulaAcudiente     = "1023456789"
        correo              = $CORREO
        nombreUsuario       = $NOMBRE_USR
        avatar              = "preset:1"
        contrasena          = $CONTRASENA_ANTERIOR
        confirmarContrasena = $CONTRASENA_ANTERIOR
    }
    Write-Host "[3/15] POST /register..." -ForegroundColor Cyan
    $r = Invoke-EraPost "/register" $body
    if ($r.Status -ne 201) {
        Write-Host "FAIL [register]: esperaba 201, obtuvo $($r.Status). Detalle: $($r.Body)" -ForegroundColor Red
        exit 1
    }
    Write-Host "OK   [register]: 201." -ForegroundColor Green
}

function Invoke-Verificacion {
    Write-Host "[4/15] POST /verify-email (codigo $CODIGO_OTP)..." -ForegroundColor Cyan
    $r = Invoke-EraPost "/verify-email" @{ correo = $CORREO; codigo = $CODIGO_OTP }
    Assert-Era -Etiqueta "verify-email" -Resp $r -Esperado 200
}

# ---------------------------------------------------------------------------
# Flujo de recuperación (Módulo C)
# ---------------------------------------------------------------------------
function Invoke-RequestGenerico {
    Write-Host "[5/15] POST /password-reset/request (correo registrado)..." -ForegroundColor Cyan
    $r = Invoke-EraPost "/password-reset/request" @{ correo = $CORREO }
    Assert-Era -Etiqueta "request (registrado)" -Resp $r -Esperado 200 `
        -Contiene "Si el correo está registrado"
    return $r.Body
}

function Invoke-RequestAntiEnumeracion {
    Write-Host "[6/15] POST /password-reset/request (correo inexistente)..." -ForegroundColor Cyan
    $r = Invoke-EraPost "/password-reset/request" @{ correo = "nadie@example.com" }
    Assert-Era -Etiqueta "request (inexistente)" -Resp $r -Esperado 200 `
        -Contiene "Si el correo está registrado"
    return $r.Body
}

function Invoke-RequestThrottle {
    Write-Host "[7/15] POST /password-reset/request (reenvío inmediato)..." -ForegroundColor Cyan
    $r = Invoke-EraPost "/password-reset/request" @{ correo = $CORREO }
    Assert-Era -Etiqueta "request (throttle)" -Resp $r -Esperado 429 `
        -Contiene "OTP_RESEND_THROTTLED"
}

function Invoke-VerifyIncorrecto {
    Write-Host "[8/15] POST /password-reset/verify (código incorrecto)..." -ForegroundColor Cyan
    $r = Invoke-EraPost "/password-reset/verify" @{ correo = $CORREO; codigo = "999999" }
    Assert-Era -Etiqueta "verify (incorrecto)" -Resp $r -Esperado 401 `
        -Contiene "OTP_INVALID_OR_EXPIRED"
}

function Invoke-VerifyCorrecto {
    Write-Host "[9/15] POST /password-reset/verify (código $CODIGO_OTP)..." -ForegroundColor Cyan
    $r = Invoke-EraPost "/password-reset/verify" @{ correo = $CORREO; codigo = $CODIGO_OTP }
    Assert-Era -Etiqueta "verify (correcto)" -Resp $r -Esperado 200 -Contiene "resetToken"
    return ($r.Body | ConvertFrom-Json).resetToken
}

function Invoke-ConfirmReutilizada {
    param([string]$TokenPuente)
    Write-Host "[10/15] POST /password-reset/confirm (misma contraseña anterior)..." -ForegroundColor Cyan
    $r = Invoke-EraPost "/password-reset/confirm" @{
        resetToken          = $TokenPuente
        nuevaContrasena     = $CONTRASENA_ANTERIOR
        confirmarContrasena = $CONTRASENA_ANTERIOR
    }
    Assert-Era -Etiqueta "confirm (reutilizada)" -Resp $r -Esperado 409 `
        -Contiene "PASSWORD_REUSED"
}

function Invoke-ConfirmOk {
    param([string]$TokenPuente)
    Write-Host "[11/15] POST /password-reset/confirm (nueva contraseña)..." -ForegroundColor Cyan
    $r = Invoke-EraPost "/password-reset/confirm" @{
        resetToken          = $TokenPuente
        nuevaContrasena     = $CONTRASENA_NUEVA
        confirmarContrasena = $CONTRASENA_NUEVA
    }
    Assert-Era -Etiqueta "confirm (ok)" -Resp $r -Esperado 200 `
        -Contiene "Contraseña actualizada"
}

function Invoke-ConfirmSingleUse {
    param([string]$TokenPuente)
    Write-Host "[12/15] POST /password-reset/confirm (token repetido, single-use)..." -ForegroundColor Cyan
    $r = Invoke-EraPost "/password-reset/confirm" @{
        resetToken          = $TokenPuente
        nuevaContrasena     = $CONTRASENA_NUEVA
        confirmarContrasena = $CONTRASENA_NUEVA
    }
    Assert-Era -Etiqueta "confirm (single-use)" -Resp $r -Esperado 401 `
        -Contiene "RESET_TOKEN_INVALID"
}

# ---------------------------------------------------------------------------
# Validación final con login (Módulo B)
# ---------------------------------------------------------------------------
function Invoke-LoginNueva {
    Write-Host "[13/15] POST /login (nueva contraseña)..." -ForegroundColor Cyan
    $r = Invoke-EraPost "/login" @{ usuarioOCorreo = $CORREO; contrasena = $CONTRASENA_NUEVA }
    Assert-Era -Etiqueta "login (nueva)" -Resp $r -Esperado 200 -Contiene "token"
}

function Invoke-LoginAnterior {
    Write-Host "[14/15] POST /login (contraseña anterior)..." -ForegroundColor Cyan
    $r = Invoke-EraPost "/login" @{ usuarioOCorreo = $CORREO; contrasena = $CONTRASENA_ANTERIOR }
    Assert-Era -Etiqueta "login (anterior)" -Resp $r -Esperado 401 `
        -Contiene "INVALID_CREDENTIALS"
}

function Invoke-AntiEnumeracionIdentica {
    param([string]$BodyRegistrado)
    Write-Host "[15/15] Las respuestas de request deben ser idénticas (C-1)..." -ForegroundColor Cyan
    $r = Invoke-EraPost "/password-reset/request" @{ correo = "nadie@example.com" }
    if ($r.Body -ne $BodyRegistrado) {
        Write-Host "FAIL [anti-enumeración idéntica]: bodies distintos. $($r.Body)" -ForegroundColor Red
        exit 1
    }
    Write-Host "OK   [anti-enumeración idéntica]: mismas respuesta y mensaje." -ForegroundColor Green
}

# ---------------------------------------------------------------------------
# Ejecución
# ---------------------------------------------------------------------------
$mysql = (Get-Command mysql.exe -ErrorAction Stop).Source

Resolve-DbParams
Test-Servidor
Invoke-LimpiezaSql
Invoke-Registro
Invoke-Verificacion

$bodyRegistrado = Invoke-RequestGenerico
Invoke-RequestAntiEnumeracion
Invoke-RequestThrottle
Invoke-VerifyIncorrecto
$tokenPuente = Invoke-VerifyCorrecto
Invoke-ConfirmReutilizada $tokenPuente
Invoke-ConfirmOk $tokenPuente
Invoke-ConfirmSingleUse $tokenPuente
Invoke-LoginNueva
Invoke-LoginAnterior
Invoke-AntiEnumeracionIdentica $bodyRegistrado

Write-Host "[FINAL] PASSWORD RESET TEST PASS: flujo completo C + login con la nueva contraseña." -ForegroundColor Green
exit 0
