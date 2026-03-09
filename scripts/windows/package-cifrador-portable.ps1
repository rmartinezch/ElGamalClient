param(
    [string]$ProjectRoot = (Resolve-Path "$PSScriptRoot/..").Path,
    [string]$AppName = "Cifrador",
    [string]$AppVersion = "1.1.0",
    [string]$OutputDir,
    [switch]$RebuildExe
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ProjectRoot = (Resolve-Path $ProjectRoot).Path
$BuildScript = Join-Path $ProjectRoot "scripts\windows\build-cifrador-exe.ps1"
$ImageRoot = Join-Path $ProjectRoot ("dist\windows\image\{0}" -f $AppName)

if (-not $OutputDir) {
    $OutputDir = Join-Path $ProjectRoot "dist\windows"
}
else {
    $OutputDir = (Resolve-Path $OutputDir).Path
}

if ($RebuildExe.IsPresent -or -not (Test-Path (Join-Path $ImageRoot ($AppName + ".exe")))) {
    & powershell -NoProfile -ExecutionPolicy Bypass -File $BuildScript `
        -ProjectRoot $ProjectRoot `
        -AppName $AppName `
        -AppVersion $AppVersion

    if ($LASTEXITCODE -ne 0) {
        throw "No se pudo generar el ejecutable portable."
    }
}

if (-not (Test-Path $ImageRoot)) {
    throw "No se encontro la carpeta portable esperada: $ImageRoot"
}

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$ZipPath = Join-Path $OutputDir ("{0}-{1}-windows-x64-portable.zip" -f $AppName, $AppVersion)
if (Test-Path $ZipPath) {
    Remove-Item $ZipPath -Force
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory(
    $ImageRoot,
    $ZipPath,
    [System.IO.Compression.CompressionLevel]::Optimal,
    $true
)

Write-Host "ZIP portable generado en: $ZipPath"
