#Requires -Version 5.1
<#
scripts/Load-DotEnv.ps1 - Carga automatica de variables de entorno desde .env

Lee el archivo .env de la raiz del proyecto y exporta cada variable en la
sesion actual de PowerShell. Ignora comentarios (#) y lineas vacias.

Uso (desde otros scripts):
    . (Join-Path $PSScriptRoot "Load-DotEnv.ps1")
    Load-DotEnv

Uso (manual en terminal):
    . .\scripts\Load-DotEnv.ps1
    Load-DotEnv

Seguridad: .env esta en .gitignore y nunca se versiona. Las variables solo
viven en la sesion de PowerShell que invoca este script.
#>

function Load-DotEnv {
    $root = Split-Path -Parent $PSScriptRoot
    $envFile = Join-Path $root ".env"

    if (-not (Test-Path -LiteralPath $envFile)) {
        Write-Host "Advertencia: archivo .env no encontrado en $envFile" -ForegroundColor Yellow
        Write-Host "Usa .env.example como plantilla para crearlo." -ForegroundColor Yellow
        return
    }

    $lineas = Get-Content -LiteralPath $envFile -Encoding UTF8
    $cargadas = 0

    foreach ($linea in $lineas) {
        $linea = $linea.Trim()

        # Ignorar lineas vacias y comentarios
        if ([string]::IsNullOrWhiteSpace($linea)) { continue }
        if ($linea.StartsWith("#")) { continue }

        # Parsear KEY=VALUE
        $separatorIndex = $linea.IndexOf("=")
        if ($separatorIndex -lt 1) { continue }

        $key = $linea.Substring(0, $separatorIndex).Trim()
        $value = $linea.Substring($separatorIndex + 1).Trim()

        # Remover comillas envolventes si las hay
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
            ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }

        # Usar $env: directamente para compatibilidad con PowerShell 5.1
        Set-Item -Path "env:$key" -Value $value
        $cargadas++
    }

    Write-Host ".env cargado: $cargadas variables configuradas" -ForegroundColor Green
}


