param(
    [string]$ProjectRoot = (Resolve-Path "$PSScriptRoot/../../..").Path,
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

$ProjectRoot = (Resolve-Path $ProjectRoot).Path
$SourceDir = Join-Path $ProjectRoot "workflow\votante\windows\app\src"
$ClassesDir = Join-Path $ProjectRoot ".build\votante-windows\classes"
$MainClass = "pe.gob.onpe.votodigital.votante.windows.DidacticWindowsVoterServer"

if (-not $Auxsid -and $SessionId) {
    $Auxsid = $SessionId
    Write-Host "[votante-windows] SessionId es un parametro heredado. Se reutilizara como Auxsid: $Auxsid"
}

New-Item -ItemType Directory -Force -Path $ClassesDir | Out-Null

$sources = Get-ChildItem -Path $SourceDir -Recurse -Filter *.java | ForEach-Object { $_.FullName }
if (-not $sources) {
    throw "No se encontraron fuentes Java en $SourceDir"
}

Write-Host "[votante-windows] Compilando servicio..."
javac -encoding UTF-8 -d $ClassesDir @sources
if ($LASTEXITCODE -ne 0) {
    throw "Fallo la compilacion del votante Windows."
}

if (-not (Test-PortAvailable -PortNumber $Port)) {
    throw "El puerto $Port ya esta ocupado. Si el votante Windows ya esta corriendo, usa esa instancia. Si no, libera el puerto o arranca con -Port <otro_puerto>."
}

Write-Host "[votante-windows] Iniciando servidor en http://127.0.0.1:$Port"
if ($ServiceBaseUrl) {
    Write-Host "[votante-windows] Semilla de descubrimiento configurada: $ServiceBaseUrl"
}
else {
    Write-Host "[votante-windows] Descubrimiento de mezcladora: red local"
}
$javaArgs = @(
    "-Dvotante.windows.root=$ProjectRoot",
    "-Dvotante.windows.port=$Port"
)
if ($ServiceBaseUrl) {
    $javaArgs += "-Dvotante.windows.serviceBaseUrl=$ServiceBaseUrl"
}
if ($Auxsid) {
    $javaArgs += "-Dvotante.windows.auxsid=$Auxsid"
}
$javaArgs += @("-cp", $ClassesDir, $MainClass)
java @javaArgs
