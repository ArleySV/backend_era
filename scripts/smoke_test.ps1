#Requires -Version 5.1
<#
scripts/smoke_test.ps1 - Prueba de humo E2E (Base de Trazabilidad de Calidad, V11).

Flujo:
  1. Preflight: GET / para confirmar que el servidor está escuchando.
  2. Limpieza SQL del usuario de prueba (FK RESTRICT: registro_pendiente,
     configuracion -> acudiente -> usuario).
  3. POST /api/v1/auth/register   -> valida 201.
  4. POST /api/v1/auth/verify-email con codigo "123456" -> valida 200.
  5. Validacion post-test: confirmar filas vinculadas en usuario, acudiente y
     configuracion para test@example.com antes del PASS final.

Requisitos:
  - Servidor levantado (.\scripts\dev.ps1 o .\kotlin run) en http://localhost:$PORT
    con APP_DEV_MODE=true (OTP fijo 123456 y SMTP No-Op, ver V10/V10.1/V11).
  - Variables de entorno DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD
    (ver .env.example) para la limpieza y validacion SQL.
  - mysql.exe accesible en el PATH (MySQL Server 8.0).

Salida: PASS/FAIL por paso; exit code 0 si el flujo completo fue correcto.
#>
$ErrorActionPreference = "Stop"

$script:port = if ($env:PORT) { [int]$env:PORT } else { 8080 }
$script:base = "http://localhost:$($script:port)/api/v1/auth"

$CORREO     = "test@example.com"
$NOMBRE_USR = "test_user"
$CODIGO_OTP = "123456"

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
        # PS 5.1 decodifica .Content según la cabecera; sin charset asume ISO-8859-1.
        # Ktor responde UTF-8: se decodifican los bytes en crudo para ver acentos bien.
        $texto = [System.Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())
        return [pscustomobject]@{ Status = [int]$resp.StatusCode; Body = $texto }
    } catch {
        $code = $null
        if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
        $detalle = if ($_.ErrorDetails.Message) { $_.ErrorDetails.Message } else { $_.Exception.Message }
        return [pscustomobject]@{ Status = $code; Body = $detalle }
    }
}

# ---------------------------------------------------------------------------
# Paso 1: preflight del servidor
# ---------------------------------------------------------------------------
function Test-Servidor {
    Write-Host "[1/5] Preflight: servidor en http://localhost:$($script:port)..." -ForegroundColor Cyan
    try {
        $null = Invoke-WebRequest -UseBasicParsing -Method Get `
            -Uri "http://localhost:$($script:port)/" -TimeoutSec 5
        Write-Host "Servidor OK" -ForegroundColor Green
    } catch {
        # Una respuesta HTTP (aunque sea 4xx/5xx) implica que el servidor escucha.
        if ($_.Exception.Response) {
            Write-Host "Servidor OK (responde HTTP)" -ForegroundColor Green
        } else {
            Write-Host "Servidor no detectado. Ejecuta .\dev.ps1 primero." -ForegroundColor Red
            exit 1
        }
    }
}

# ---------------------------------------------------------------------------
# Paso 2: limpieza SQL (orden exacto por FK ON DELETE RESTRICT)
# ---------------------------------------------------------------------------
function Invoke-LimpiezaSql {
    Write-Host "[2/5] Limpieza SQL (${script:dbHost}:${script:dbPort}/${script:dbName})..." -ForegroundColor Cyan

    # registro_pendiente es independiente del usuario (se consume al verificar).
    $sql = "DELETE FROM registro_pendiente WHERE correo = '$CORREO';"

    # Si el usuario de prueba ya existe, borrar en orden de FK: configuracion -> acudiente -> usuario.
    $id = (& $mysql -h $script:dbHost -P $script:dbPort -u $script:dbUser $script:dbName -N -B `
        -e "SELECT id_usuario FROM usuario WHERE correo = '$CORREO';" 2>&1) | Select-Object -First 1
    if ($LASTEXITCODE -ne 0) { throw "Fallo al consultar el usuario de prueba: $id" }
    $id = ("$id").Trim()
    if ($id) {
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
# Paso 3: registro (campos exactos de RegisterRequestDto / diccionario de datos)
# ---------------------------------------------------------------------------
function Invoke-Registro {
    $bodyRegistro = @{
        nombreMenor         = "Test Menor"
        fechaNacimiento     = "2015-06-15"
        nombreAcudiente     = "Test Acudiente"
        cedulaAcudiente     = "1023456789"
        correo              = $CORREO
        nombreUsuario       = $NOMBRE_USR
        avatar              = "preset:1"
        contrasena          = "Abcdef1!"
        confirmarContrasena = "Abcdef1!"
    }

    Write-Host "[3/5] POST /register..." -ForegroundColor Cyan
    $r = Invoke-EraPost "/register" $bodyRegistro
    if ($r.Status -ne 201) {
        $detalle = if ($null -eq $r.Status) { "sin respuesta del servidor (¿levantado en :$($script:port)?)" } else { $r.Body }
        Write-Host "FAIL: esperaba 201, obtuvo $($r.Status). Detalle: $detalle" -ForegroundColor Red
        exit 1
    }
    Write-Host "201 OK. Respuesta: $($r.Body)" -ForegroundColor Green
}

# ---------------------------------------------------------------------------
# Paso 4: verificación con el OTP fijo de dev (123456)
# ---------------------------------------------------------------------------
function Invoke-Verificacion {
    $bodyVerify = @{
        correo = $CORREO
        codigo = $CODIGO_OTP
    }

    Write-Host "[4/5] POST /verify-email..." -ForegroundColor Cyan
    $r = Invoke-EraPost "/verify-email" $bodyVerify
    if ($r.Status -ne 200) {
        $detalle = if ($null -eq $r.Status) { "sin respuesta del servidor (¿levantado en :$($script:port)?)" } else { $r.Body }
        Write-Host "FAIL: esperaba 200, obtuvo $($r.Status). Detalle: $detalle" -ForegroundColor Red
        exit 1
    }
    Write-Host "200 OK. Respuesta: $($r.Body)" -ForegroundColor Green
}

# ---------------------------------------------------------------------------
# Paso 5: validación post-test en BD (filas vinculadas antes del PASS final)
# ---------------------------------------------------------------------------
function Invoke-ValidacionDb {
    Write-Host "[5/5] Validacion post-test en BD..." -ForegroundColor Cyan
    # 1:1: el JOIN interno solo produce fila si existen usuario + acudiente + configuracion.
    $sql = "SELECT COUNT(*) FROM usuario u " +
        "INNER JOIN acudiente a ON a.id_usuario = u.id_usuario " +
        "INNER JOIN configuracion c ON c.id_usuario = u.id_usuario " +
        "WHERE u.correo = '$CORREO';"
    $n = (& $mysql -h $script:dbHost -P $script:dbPort -u $script:dbUser $script:dbName -N -B -e $sql 2>&1) |
        Select-Object -First 1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Fallo en la validacion SQL: $n" -ForegroundColor Red
        exit 1
    }
    if (("$n").Trim() -ne "1") {
        Write-Host "FAIL: faltan filas vinculadas en usuario/acudiente/configuracion para $CORREO (count=$($n.Trim()))." -ForegroundColor Red
        exit 1
    }
    Write-Host "BD OK: usuario, acudiente y configuracion vinculados al correo." -ForegroundColor Green
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
Invoke-ValidacionDb

Write-Host "[FINAL] SMOKE TEST PASS: Register -> Verify con persistencia verificada en BD." -ForegroundColor Green
exit 0
