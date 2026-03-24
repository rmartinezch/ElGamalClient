param(
    [string]$ProjectRoot = (Resolve-Path "$PSScriptRoot/../../..").Path,
    [string]$JavaHome,
    [string]$MavenCmd,
    [string]$AppVersion = "1.1.0",
    [string]$AppName = "Cifrador",
    [switch]$ForceNativeBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-JavaHome {
    param([string]$PreferredJavaHome)

    $knownHomes = @(
        $PreferredJavaHome,
        "C:\Users\soett\.antigravity\extensions\redhat.java-1.51.0-win32-x64\jre\21.0.9-win32-x86_64",
        $env:JAVA_HOME
    ) | Where-Object { $_ }

    foreach ($candidate in $knownHomes | Select-Object -Unique) {
        $javacPath = Join-Path $candidate "bin\javac.exe"
        if (Test-Path $javacPath) {
            return (Resolve-Path $candidate).Path
        }
    }

    $javacCommand = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($javacCommand) {
        return Split-Path (Split-Path $javacCommand.Source -Parent) -Parent
    }

    throw "No se encontro un JDK valido con javac.exe."
}

function Write-Utf8NoBomTextFile {
    param(
        [string]$PathValue,
        [string]$Content
    )

    $encoding = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText($PathValue, $Content, $encoding)
}

$ProjectRoot = (Resolve-Path $ProjectRoot).Path
$JavaHome = Resolve-JavaHome -PreferredJavaHome $JavaHome
$BuildScript = Join-Path $ProjectRoot "scripts\windows\compilacion\build-cifrador.ps1"

$buildArgs = @(
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-File", $BuildScript,
    "-ProjectRoot", $ProjectRoot,
    "-JavaHome", $JavaHome,
    "-AppVersion", $AppVersion
)
if ($MavenCmd) {
    $buildArgs += @("-MavenCmd", $MavenCmd)
}
if ($ForceNativeBuild.IsPresent) {
    $buildArgs += "-ForceNativeBuild"
}

& powershell @buildArgs

if ($LASTEXITCODE -ne 0) {
    throw "No se pudo compilar el cifrador Windows."
}

$NativeDir = Join-Path $ProjectRoot "prebuilt\windows-x64"
$CanonicalJavaDir = Join-Path $ProjectRoot "prebuilt\java"
$LibraryRoot = Join-Path $ProjectRoot "dist\windows\library\$AppName"
$AppRoot = Join-Path $LibraryRoot "app"
$LibraryNativeRoot = Join-Path $LibraryRoot "libs\windows-x64"
$JarPath = Join-Path $CanonicalJavaDir ("ElGamalCipher-" + $AppVersion + ".jar")
$JarName = Split-Path $JarPath -Leaf
$ReadmePath = Join-Path $LibraryRoot "README.txt"

if (-not (Test-Path $JarPath)) {
    throw "No se encontro el JAR esperado: $JarPath"
}

if (Test-Path $LibraryRoot) {
    Remove-Item -Recurse -Force $LibraryRoot
}

New-Item -ItemType Directory -Force -Path $AppRoot | Out-Null
New-Item -ItemType Directory -Force -Path $LibraryNativeRoot | Out-Null

Copy-Item $JarPath $AppRoot -Force
Copy-Item (Join-Path $NativeDir "*") $LibraryNativeRoot -Recurse -Force

$Readme = @"
$AppName library bundle para Windows x64
=======================================

Contenido principal
-------------------
- app\$JarName
- libs\windows-x64\vecj-2.2.0.dll
- libs\windows-x64\vmgj-1.3.0.dll

Uso como libreria externa
-------------------------
Una aplicacion externa debe invocar el jar con Java y exponer las DLL JNI con
java.library.path.

Ejemplo PowerShell:

  java -Djava.library.path=.\libs\windows-x64 -jar .\app\$JarName .\publicKey .\votes.txt .\ciphertexts_ext -sw

Notas
-----
- Este bundle no contiene interfaz ni launcher .exe.
- Este bundle no contiene runtime Java embebido.
- El artefacto canonico para integracion en Windows es jar + DLL JNI.
"@

Write-Utf8NoBomTextFile -PathValue $ReadmePath -Content $Readme

Write-Host "Bundle de libreria generado en: $LibraryRoot"
Write-Host " - $(Join-Path $AppRoot $JarName)"
Write-Host " - $(Join-Path $LibraryNativeRoot 'vecj-2.2.0.dll')"
Write-Host " - $(Join-Path $LibraryNativeRoot 'vmgj-1.3.0.dll')"
