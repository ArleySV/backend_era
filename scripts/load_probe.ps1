#Requires -Version 5.1
<#
scripts/load_probe.ps1 - Sonda de carga (Parte 2 de la fase final del plan de testing).

Ejecuta la clase com.era.backend.load.LoadProbeTest (gated por ERA_LOAD_PROBE=true)
contra la base de pruebas era_db_test (nunca era_db). La sonda levanta un servidor
Ktor REAL embebido (wiring completo de Application.module()) con un pool Hikari y
mide cuatro escenarios bajo carga creciente:

  - POST /api/v1/auth/login con contrasena incorrecta (bcrypt coste 11 real).
  - POST /api/v1/auth/login con contrasena incorrecta y usuario INEXISTENTE
    (mismo coste 11; mide la paridad anti-enumeracion B-4).
  - POST /api/v1/auth/login con cuenta bloqueada (fast-path B-2, sin bcrypt).
  - GET  /api/v1/progress/sync autenticado (lecturas Exposed sobre el pool).

Criterio REQ-NF-01 (p95 < 3000 ms): se valida con hard-assert sobre progress-sync y
login-bloqueado; los escenarios bcrypt se reportan como hallazgo si exceden el techo.

Genera un log de evidencia con timestamp en
test-results/load_probe_YYYYMMDD_HHmmss.log (lineas [LOADPROBE]) y guarda el output
crudo de la suite en test-results/load_probe_YYYYMMDD_HHmmss.raw.log (gitignored).

Requisitos:
  - era_db_test YA creada y migrada (Flyway V1+V2+V3). El script NO crea ni migra.
  - Variables de entorno: DB_HOST, DB_PORT, DB_USER, DB_PASSWORD (ver .env.example).
  - mysql.exe en el PATH (MySQL Server 8.0).

Nunca se escribe en el log contrasenas ni valores de .env.

Salida: exit code 0 solo si el preflight confirmo era_db_test y TODOS los tests pasan.
#>
$ErrorActionPreference = "Stop"

$script:Root      = Split-Path -Parent $PSScriptRoot
$script:TestDb    = "era_db_test"                 # Unica base objetivo; nunca era_db
$script:LogsDir   = Join-Path $script:Root "test-results"
$script:Stamp     = Get-Date -Format "yyyyMMdd_HHmmss"
$script:LogFile   = Join-Path $script:LogsDir "load_probe_$($script:Stamp).log"
$script:RawFile   = Join-Path $script:LogsDir "load_probe_$($script:Stamp).raw.log"
$script:TestClass = "com.era.backend.load.*"
$script:failMsg   = @{}
$script:rawLines  = @()
$script:testExit  = -1
$script:testDuration = [TimeSpan]::Zero

# Guard de paranoia: el objetivo es una constante, pero nunca debe ser era_db.
if ($script:TestDb -eq "era_db") {
    Write-Host "ERROR: el script nunca debe apuntar a la base de produccion era_db." -ForegroundColor Red
    exit 1
}

# Crear el directorio de logs de evidencia (test-results/) si no existe.
if (-not (Test-Path -LiteralPath $script:LogsDir)) {
    New-Item -ItemType Directory -Path $script:LogsDir | Out-Null
}

function Write-EraLog {
    param([string]$Text, [string]$Color = "Gray")
    $line = "[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] $Text"
    Add-Content -LiteralPath $script:LogFile -Value $line -Encoding UTF8
    Write-Host $Text -ForegroundColor $Color
}

function Assert-DbEnv {
    $required = @("DB_HOST", "DB_PORT", "DB_USER", "DB_PASSWORD")
    $missing = @($required | Where-Object { [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_)) })
    if ($missing.Count -gt 0) {
        Write-EraLog "ERROR: faltan variables de entorno DB_*: $($missing -join ', '). Definelas (ver .env.example) y reintenta." "Red"
        exit 1
    }
    if ($null -eq (Get-Command mysql.exe -ErrorAction SilentlyContinue)) {
        Write-EraLog "ERROR: mysql.exe no esta en el PATH (MySQL Server 8.0)." "Red"
        exit 1
    }
}

