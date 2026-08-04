#Requires -Version 5.1
<#
scripts/dev.ps1 - Servidor de desarrollo con recarga automatica (Amper)

Vigila src/ y resources/; al detectar cambios recompila (.\kotlin build) y,
si compila, reinicia el servidor (.\kotlin run).

Uso:
  .\scripts\dev.ps1             inicia y recarga al cambiar codigo
  .\scripts\dev.ps1 -Once       compila y corre una sola vez, sin vigilar
  Ctrl+C para detener

Nota: las variables de entorno (PORT, DB_*, JWT_SECRET, SMTP_*) deben estar
definidas en esta terminal antes de ejecutar (ver .env.example).
#>
param([switch]$Once)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$kotlin = Join-Path $root "kotlin.bat"

function Start-Server {
    Write-Host "`n=== Arrancando servidor (.\kotlin run) ===" -ForegroundColor Cyan
    Start-Process -FilePath "cmd.exe" -ArgumentList "/c", "`"$kotlin`" run" -WorkingDirectory $root -NoNewWindow -PassThru
}

function Stop-Server([System.Diagnostics.Process]$proc) {
    if ($null -ne $proc -and -not $proc.HasExited) {
        & taskkill /PID $proc.Id /T /F 2>$null | Out-Null
        $proc.WaitForExit()
    }
}

$server = $null
$lastBuild = Get-Date
$watched = @("src", "resources")

try {
    while ($true) {
        if ($Once) {
            & $kotlin build
            if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
            & $kotlin run
            exit $LASTEXITCODE
        }

        $changed = Get-ChildItem -Path $watched -Recurse -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '\.(kt|kts|yaml|yml|xml|sql|properties)$' -and $_.LastWriteTimeUtc -gt $lastBuild } |
            Select-Object -First 1

        if ($null -ne $changed) {
            $lastBuild = Get-Date
            Write-Host "`nCambio detectado: $($changed.FullName)" -ForegroundColor Yellow
            Stop-Server $server
            $server = $null
            Write-Host "Compilando (.\kotlin build)..." -ForegroundColor Cyan
            & $kotlin build
            if ($LASTEXITCODE -eq 0) {
                $server = Start-Server
            } else {
                Write-Host "Compilacion fallida; servidor no reiniciado." -ForegroundColor Red
            }
        } elseif ($null -eq $server -or $server.HasExited) {
            $lastBuild = Get-Date
            $server = Start-Server
        }

        Start-Sleep -Seconds 1
    }
} finally {
    Stop-Server $server
}
