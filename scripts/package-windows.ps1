param(
    [string]$ProjectRoot = (Resolve-Path "$PSScriptRoot/..").Path,
    [string]$AppVersion = "1.1.0",
    [string]$AppName = "ElGamalCipher"
)

$ErrorActionPreference = "Stop"

$JarPath = Join-Path $ProjectRoot "target/ElGamalCipher-$AppVersion.jar"
$NativeDir = Join-Path $ProjectRoot "libs/windows-x64"
$DllPath = Join-Path $NativeDir "vecj-2.2.0.dll"
$BuildRoot = Join-Path $ProjectRoot "dist/windows"
$InputDir = Join-Path $BuildRoot "input"
$ImageDir = Join-Path $BuildRoot "image"

if (-not (Test-Path $JarPath)) {
    throw "No se encontro el JAR esperado: $JarPath"
}

if (-not (Test-Path $DllPath)) {
    throw "No se encontro la DLL requerida: $DllPath"
}

if (Test-Path $InputDir) {
    Remove-Item -Recurse -Force $InputDir
}

if (Test-Path $ImageDir) {
    Remove-Item -Recurse -Force $ImageDir
}

New-Item -ItemType Directory -Force -Path $InputDir | Out-Null
New-Item -ItemType Directory -Force -Path $ImageDir | Out-Null

Copy-Item $JarPath $InputDir
Copy-Item -Recurse (Join-Path $ProjectRoot "recursos") $InputDir
Copy-Item -Recurse $NativeDir (Join-Path $InputDir "libs")

jpackage `
    --type app-image `
    --name $AppName `
    --app-version $AppVersion `
    --input $InputDir `
    --dest $ImageDir `
    --main-jar ("ElGamalCipher-" + $AppVersion + ".jar") `
    --java-options "-Djava.library.path=`$APPDIR\libs\windows-x64"

Write-Host "Paquete generado en: $ImageDir"
