param(
    [string]$ProjectRoot = (Resolve-Path "$PSScriptRoot/../../..").Path,
    [string]$OutputDir = "",
    [string]$JavaHome = "",
    [switch]$NoZip
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function New-CleanDirectory {
    param([string]$Path)

    if (Test-Path $Path) {
        Remove-Item -Recurse -Force $Path
    }
    New-Item -ItemType Directory -Force -Path $Path | Out-Null
}

function Copy-DirectoryContent {
    param(
        [string]$Source,
        [string]$Destination
    )

    if (-not (Test-Path $Source)) {
        throw "No existe la carpeta requerida: $Source"
    }

    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    Copy-Item -Path (Join-Path $Source '*') -Destination $Destination -Recurse -Force
}

function Assert-FileExists {
    param([string]$Path)

    if (-not (Test-Path $Path)) {
        throw "No existe el archivo requerido: $Path"
    }
}

function Resolve-JavaHome {
    param([string]$PreferredJavaHome)

    $knownHomes = @(
        $PreferredJavaHome,
        "C:\Users\soett\.antigravity\extensions\redhat.java-1.51.0-win32-x64\jre\21.0.9-win32-x86_64",
        $env:JAVA_HOME
    ) | Where-Object { $_ }

    foreach ($candidate in $knownHomes | Select-Object -Unique) {
        $javacPath = Join-Path $candidate "bin\javac.exe"
        if ((Test-Path $javacPath) -and (Test-Java21OrNewer -JavaHomePath $candidate)) {
            return (Resolve-Path $candidate).Path
        }
    }

    $javacCommand = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($javacCommand) {
        $javaHomeFromPath = Split-Path (Split-Path $javacCommand.Source -Parent) -Parent
        if (Test-Java21OrNewer -JavaHomePath $javaHomeFromPath) {
            return $javaHomeFromPath
        }
    }

    throw "No se encontro un JDK 21 o superior con javac.exe para empaquetar la estacion."
}

function Test-Java21OrNewer {
    param([string]$JavaHomePath)

    $javacPath = Join-Path $JavaHomePath "bin\javac.exe"
    if (-not (Test-Path $javacPath)) {
        return $false
    }

    try {
        $versionOutput = (& $javacPath -version 2>&1 | Select-Object -First 1)
        if ($versionOutput -match '(\d+)(?:\.\d+)?') {
            return [int]$matches[1] -ge 21
        }
    }
    catch {
        return $false
    }

    return $false
}

$ProjectRoot = (Resolve-Path $ProjectRoot).Path
$JavaHome = Resolve-JavaHome -PreferredJavaHome $JavaHome
if (-not $OutputDir) {
    $OutputDir = Join-Path $ProjectRoot "dist\windows\VotanteWindowsPortable"
}

$PortableRoot = (Resolve-Path (Split-Path -Parent $OutputDir) -ErrorAction SilentlyContinue)
if (-not $PortableRoot) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $OutputDir) | Out-Null
}
$OutputDir = [System.IO.Path]::GetFullPath($OutputDir)
$ZipPath = "$OutputDir.zip"

$SourceDir = Join-Path $ProjectRoot "workflow\votante\windows\app\src"
$PublicDir = Join-Path $ProjectRoot "workflow\votante\windows\app\public"
$SharedDir = Join-Path $ProjectRoot "workflow\votante\shared"
$BuildCifradorScript = Join-Path $ProjectRoot "scripts\windows\empaquetado\build-cifrador-portable.ps1"
$CifradorLibraryDir = Join-Path $ProjectRoot "dist\windows\library\Cifrador"
$RuntimeSourceDir = $JavaHome
$JarSource = Join-Path $CifradorLibraryDir "app\ElGamalCipher-1.1.0.jar"
$VmgjSource = Join-Path $CifradorLibraryDir "libs\windows-x64\vmgj-1.3.0.dll"
$VecjSource = Join-Path $CifradorLibraryDir "libs\windows-x64\vecj-2.2.0.dll"
$CatalogSource = Join-Path $SharedDir "catalogo-opciones.json"
$SchemaSource = Join-Path $SharedDir "vote-schema.md"
$PortableJavac = Join-Path $JavaHome "bin\javac.exe"

