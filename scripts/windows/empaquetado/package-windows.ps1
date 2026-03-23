param(
    [string]$ProjectRoot = (Resolve-Path "$PSScriptRoot/../../..").Path,
    [string]$AppVersion = "1.1.0",
    [string]$AppName = "ElGamalCipher",
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = "Stop"

function Resolve-JpackageExe {
    param([string]$PreferredJavaHome)

    $candidates = @()

    if ($PreferredJavaHome) {
        $candidates += (Join-Path $PreferredJavaHome "bin\jpackage.exe")
    }

    foreach ($commandName in @("jpackage.exe", "jpackage")) {
        $command = Get-Command $commandName -ErrorAction SilentlyContinue
        if ($command) {
            $candidates += $command.Source
        }
    }

    foreach ($candidate in $candidates | Select-Object -Unique) {
        if ($candidate -and (Test-Path $candidate)) {
            return (Resolve-Path $candidate).Path
        }
    }

    throw "No se encontro jpackage.exe. Define JAVA_HOME con un JDK 21+ o agrega jpackage al PATH."
}

$JarPath = Join-Path $ProjectRoot "prebuilt/java/ElGamalCipher-$AppVersion.jar"
$NativeDir = Join-Path $ProjectRoot "prebuilt/windows-x64"
$RequiredDlls = @(
    "vecj-2.2.0.dll",
    "vmgj-1.3.0.dll"
)
$BuildRoot = Join-Path $ProjectRoot "dist/windows"
$InputDir = Join-Path $BuildRoot "input"
$ImageDir = Join-Path $BuildRoot "image"
$StagedNativeDir = Join-Path $InputDir "libs/windows-x64"
$JpackageExe = Resolve-JpackageExe -PreferredJavaHome $JavaHome
$RuntimeImageArgs = @()

if ($JavaHome -and (Test-Path (Join-Path $JavaHome "bin\java.exe"))) {
    $RuntimeImageArgs = @("--runtime-image", $JavaHome)
}

if (-not (Test-Path $JarPath)) {
    throw "No se encontro el JAR esperado: $JarPath"
}

foreach ($dll in $RequiredDlls) {
    $dllPath = Join-Path $NativeDir $dll
    if (-not (Test-Path $dllPath)) {
        throw "No se encontro la DLL requerida: $dllPath"
    }
}

if (Test-Path $InputDir) {
    Remove-Item -Recurse -Force $InputDir
}

if (Test-Path $ImageDir) {
    Remove-Item -Recurse -Force $ImageDir
}

New-Item -ItemType Directory -Force -Path $InputDir | Out-Null
New-Item -ItemType Directory -Force -Path $ImageDir | Out-Null
New-Item -ItemType Directory -Force -Path $StagedNativeDir | Out-Null

Copy-Item $JarPath $InputDir
Copy-Item -Recurse (Join-Path $ProjectRoot "recursos") $InputDir
Copy-Item (Join-Path $NativeDir "*") $StagedNativeDir -Recurse -Force

$jpackageArgs = @(
    "--type", "app-image",
    "--name", $AppName,
    "--app-version", $AppVersion,
    "--input", $InputDir,
    "--dest", $ImageDir,
    "--main-jar", ("ElGamalCipher-" + $AppVersion + ".jar"),
    "--win-console",
    "--java-options", "-Djava.library.path=`$APPDIR\libs\windows-x64"
) + $RuntimeImageArgs

& $JpackageExe @jpackageArgs

if ($LASTEXITCODE -ne 0) {
    throw "jpackage fallo al generar la app-image de Windows."
}

Write-Host "Paquete generado en: $ImageDir"
Write-Host "Launcher generado en: $(Join-Path (Join-Path $ImageDir $AppName) ($AppName + '.exe'))"