# Preflight: confirma la conexion a era_db_test ANTES de ejecutar la sonda.
function Test-ConexionPruebas {
    Write-EraLog "Preflight: verificando conexion a $($script:TestDb)..." "Cyan"
    $mysql = (Get-Command mysql.exe -ErrorAction Stop).Source
    $host_ = $env:DB_HOST
    $port_ = if ($env:DB_PORT) { $env:DB_PORT } else { "3306" }
    $prevPwd = $env:MYSQL_PWD
    $env:MYSQL_PWD = $env:DB_PASSWORD   # evita la contrasena en la linea de comandos y en el log
    try {
        & $mysql -h $host_ -P $port_ -u $env:DB_USER -N -B -e "SELECT DATABASE();" $script:TestDb 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "No se pudo conectar a $($script:TestDb). Revisa DB_* y que el usuario tenga privilegios sobre la base de pruebas."
        }
        $db = & $mysql -h $host_ -P $port_ -u $env:DB_USER -N -B -e "SELECT DATABASE();" $script:TestDb 2>&1
        $dbName = ("$($db | Select-Object -Last 1)").Trim()
        if ($dbName -ne $script:TestDb) {
            throw "La conexion activa devolvio '$dbName' y debe ser '$($script:TestDb)'. Abortando: nunca se corren pruebas contra otra base."
        }
        Write-EraLog "Preflight OK: conexion activa = $dbName ($host_`:$port_)." "Green"

        $count = & $mysql -h $host_ -P $port_ -u $env:DB_USER -N -B -e "SELECT COUNT(*) FROM flyway_schema_history;" $script:TestDb 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "Fallo al consultar flyway_schema_history en $($script:TestDb)."
        }
        $n = ("$($count | Select-Object -Last 1)").Trim()
        if ($n -ne "3") {
            throw "flyway_schema_history tiene $n migracion(es); se esperaba 3 (V1+V2+V3). Migra era_db_test antes de correr."
        }
        Write-EraLog "Preflight OK: flyway_schema_history = V1,V2,V3 (3 migraciones)." "Green"
    } finally {
        if ($null -eq $prevPwd) { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue }
        else { $env:MYSQL_PWD = $prevPwd }
    }
}

function Invoke-Suite {
    Write-EraLog "Ejecutando sonda de carga ($($script:TestClass))..." "Cyan"
    $env:DB_NAME = $script:TestDb
    $env:TEST_DB_NAME = $script:TestDb
    $env:ERA_LOAD_PROBE = "true"   # gate de la sonda; sin esto el test queda skipped
    $kotlinBat = Join-Path $script:Root "kotlin.bat"
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $output = & $kotlinBat test --format=teamcity --include-classes=$script:TestClass 2>&1
        $script:testExit = $LASTEXITCODE
    } finally {
        $sw.Stop()
        Remove-Item Env:ERA_LOAD_PROBE -ErrorAction SilentlyContinue
    }
    $script:testDuration = $sw.Elapsed
    $script:rawLines = @($output | ForEach-Object { $_.ToString() })
    $script:rawLines | Set-Content -LiteralPath $script:RawFile -Encoding UTF8
}

# Decodifica los valores TeamCity (mismo algoritmo que integration_test.ps1).
function Decode-TeamCity {
    param([string]$Value)
    if ([string]::IsNullOrEmpty($Value)) { return "" }
    $sb = New-Object System.Text.StringBuilder
    $i = 0
    while ($i -lt $Value.Length) {
        $c = $Value[$i]
        if ($c -eq '|' -and ($i + 1) -lt $Value.Length) {
            $n = $Value[$i + 1]
            switch ($n) {
                'n'  { [void]$sb.Append("`n"); $i += 2; continue }
                'r'  { [void]$sb.Append("`r"); $i += 2; continue }
                '['  { [void]$sb.Append('[');  $i += 2; continue }
                ']'  { [void]$sb.Append(']');  $i += 2; continue }
                "'"  { [void]$sb.Append("'");  $i += 2; continue }
                '"'  { [void]$sb.Append('"');  $i += 2; continue }
                '|'  { [void]$sb.Append('|');  $i += 2; continue }
                default { [void]$sb.Append($c); $i += 1 }
            }
        } else {
            [void]$sb.Append($c)
            $i += 1
        }
    }
    return $sb.ToString()
}

# Extrae las lineas [LOADPROBE] del stdout de la suite (vienen como testStdOut TeamCity).
# El atributo `out` termina con `' flowId='` (el runner anade flowId/displayName tras el).
function Get-LoadProbeLineas {
    $result = @()
    foreach ($line in $script:rawLines) {
        if ($line -match "^##teamcity\[testStdOut name='(?<name>.*?)' out='(?<out>.*?)' flowId='") {
            $decoded = Decode-TeamCity $Matches['out']
            foreach ($l in ($decoded -split "`n")) {
                if ($l -match "^\[LOADPROBE\]") { $result += $l.TrimEnd("`r") }
            }
        }
    }
    return $result
}