if (-not (Test-Path $PortableJavac) -or -not (Test-Path $JarSource) -or -not (Test-Path $VmgjSource) -or -not (Test-Path $VecjSource)) {
    Write-Host "[portable] No se encontro el bundle de libreria del cifrador Windows. Reconstruyendo..."
    & powershell -NoProfile -ExecutionPolicy Bypass -File $BuildCifradorScript -ProjectRoot $ProjectRoot
    if ($LASTEXITCODE -ne 0) {
        throw "No se pudo preparar el cifrador Windows como libreria."
    }
}

Assert-FileExists $JarSource
Assert-FileExists $VmgjSource
Assert-FileExists $VecjSource
Assert-FileExists $CatalogSource
Assert-FileExists $SchemaSource
Assert-FileExists $PortableJavac

$ClassesDir = Join-Path $OutputDir "workflow\votante\windows\app\classes"
$PortablePublicDir = Join-Path $OutputDir "workflow\votante\windows\app\public"
$PortableRuntimeDir = Join-Path $OutputDir "workflow\votante\windows\runtime"
$PortableSharedDir = Join-Path $OutputDir "workflow\votante\shared"
$PortableCifradorDir = Join-Path $OutputDir "dist\windows\library\Cifrador"
$PortableAppDir = Join-Path $PortableCifradorDir "app"
$PortableLibDir = Join-Path $PortableCifradorDir "libs\windows-x64"
$PortableJavaDir = Join-Path $OutputDir "runtime\java"

Write-Host "[portable] Preparando carpeta portable en $OutputDir"
New-CleanDirectory $OutputDir

New-Item -ItemType Directory -Force -Path $ClassesDir | Out-Null
New-Item -ItemType Directory -Force -Path $PortableRuntimeDir | Out-Null
New-Item -ItemType Directory -Force -Path $PortableSharedDir | Out-Null
New-Item -ItemType Directory -Force -Path $PortableAppDir | Out-Null
New-Item -ItemType Directory -Force -Path $PortableLibDir | Out-Null
New-Item -ItemType Directory -Force -Path $PortableJavaDir | Out-Null

Write-Host "[portable] Copiando UI y catalogos"
Copy-DirectoryContent -Source $PublicDir -Destination $PortablePublicDir
Copy-Item -Path $CatalogSource -Destination (Join-Path $PortableSharedDir "catalogo-opciones.json") -Force
Copy-Item -Path $SchemaSource -Destination (Join-Path $PortableSharedDir "vote-schema.md") -Force

Write-Host "[portable] Copiando runtime Java de la estacion"
Copy-DirectoryContent -Source $RuntimeSourceDir -Destination $PortableJavaDir

Write-Host "[portable] Copiando jar y DLL nativas"
Copy-Item -Path $JarSource -Destination (Join-Path $PortableAppDir "ElGamalCipher-1.1.0.jar") -Force
Copy-Item -Path $VmgjSource -Destination (Join-Path $PortableLibDir "vmgj-1.3.0.dll") -Force
Copy-Item -Path $VecjSource -Destination (Join-Path $PortableLibDir "vecj-2.2.0.dll") -Force

Write-Host "[portable] Compilando servidor del votante con runtime portable"
$SourceList = Join-Path $OutputDir "workflow\votante\windows\app\sources.txt"
Get-ChildItem -Path $SourceDir -Recurse -Filter *.java |
        Sort-Object FullName |
        ForEach-Object { $_.FullName } |
        Set-Content -Path $SourceList -Encoding ASCII

& $PortableJavac -encoding UTF-8 -d $ClassesDir "@$SourceList"
if ($LASTEXITCODE -ne 0) {
    throw "Fallo la compilacion del servidor portable."
}
Remove-Item -Force $SourceList

