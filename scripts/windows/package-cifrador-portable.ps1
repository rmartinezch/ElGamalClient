param(
    [string]$ProjectRoot = (Resolve-Path "$PSScriptRoot/../..").Path,
    [string]$AppName = "Cifrador",
    [string]$AppVersion = "1.1.0",
    [string]$OutputDir,
    [switch]$RebuildExe
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Write-Utf8NoBomTextFile {
    param(
        [string]$PathValue,
        [string]$Content
    )

    $encoding = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText($PathValue, $Content, $encoding)
}

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

$PortableReadmePath = Join-Path $ImageRoot "README.txt"
$PortableReadmeContent = @"
$AppName portable para Windows x64
==================================

Contenido principal
-------------------
- $AppName.exe
- runtime\ (JRE embebido)
- app\ElGamalCipher-$AppVersion.jar
- libs\windows-x64\vecj-2.2.0.dll
- libs\windows-x64\vmgj-1.3.0.dll
- recursos\publicKey
- recursos\shuffled_votes.txt

Uso basico
----------
Abre PowerShell o CMD dentro de esta carpeta y ejecuta:

  .\$AppName.exe .\recursos\publicKey .\recursos\shuffled_votes.txt .\salida.txt -sw

El archivo de salida quedara en:

  .\salida.txt

Uso con TrueRNG en Windows
--------------------------
Si el TrueRNG ya aparece como puerto serie COM en Windows, por ejemplo COM6:

PowerShell:
  `$env:ELGAMAL_RNG_DEVICE='COM6'
  .\$AppName.exe .\recursos\publicKey .\recursos\shuffled_votes.txt .\salida.txt -hw

CMD:
  set ELGAMAL_RNG_DEVICE=COM6
  .\$AppName.exe .\recursos\publicKey .\recursos\shuffled_votes.txt .\salida.txt -hw

Si no se define ELGAMAL_RNG_DEVICE, el cifrador intentara autodetectar el
TrueRNG USB en Windows.

Notas
-----
- No copies solo $AppName.exe. Debes mover toda la carpeta portable.
- -sw usa SecureRandom.
- -hw usa TrueRNG cuando el dispositivo esta disponible; si no, hace fallback a SecureRandom.
"@

Write-Utf8NoBomTextFile -PathValue $PortableReadmePath -Content $PortableReadmeContent

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
