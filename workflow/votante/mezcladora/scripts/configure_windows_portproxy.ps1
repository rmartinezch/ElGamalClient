[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$BasePort = 7040
$LastPartyPort = 7049
$FirewallRuleName = "Verificatum GUI 7040-7049"

function Assert-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw "Ejecuta este script en PowerShell como Administrador."
    }
}

function Get-PrimaryListenAddress {
    $configs = Get-NetIPConfiguration | Where-Object {
        $_.IPv4Address -and
        $_.NetAdapter -and
        $_.NetAdapter.Status -eq "Up"
    }

    $withGateway = $configs | Where-Object {
        $_.IPv4DefaultGateway -and
        $_.IPv4Address.IPAddress -notlike "169.254.*" -and
        $_.IPv4Address.IPAddress -ne "127.0.0.1"
    }

    if ($withGateway) {
        $selected = $withGateway |
            Sort-Object InterfaceMetric |
            Select-Object -First 1
        if ($selected -and $selected.IPv4Address -and $selected.IPv4Address.IPAddress) {
            return $selected.IPv4Address.IPAddress
        }
    }

    $defaultRoute = Get-NetRoute -AddressFamily IPv4 -DestinationPrefix "0.0.0.0/0" -ErrorAction SilentlyContinue |
        Where-Object { $_.NextHop -and $_.NextHop -ne "0.0.0.0" } |
        Sort-Object RouteMetric, InterfaceMetric |
        Select-Object -First 1

    if ($defaultRoute) {
        $ip = Get-NetIPAddress -AddressFamily IPv4 -InterfaceIndex $defaultRoute.InterfaceIndex -ErrorAction SilentlyContinue |
            Where-Object {
                $_.IPAddress -notlike "169.254.*" -and
                $_.IPAddress -ne "127.0.0.1"
            } |
            Sort-Object SkipAsSource |
            Select-Object -First 1 -ExpandProperty IPAddress

        if ($ip) {
            return $ip
        }
    }

    $fallback = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object {
            $_.IPAddress -notlike "169.254.*" -and
            $_.IPAddress -ne "127.0.0.1" -and
            $_.InterfaceAlias -notmatch "Loopback|vEthernet|WSL|Hyper-V|VirtualBox|VMware|Tailscale"
        } |
        Sort-Object InterfaceMetric, SkipAsSource |
        Select-Object -First 1 -ExpandProperty IPAddress

    if ($fallback) {
        return $fallback
    }

    throw "No pude detectar la IPv4 principal de Windows. Revisa que Windows tenga una interfaz de red activa con gateway."
}

function Get-WslConnectAddress {
    $raw = & wsl.exe -- sh -lc "hostname -I 2>/dev/null || true" 2>$null
    if (-not $raw) {
        throw "No pude detectar direcciones IPv4 de WSL."
    }

    $addresses = @((($raw -split "\s+") | Where-Object {
        $_ -match '^\d+\.\d+\.\d+\.\d+$' -and $_ -ne '127.0.0.1'
    }))

    if ($addresses.Count -eq 0) {
        throw "No pude detectar una IPv4 util de WSL."
    }

    $preferred = $addresses | Where-Object { $_ -like "172.*" } | Select-Object -First 1
    if ($preferred) {
        return $preferred
    }

    return $addresses[0]
}

function Get-ExistingPortProxyEntries {
    $lines = & netsh interface portproxy show all
    $entries = @()

    foreach ($line in $lines) {
        if ($line -match '^\s*(\d+\.\d+\.\d+\.\d+)\s+(\d+)\s+(\d+\.\d+\.\d+\.\d+)\s+(\d+)\s*$') {
            $entries += [PSCustomObject]@{
                ListenAddress  = $matches[1]
                ListenPort     = [int]$matches[2]
                ConnectAddress = $matches[3]
                ConnectPort    = [int]$matches[4]
            }
        }
    }

    return $entries
}

function Remove-PortProxyRange {
    param([System.Collections.IEnumerable]$Entries)

    foreach ($entry in $Entries) {
        & netsh interface portproxy delete v4tov4 `
            listenaddress=$($entry.ListenAddress) `
            listenport=$($entry.ListenPort) | Out-Null
    }
}

function Add-PortProxyRange {
    param(
        [string]$ListenAddress,
        [string]$ConnectAddress,
        [int]$StartPort,
        [int]$EndPort
    )

    foreach ($port in $StartPort..$EndPort) {
        & netsh interface portproxy add v4tov4 `
            listenaddress=$ListenAddress `
            listenport=$port `
            connectaddress=$ConnectAddress `
            connectport=$port | Out-Null
    }
}

function Ensure-FirewallRule {
    param(
        [string]$RuleName,
        [int]$StartPort,
        [int]$EndPort
    )

    $portRange = "$StartPort-$EndPort"
    $existing = Get-NetFirewallRule -DisplayName $RuleName -ErrorAction SilentlyContinue
    if ($existing) {
        $existing | Remove-NetFirewallRule | Out-Null
    }

    New-NetFirewallRule `
        -DisplayName $RuleName `
        -Direction Inbound `
        -Action Allow `
        -Protocol TCP `
        -LocalPort $portRange | Out-Null
}

Assert-Administrator

$listenAddress = Get-PrimaryListenAddress
$connectAddress = Get-WslConnectAddress

$desiredEntries = @()
foreach ($port in $BasePort..$LastPartyPort) {
    $desiredEntries += [PSCustomObject]@{
        ListenAddress  = $listenAddress
        ListenPort     = $port
        ConnectAddress = $connectAddress
        ConnectPort    = $port
    }
}

$existingEntries = @()
foreach ($entry in (Get-ExistingPortProxyEntries)) {
    if ($entry.ListenPort -ge $BasePort -and $entry.ListenPort -le $LastPartyPort) {
        $existingEntries += $entry
    }
}

$currentOk = $true
if ($existingEntries.Count -ne $desiredEntries.Count) {
    $currentOk = $false
} else {
    foreach ($desired in $desiredEntries) {
        $match = $existingEntries | Where-Object {
            $_.ListenAddress -eq $desired.ListenAddress -and
            $_.ListenPort -eq $desired.ListenPort -and
            $_.ConnectAddress -eq $desired.ConnectAddress -and
            $_.ConnectPort -eq $desired.ConnectPort
        }
        if (-not $match) {
            $currentOk = $false
            break
        }
    }
}

Write-Host "ListenAddress : $listenAddress"
Write-Host "ConnectAddress: $connectAddress"
Write-Host "Ports         : $BasePort-$LastPartyPort"

if ($currentOk) {
    Write-Host "Portproxy ya estaba actualizado."
} else {
    if ($existingEntries.Count -gt 0) {
        Write-Host "Se detectaron reglas viejas para $BasePort-$LastPartyPort. Se recrearan."
        Remove-PortProxyRange -Entries $existingEntries
    } else {
        Write-Host "No habia reglas previas para $BasePort-$LastPartyPort. Se crearan."
    }

    Add-PortProxyRange -ListenAddress $listenAddress -ConnectAddress $connectAddress -StartPort $BasePort -EndPort $LastPartyPort
}

Ensure-FirewallRule -RuleName $FirewallRuleName -StartPort $BasePort -EndPort $LastPartyPort

Write-Host ""
Write-Host "Reglas actuales:"
& netsh interface portproxy show all
