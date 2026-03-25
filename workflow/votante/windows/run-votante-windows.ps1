param(
    [string]$ProjectRoot = (Resolve-Path "$PSScriptRoot/../../..").Path,
    [int]$Port = 8788,
    [string]$BindHost = "0.0.0.0",
    [string]$PublicHost = "",
    [Alias("MixBaseUrl")][string]$ServiceBaseUrl = "",
    [string]$Auxsid = "",
    [string]$SessionId = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Test-PortAvailable {
    param(
        [string]$BindHostValue,
        [int]$PortNumber
    )

    $listener = $null
    try {
        $address = Resolve-BindAddress -BindHostValue $BindHostValue
        $listener = [System.Net.Sockets.TcpListener]::new($address, $PortNumber)
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

function Resolve-BindAddress {
    param([string]$BindHostValue)

    if ([string]::IsNullOrWhiteSpace($BindHostValue) -or $BindHostValue -eq "0.0.0.0" -or $BindHostValue -eq "*") {
        return [System.Net.IPAddress]::Any
    }
    if ($BindHostValue -eq "::") {
        return [System.Net.IPAddress]::IPv6Any
    }
    if ($BindHostValue -eq "localhost") {
        return [System.Net.IPAddress]::Loopback
    }

    $parsed = $null
    if ([System.Net.IPAddress]::TryParse($BindHostValue, [ref]$parsed)) {
        return $parsed
    }

    $candidate = [System.Net.Dns]::GetHostAddresses($BindHostValue) |
        Where-Object { $_.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetwork } |
        Select-Object -First 1
    if ($candidate) {
        return $candidate
    }

    throw "No se pudo resolver la direccion de escucha: $BindHostValue"
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

if (-not (Test-PortAvailable -BindHostValue $BindHost -PortNumber $Port)) {
    throw "La combinacion $BindHost`:$Port ya esta ocupada. Si el votante Windows ya esta corriendo, usa esa instancia. Si no, libera el puerto o arranca con -Port <otro_puerto>."
}

Write-Host "[votante-windows] Iniciando servidor en http://$BindHost:$Port"
if ($ServiceBaseUrl) {
    Write-Host "[votante-windows] Semilla de descubrimiento configurada: $ServiceBaseUrl"
}
else {
    Write-Host "[votante-windows] Descubrimiento de mezcladora: red local"
}
$javaArgs = @(
    "-Dvotante.windows.root=$ProjectRoot",
    "-Dvotante.windows.bindHost=$BindHost",
    "-Dvotante.windows.port=$Port"
)
if ($PublicHost) {
    $javaArgs += "-Dvotante.windows.publicHost=$PublicHost"
}
if ($ServiceBaseUrl) {
    $javaArgs += "-Dvotante.windows.serviceBaseUrl=$ServiceBaseUrl"
}
if ($Auxsid) {
    $javaArgs += "-Dvotante.windows.auxsid=$Auxsid"
}
$javaArgs += @("-cp", $ClassesDir, $MainClass)
java @javaArgs
