#Requires -Version 5.1
<#
scripts/config_smoke.ps1 - Smoke test de configuracion de produccion (Parte 1, fase final).

Para cada variable critica que application.yaml requiere, arranca la app real (kotlin.bat
run) con ESA variable AUSENTE una por vez y verifica que el arranque falla rapido (fail-fast)
con un mensaje claro que nombra la variable. Cubre las 13 variables criticas + PORT:
  DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD, JWT_SECRET, SMTP_HOST, SMTP_PORT,
  SMTP_USER, SMTP_PASSWORD, SMTP_FROM, AVATAR_STORAGE_DIR y PORT.
Mas el caso especial JWT_SECRET definida pero VACIA.

Mecanismo verificado con evidencia (2026-08-12): la resolucion de ${VAR} ocurre en la
construccion de YamlConfig (Ktor 3.4.3, YamlConfig.kt), ANTES de Application.module() y de
cualquier conexion a MySQL. Ante ausencia lanza:
  ApplicationConfigurationException: Required environment variable "<VAR>" not found
  and no default value is present
Ese es el marcador esperado. Si el proceso sigue vivo al vencerse el timeout, la app ARRANCO
con la variable faltante: es un fallback silencioso no documentado y el smoke falla.

Requisitos: variables del .env presentes (el script las carga a su entorno desde el archivo
.env en la raiz; tambien se pueden predefinir en la terminal). No requiere MySQL.

Log de evidencia con timestamp en test-results/config_smoke_YYYYMMDD_HHmmss.log.
Nunca se loguean valores de variables: solo nombres y marcadores.

Salida: exit code 0 solo si TODOS los casos fallan rapido con su marcador esperado.
#>
$ErrorActionPreference = "Stop"

$script:Root    = Split-Path -Parent $PSScriptRoot
$script:EnvFile = Join-Path $script:Root ".env"
$script:LogsDir = Join-Path $script:Root "test-results"
$script:Stamp   = Get-Date -Format "yyyyMMdd_HHmmss"
$script:LogFile = Join-Path $script:LogsDir "config_smoke_$($script:Stamp).log"
$script:RawDir  = Join-Path $script:LogsDir "config_smoke_$($script:Stamp)"
$script:Kotlin  = Join-Path $script:Root "kotlin.bat"
$script:TimeoutSec = 180

$script:failures = @()

if (-not (Test-Path -LiteralPath $script:LogsDir)) { New-Item -ItemType Directory -Path $script:LogsDir | Out-Null }
New-Item -ItemType Directory -Path $script:RawDir | Out-Null

function Write-EraLog {
    param([string]$Text, [string]$Color = "Gray")
    $line = "[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] $Text"
    Add-Content -LiteralPath $script:LogFile -Value $line -Encoding UTF8
    Write-Host $Text -ForegroundColor $Color
}

# Aplica las variables del .env al entorno del proceso (valores nunca se loguean).
function Apply-EnvFromFile {
    if (-not (Test-Path -LiteralPath $script:EnvFile)) {
        throw "No existe $($script:EnvFile). Crea uno a partir de .env.example."
    }
    foreach ($line in Get-Content -LiteralPath $script:EnvFile) {
        if ($line -match '^\s*#') { continue }
        if ($line -match '^([A-Z_][A-Z0-9_]*)=(.*)$') {
            [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], "Process")
        }
    }
}

