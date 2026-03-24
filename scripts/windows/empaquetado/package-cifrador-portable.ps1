param(
    [string]$ProjectRoot = (Resolve-Path "$PSScriptRoot/../../..").Path,
    [string]$AppName = "Cifrador",
    [string]$AppVersion = "1.1.0",
    [string]$OutputDir,
    [switch]$RebuildBundle
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
$BuildScript = Join-Path $ProjectRoot "scripts\windows\empaquetado\build-cifrador-portable.ps1"
$LibraryRoot = Join-Path $ProjectRoot ("dist\windows\library\{0}" -f $AppName)

if (-not $OutputDir) {
    $OutputDir = Join-Path $ProjectRoot "dist\windows"
}
else {
    $OutputDir = (Resolve-Path $OutputDir).Path
}

if ($RebuildBundle.IsPresent -or -not (Test-Path (Join-Path $LibraryRoot "app\ElGamalCipher-$AppVersion.jar"))) {
    & powershell -NoProfile -ExecutionPolicy Bypass -File $BuildScript `
        -ProjectRoot $ProjectRoot `
        -AppName $AppName `
        -AppVersion $AppVersion

    if ($LASTEXITCODE -ne 0) {
        throw "No se pudo generar el bundle de libreria Windows."
    }
}

if (-not (Test-Path $LibraryRoot)) {
    throw "No se encontro la carpeta esperada del bundle de libreria: $LibraryRoot"
}

$PortableReadmePath = Join-Path $LibraryRoot "README.txt"
$PortableReadmeContent = @"
$AppName library bundle para Windows x64
========================================

Contenido principal
-------------------
- app\ElGamalCipher-$AppVersion.jar
- libs\windows-x64\vecj-2.2.0.dll
- libs\windows-x64\vmgj-1.3.0.dll

Uso como libreria
-----------------
Una aplicacion externa debe invocar el jar con Java y publicar las DLL JNI con
java.library.path.

Ejemplo PowerShell:

  java -Djava.library.path=.\libs\windows-x64 -jar .\app\ElGamalCipher-$AppVersion.jar .\publicKey .\votes.txt .\ciphertexts_ext -sw

Notas
-----
- Este bundle no contiene interfaz ni launcher .exe.
- Este bundle no contiene runtime Java embebido.
- -sw usa SecureRandom.
- -hw usa TrueRNG cuando el dispositivo esta disponible; si no, hace fallback a SecureRandom.
"@

Write-Utf8NoBomTextFile -PathValue $PortableReadmePath -Content $PortableReadmeContent

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$ZipPath = Join-Path $OutputDir ("{0}-{1}-windows-x64-library.zip" -f $AppName, $AppVersion)
if (Test-Path $ZipPath) {
    Remove-Item $ZipPath -Force
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory(
    $LibraryRoot,
    $ZipPath,
    [System.IO.Compression.CompressionLevel]::Optimal,
    $true
)

Write-Host "ZIP portable generado en: $ZipPath"
