#Requires -Version 5.1
<#
scripts/lint.ps1 - Lint y formato con ktlint (CLI independiente, sin tocar module.yaml).

Uso:
  .\scripts\lint.ps1            comprueba estilo en src/ y test/
  .\scripts\lint.ps1 -Format    corrige automaticamente (ktlint --format)

Primera ejecucion: descarga ktlint $ktlintVersion a .tools/ (gitignored).
Si el bloqueo de politica de ejecucion lo impide:
  powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\lint.ps1
#>
param([switch]$Format)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$ktlintVersion = "1.8.0"
$toolsDir = Join-Path $root ".tools"
$jar = Join-Path $toolsDir "ktlint-$ktlintVersion.jar"
$tmp = "$jar.tmp"
$url = "https://github.com/ktlint/ktlint/releases/download/$ktlintVersion/ktlint"

function Test-KtLintJar {
    $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "java" }
    try {
        $out = & $java -jar $jar --version 2>&1
        return ($LASTEXITCODE -eq 0 -and ($out -match "ktlint version"))
    } catch {
        return $false
    }
}

New-Item -ItemType Directory -Force -Path $toolsDir | Out-Null
if (-not (Test-Path $jar) -or -not (Test-KtLintJar)) {
    Write-Host "Descargando ktlint $ktlintVersion ..." -ForegroundColor Cyan
    & curl.exe -L --fail --retry 3 -o $tmp $url
    if ($LASTEXITCODE -ne 0) { throw "Fallo la descarga de ktlint ($url)" }
    Move-Item -Force $tmp $jar
    if (-not (Test-KtLintJar)) { throw "El jar de ktlint descargado no es valido." }
}

$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "java" }
$ktlintArgs = @("-jar", $jar)
if ($Format) { $ktlintArgs += "-F" }
$ktlintArgs += "src/**/*.kt", "test/**/*.kt"

& $java $ktlintArgs
exit $LASTEXITCODE