function Get-EvidenciaTests {
    $statuses = [ordered]@{}
    foreach ($line in $script:rawLines) {
        if ($line -match "^##teamcity\[testStarted name='(?<name>.*?)'") {
            $name = Decode-TeamCity $Matches['name']
            if (-not $statuses.Contains($name)) { $statuses[$name] = 'PASS' }
        }
        elseif ($line -match "^##teamcity\[testFailed name='(?<name>.*?)' message='(?<msg>.*?)'") {
            $name = Decode-TeamCity $Matches['name']
            $statuses[$name] = 'FAIL'
            $script:failMsg[$name] = Decode-TeamCity $Matches['msg']
        }
        elseif ($line -match "^##teamcity\[testIgnored name='(?<name>.*?)'") {
            $name = Decode-TeamCity $Matches['name']
            $statuses[$name] = 'IGNORED'
        }
        elseif ($line -match "^##teamcity\[testFinished name='(?<name>.*?)'") {
            $name = Decode-TeamCity $Matches['name']
            if (-not $statuses.Contains($name)) { $statuses[$name] = 'PASS' }
        }
    }
    return $statuses
}

function Write-Evidencia {
    $statuses = Get-EvidenciaTests
    if ($statuses.Count -eq 0) {
        Write-EraLog "ADVERTENCIA: no se pudieron extraer resultados por test del output de la suite." "Yellow"
    }
    $passed = 0; $failed = 0; $ignored = 0
    foreach ($name in $statuses.Keys) {
        switch ($statuses[$name]) {
            'PASS' {
                $passed++
                Write-EraLog "[PASS] $name" "Green"
            }
            'IGNORED' {
                $ignored++
                Write-EraLog "[IGNORED] $name" "Yellow"
            }
            default {
                $failed++
                Write-EraLog "[FAIL] $name" "Red"
                if ($script:failMsg.ContainsKey($name)) {
                    Write-EraLog "  Mensaje: $($script:failMsg[$name])" "Red"
                }
            }
        }
    }
    $total = $statuses.Count
    $color = if ($failed -gt 0) { "Red" } else { "Green" }
    Write-EraLog "Resumen: $passed/$total tests pasados ($failed fallidos, $ignored ignorados)." $color
    return $failed
}

# ---------------------------------------------------------------------------
# Ejecucion
# ---------------------------------------------------------------------------
Write-EraLog "=== ERA - Sonda de carga (Parte 2, fase final) ===" "Cyan"
Write-EraLog "Inicio: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')."
Write-EraLog "Base objetivo: $($script:TestDb) (nunca era_db)."

Assert-DbEnv
Test-ConexionPruebas
Invoke-Suite
$failedCount = Write-Evidencia

# Evidencia de mediciones [LOADPROBE] (si la sonda corrio y emitio lineas).
$probeLines = Get-LoadProbeLineas
if ($probeLines.Count -gt 0) {
    Add-Content -LiteralPath $script:LogFile -Value "" -Encoding UTF8
    Add-Content -LiteralPath $script:LogFile -Value "=== Mediciones [LOADPROBE] ===" -Encoding UTF8
    foreach ($l in $probeLines) {
        Add-Content -LiteralPath $script:LogFile -Value $l -Encoding UTF8
        Write-Host $l
    }
} elseif ($script:testExit -eq 0) {
    Write-EraLog "ADVERTENCIA: la suite paso pero no se emitieron lineas [LOADPROBE]. Revisa el raw log." "Yellow"
}

$seconds = [math]::Round($script:testDuration.TotalSeconds, 1)
Write-EraLog "Exit code de la suite: $($script:testExit)."
Write-EraLog "Duracion total: $seconds s."
Write-EraLog "Log de evidencia: $($script:LogFile)."
Write-EraLog "Fin: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')."

$ok = ($failedCount -eq 0) -and ($script:testExit -eq 0) -and ($probeLines.Count -gt 0)
if ($ok) {
    Write-EraLog "RESULTADO: PASADO" "Green"
    exit 0
} else {
    Write-EraLog "RESULTADO: FALLIDO" "Red"
    exit 1
}
