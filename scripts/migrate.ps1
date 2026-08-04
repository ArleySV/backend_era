#Requires -Version 5.1
<#
scripts/migrate.ps1 - Ejecuta las migraciones Flyway sin levantar el servidor.

Uso:
  .\scripts\migrate.ps1

Reutiliza la configuracion del servidor (application.yaml + ${VAR}):
  .\kotlin run --main-class=com.era.backend.database.MigrateRunnerKt

Nota: requiere las variables de entorno DB_* definidas en esta terminal
(ver .env.example).
#>
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
& .\kotlin.bat run --main-class=com.era.backend.database.MigrateRunnerKt
exit $LASTEXITCODE