# (nombre de variable, marcador esperado en el log de arranque)
$script:casos = @(
    @{ Var = "PORT";                   Marker = 'Required environment variable "PORT" not found' }
    @{ Var = "DB_HOST";                Marker = 'Required environment variable "DB_HOST" not found' }
    @{ Var = "DB_PORT";                Marker = 'Required environment variable "DB_PORT" not found' }
    @{ Var = "DB_NAME";                Marker = 'Required environment variable "DB_NAME" not found' }
    @{ Var = "DB_USER";                Marker = 'Required environment variable "DB_USER" not found' }
    @{ Var = "DB_PASSWORD";            Marker = 'Required environment variable "DB_PASSWORD" not found' }
    @{ Var = "JWT_SECRET";             Marker = 'Required environment variable "JWT_SECRET" not found' }
    @{ Var = "SMTP_HOST";              Marker = 'Required environment variable "SMTP_HOST" not found' }
    @{ Var = "SMTP_PORT";              Marker = 'Required environment variable "SMTP_PORT" not found' }
    @{ Var = "SMTP_USER";              Marker = 'Required environment variable "SMTP_USER" not found' }
    @{ Var = "SMTP_PASSWORD";          Marker = 'Required environment variable "SMTP_PASSWORD" not found' }
    @{ Var = "SMTP_FROM";              Marker = 'Required environment variable "SMTP_FROM" not found' }
    @{ Var = "AVATAR_STORAGE_DIR";     Marker = 'Required environment variable "AVATAR_STORAGE_DIR" not found' }
    @{ Var = "JWT_SECRET_VACIA";       Marker = 'JWT_SECRET' }  # caso especial: definida pero vacia
)

# Ejecuta un arranque y decide PASS/FAIL. Devuelve $true si fallo rapido con el marcador.
function Test-Boot {
    param([string]$Nombre, [string]$Marker)
    $stdout = Join-Path $script:RawDir "$Nombre.out.log"
    $stderr = Join-Path $script:RawDir "$Nombre.err.log"
    Remove-Item $stdout, $stderr -ErrorAction SilentlyContinue
    $p = Start-Process -FilePath "cmd.exe" -ArgumentList "/c", "`"$($script:Kotlin)`" run" `
        -WorkingDirectory $script:Root -RedirectStandardOutput $stdout -RedirectStandardError $stderr `
        -PassThru -WindowStyle Hidden
    $exited = $p.WaitForExit($script:TimeoutSec * 1000)
    if (-not $exited) {
        & taskkill /PID $p.Id /T /F 2>$null | Out-Null
        $p.WaitForExit()
        return $false, "La app NO aborto: siguio viva tras $($script:TimeoutSec)s con '$Nombre' ausente (fallback silencioso)."
    }
    $all = ""
    if (Test-Path -LiteralPath $stdout) { $all += Get-Content -LiteralPath $stdout -Raw }
    if (Test-Path -LiteralPath $stderr) { $all += "`n" + (Get-Content -LiteralPath $stderr -Raw) }
    if ($all -match [regex]::Escape($Marker)) {
        return $true, ""
    }
    $firstError = ($all -split "`r?`n" | Where-Object { $_ -match 'Exception|ERROR|error:' } | Select-Object -First 1)
    return $false, "El arranque fallo pero sin el marcador esperado '$Marker'. Primer rastro: $firstError"
}

Write-EraLog "=== ERA - Smoke test de configuracion ===" "Cyan"
Write-EraLog "Inicio: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')."
Write-EraLog "Casos a verificar: $($script:casos.Count)."

foreach ($caso in $script:casos) {
    Apply-EnvFromFile
    if ($caso.Var -eq "JWT_SECRET_VACIA") {
        [Environment]::SetEnvironmentVariable("JWT_SECRET", "", "Process")
    } else {
        Remove-Item "Env:$($caso.Var)" -ErrorAction SilentlyContinue
    }
    $ok, $motivo = Test-Boot -Nombre $caso.Var -Marker $caso.Marker
    if ($ok) {
        Write-EraLog "[PASS] $($caso.Var) ausente -> fail-fast con mensaje claro." "Green"
    } else {
        Write-EraLog "[FAIL] $($caso.Var): $motivo" "Red"
        $script:failures += $caso.Var
    }
}

$total = $script:casos.Count
$failed = $script:failures.Count
$color = if ($failed -gt 0) { "Red" } else { "Green" }
Write-EraLog "Resumen: $($total - $failed)/$total casos pasados ($failed fallidos)." $color
Write-EraLog "Log de evidencia: $($script:LogFile)."
Write-EraLog "Logs crudos por caso: $($script:RawDir)."
Write-EraLog "Fin: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')."

if ($failed -eq 0) {
    Write-EraLog "RESULTADO: PASADO" "Green"
    exit 0
} else {
    Write-EraLog "RESULTADO: FALLIDO" "Red"
    exit 1
}