$LauncherPs1 = @'
param(
    [int]$Port = 8788,
    [Alias("MixBaseUrl")][string]$ServiceBaseUrl = "",
    [string]$Auxsid = "",
    [string]$SessionId = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Test-PortAvailable {
    param([int]$PortNumber)

    $listener = $null
    try {
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $PortNumber)
        $listener.Start()
        return $true
    }
    catch {
        return $false
    }
    finally {
        if ($listener) {
            $listener.Stop()
        }
    }
}

$PortableRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '.'))
$JavaExe = Join-Path $PortableRoot 'runtime\java\bin\java.exe'
$ClassesDir = Join-Path $PortableRoot 'workflow\votante\windows\app\classes'
$MainClass = 'pe.gob.onpe.votodigital.votante.windows.DidacticWindowsVoterServer'

if (-not $Auxsid -and $SessionId) {
    $Auxsid = $SessionId
    Write-Host "[votante-windows] SessionId es un parametro heredado. Se reutilizara como Auxsid: $Auxsid"
}

if (-not (Test-Path $JavaExe)) {
    throw "No se encontro el runtime portable en $JavaExe"
}

if (-not (Test-Path $ClassesDir)) {
    throw "No se encontro la carpeta de clases portable en $ClassesDir"
}

if (-not (Test-PortAvailable -PortNumber $Port)) {
    throw "El puerto $Port ya esta ocupado. Usa esa instancia o arranca con -Port <otro_puerto>."
}

Write-Host "[votante-windows] Iniciando servidor en http://127.0.0.1:$Port"
if ($ServiceBaseUrl) {
    Write-Host "[votante-windows] Semilla de descubrimiento configurada: $ServiceBaseUrl"
}
else {
    Write-Host "[votante-windows] Descubrimiento de mezcladora: red local"
}

$javaArgs = @(
    "-Dvotante.windows.root=$PortableRoot",
    "-Dvotante.windows.port=$Port"
)
if ($ServiceBaseUrl) {
    $javaArgs += "-Dvotante.windows.serviceBaseUrl=$ServiceBaseUrl"
}
if ($Auxsid) {
    $javaArgs += "-Dvotante.windows.auxsid=$Auxsid"
}
$javaArgs += @("-cp", $ClassesDir, $MainClass)

& $JavaExe @javaArgs
'@

$LauncherCmd = @'
@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-votante-windows.ps1" %*
'@

$Readme = @'
Estacion de votacion Windows portable

Contenido:
- runtime Java portable de la estacion
- servidor local de la estacion de votacion
- UI web local
- ElGamalCipher-1.1.0.jar
- DLL nativas vecj/vmgj

Ejecucion recomendada:
1. run-votante-windows.cmd
2. abrir http://127.0.0.1:8788 o http://<ip-local-de-la-estacion>:8788

Comportamiento de red:
- por defecto la estacion escucha en 0.0.0.0
- queda accesible desde la red local
- para restringirla a la misma maquina:
  powershell -NoProfile -ExecutionPolicy Bypass -File .\run-votante-windows.ps1 -BindHost 127.0.0.1

Opcional:
- fijar semilla de descubrimiento:
  powershell -NoProfile -ExecutionPolicy Bypass -File .\run-votante-windows.ps1 -ServiceBaseUrl http://192.168.0.120:7040
'@

Set-Content -Path (Join-Path $OutputDir "run-votante-windows.ps1") -Value $LauncherPs1 -Encoding UTF8
Set-Content -Path (Join-Path $OutputDir "run-votante-windows.cmd") -Value $LauncherCmd -Encoding ASCII
Set-Content -Path (Join-Path $OutputDir "README.txt") -Value $Readme -Encoding UTF8

if (Test-Path $ZipPath) {
    Remove-Item -Force $ZipPath
}

if (-not $NoZip) {
    Write-Host "[portable] Generando zip $ZipPath"
    Compress-Archive -Path (Join-Path $OutputDir '*') -DestinationPath $ZipPath -CompressionLevel Optimal
}

Write-Host "[portable] Portable generado correctamente."
Write-Host "[portable] Carpeta: $OutputDir"
if (-not $NoZip) {
    Write-Host "[portable] Zip: $ZipPath"
}
