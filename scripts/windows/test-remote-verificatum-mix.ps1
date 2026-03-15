param(
    [string]$ProjectRoot = (Resolve-Path "$PSScriptRoot/../..").Path,
    [string]$SshHost = "CHUWIN11",
    [int]$Port = 22,
    [string]$User = "verificatum",
    [string]$Password = $env:VERIFICATUM_SSH_PASSWORD,
    [string]$VotesPath,
    [string]$RemoteDir = "/home/verificatum/codex_mix_test_auto",
    [string]$AppName = "Cifrador",
    [string]$AppVersion = "1.1.0",
    [ValidateSet("sw", "hw")]
    [string]$RngMode = "sw",
    [string]$RngDevice,
    [switch]$RebuildExe
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function New-Utf8NoBomEncoding {
    return [System.Text.UTF8Encoding]::new($false)
}

function Write-TextFile {
    param(
        [string]$PathValue,
        [string]$Content
    )

    [System.IO.File]::WriteAllText($PathValue, $Content, (New-Utf8NoBomEncoding))
}

function Write-JsonFile {
    param(
        [string]$PathValue,
        [object]$InputObject
    )

    $json = $InputObject | ConvertTo-Json -Depth 10
    Write-TextFile -PathValue $PathValue -Content $json
}

function Convert-ToCommandLineArgument {
    param([string]$Value)

    if ($null -eq $Value) {
        return '""'
    }

    if ($Value -notmatch '[\s"]') {
        return $Value
    }

    $escaped = $Value -replace '(\\*)"', '$1$1\"'
    $escaped = $escaped -replace '(\\+)$', '$1$1'
    return '"' + $escaped + '"'
}

function Join-ProcessArguments {
    param([string[]]$ArgumentValues)

    return ($ArgumentValues | ForEach-Object { Convert-ToCommandLineArgument -Value $_ }) -join " "
}

function Convert-ToShellSingleQuotedArgument {
    param([string]$Value)

    return "'" + $Value.Replace("'", "'""'""'") + "'"
}

function Resolve-SshExecutable {
    $candidates = @(
        "C:\Windows\System32\OpenSSH\ssh.exe"
    )

    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) {
            return (Resolve-Path $candidate).Path
        }
    }

    $sshCommand = Get-Command ssh.exe -ErrorAction SilentlyContinue
    if ($sshCommand) {
        return $sshCommand.Source
    }

    throw "No se encontro ssh.exe de OpenSSH."
}

function New-AskPassScript {
    param(
        [string]$Root,
        [string]$PasswordValue
    )

    $path = Join-Path $Root "ssh-askpass.cmd"
    $content = @(
        "@echo off",
        "echo $PasswordValue"
    ) -join "`r`n"
    [System.IO.File]::WriteAllText($path, $content + "`r`n", [System.Text.Encoding]::ASCII)
    return $path
}

function New-SshProcessStartInfo {
    param([string]$RemoteCommand)

    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $script:SshExe
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.Environment["SSH_ASKPASS"] = $script:AskPassPath
    $psi.Environment["SSH_ASKPASS_REQUIRE"] = "force"
    $psi.Environment["DISPLAY"] = "codex"

    $argumentValues = @(
        "-o", "BatchMode=no",
        "-o", "PreferredAuthentications=password,keyboard-interactive",
        "-o", "PubkeyAuthentication=no",
        "-o", "StrictHostKeyChecking=no",
        "-o", "UserKnownHostsFile=NUL",
        "-o", "LogLevel=ERROR",
        "-p", $script:SshPort.ToString(),
        ("{0}@{1}" -f $script:SshUser, $script:SshHost),
        $RemoteCommand
    )

    $psi.Arguments = Join-ProcessArguments -ArgumentValues $argumentValues
    return $psi
}

function Wait-ProcessOrTimeout {
    param(
        [System.Diagnostics.Process]$Process,
        [int]$TimeoutMs
    )

    $timedOut = $false
    if ($TimeoutMs -gt 0) {
        if (-not $Process.WaitForExit($TimeoutMs)) {
            $timedOut = $true
            try {
                $Process.Kill()
            }
            catch {
            }
        }
    }

    $Process.WaitForExit()
    return $timedOut
}

function Invoke-SshTextCommand {
    param(
        [string]$RemoteCommand,
        [string]$StdInPath,
        [int]$TimeoutMs = 0
    )

    $psi = New-SshProcessStartInfo -RemoteCommand $RemoteCommand
    $process = [System.Diagnostics.Process]::Start($psi)

    if ($StdInPath) {
        $inputStream = [System.IO.File]::OpenRead($StdInPath)
        try {
            $inputStream.CopyTo($process.StandardInput.BaseStream)
        }
        finally {
            $inputStream.Dispose()
            $process.StandardInput.Close()
        }
    }
    else {
        $process.StandardInput.Close()
    }

    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $timedOut = Wait-ProcessOrTimeout -Process $process -TimeoutMs $TimeoutMs

    return [PSCustomObject]@{
        ExitCode = $process.ExitCode
        StdOut = $stdoutTask.GetAwaiter().GetResult()
        StdErr = $stderrTask.GetAwaiter().GetResult()
        TimedOut = $timedOut
        Command = $RemoteCommand
    }
}

function Invoke-RemoteScript {
    param(
        [string]$RemoteDirValue,
        [string]$ScriptContent,
        [string[]]$ScriptArguments = @(),
        [int]$TimeoutMs = 0
    )

    $tempScript = Join-Path $script:WorkRoot ("remote-" + [guid]::NewGuid().ToString("N") + ".sh")
    $normalizedScript = $ScriptContent -replace "`r`n", "`n"
    [System.IO.File]::WriteAllText($tempScript, $normalizedScript, (New-Utf8NoBomEncoding))

    $remoteArgumentLine = @("bash", "-s", "--", $RemoteDirValue) + $ScriptArguments

    try {
        return Invoke-SshTextCommand `
            -RemoteCommand (Join-ProcessArguments -ArgumentValues $remoteArgumentLine) `
            -StdInPath $tempScript `
            -TimeoutMs $TimeoutMs
    }
    finally {
        Remove-Item $tempScript -Force -ErrorAction SilentlyContinue
    }
}

function Assert-RemoteSuccess {
    param(
        [string]$Label,
        [pscustomobject]$Result
    )

    if ($Result.TimedOut) {
        throw "$Label excedio el tiempo de espera."
    }

    if ($Result.ExitCode -ne 0) {
        throw "$Label fallo.`nSTDOUT:`n$($Result.StdOut)`nSTDERR:`n$($Result.StdErr)"
    }
}

function Receive-RemoteBinaryFile {
    param(
        [string]$RemotePath,
        [string]$LocalPath,
        [int]$TimeoutMs = 0
    )

    $psi = New-SshProcessStartInfo -RemoteCommand ("cat -- " + (Convert-ToShellSingleQuotedArgument -Value $RemotePath))
    $process = [System.Diagnostics.Process]::Start($psi)
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $outFile = [System.IO.File]::Open($LocalPath, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)

    try {
        $process.StandardInput.Close()
        $process.StandardOutput.BaseStream.CopyTo($outFile)
    }
    finally {
        $outFile.Dispose()
    }

    $timedOut = Wait-ProcessOrTimeout -Process $process -TimeoutMs $TimeoutMs
    $stderr = $stderrTask.GetAwaiter().GetResult()

    if ($timedOut) {
        throw "La descarga de $RemotePath excedio el tiempo de espera."
    }

    if ($process.ExitCode -ne 0) {
        throw "La descarga de $RemotePath fallo.`nSTDERR:`n$stderr"
    }

    if (-not (Test-Path $LocalPath) -or (Get-Item $LocalPath).Length -eq 0) {
        throw "La descarga de $RemotePath devolvio contenido vacio."
    }
}

function Send-RemoteBinaryFile {
    param(
        [string]$LocalPath,
        [string]$RemotePath,
        [int]$TimeoutMs = 0
    )

    $remoteCommand = "sh -lc " + (Convert-ToShellSingleQuotedArgument -Value ("cat > " + (Convert-ToShellSingleQuotedArgument -Value $RemotePath)))
    $psi = New-SshProcessStartInfo -RemoteCommand $remoteCommand
    $process = [System.Diagnostics.Process]::Start($psi)
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $inputStream = [System.IO.File]::OpenRead($LocalPath)

    try {
        $inputStream.CopyTo($process.StandardInput.BaseStream)
    }
    finally {
        $inputStream.Dispose()
        $process.StandardInput.Close()
    }

    $timedOut = Wait-ProcessOrTimeout -Process $process -TimeoutMs $TimeoutMs
    $stdout = $stdoutTask.GetAwaiter().GetResult()
    $stderr = $stderrTask.GetAwaiter().GetResult()

    if ($timedOut) {
        throw "La subida de $LocalPath excedio el tiempo de espera."
    }

    if ($process.ExitCode -ne 0) {
        throw "La subida de $LocalPath fallo.`nSTDOUT:`n$stdout`nSTDERR:`n$stderr"
    }
}

function Get-FileLines {
    param([string]$PathValue)

    if (-not (Test-Path $PathValue)) {
        return @()
    }

    return [string[]](Get-Content -Path $PathValue)
}

function Test-SameSequence {
    param(
        [string[]]$Left,
        [string[]]$Right
    )

    if ($Left.Count -ne $Right.Count) {
        return $false
    }

    for ($index = 0; $index -lt $Left.Count; $index++) {
        if ($Left[$index] -ne $Right[$index]) {
            return $false
        }
    }

    return $true
}

function Find-FirstDifference {
    param(
        [string[]]$Left,
        [string[]]$Right
    )

    $limit = [Math]::Min($Left.Count, $Right.Count)
    for ($index = 0; $index -lt $limit; $index++) {
        if ($Left[$index] -ne $Right[$index]) {
            return [PSCustomObject]@{
                Index = $index
                Original = $Left[$index]
                Decrypted = $Right[$index]
            }
        }
    }

    if ($Left.Count -ne $Right.Count) {
        return [PSCustomObject]@{
            Index = $limit
            Original = $(if ($Left.Count -gt $limit) { $Left[$limit] } else { $null })
            Decrypted = $(if ($Right.Count -gt $limit) { $Right[$limit] } else { $null })
        }
    }

    return $null
}

function Get-Sha256HexFromLines {
    param([string[]]$Lines)

    $joined = [string]::Join("`n", $Lines)
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($joined)
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        $hash = $sha256.ComputeHash($bytes)
        return ([System.BitConverter]::ToString($hash)).Replace("-", "")
    }
    finally {
        $sha256.Dispose()
    }
}

if (-not $Password) {
    throw "Define -Password o la variable VERIFICATUM_SSH_PASSWORD."
}

$ProjectRoot = (Resolve-Path $ProjectRoot).Path
if (-not $VotesPath) {
    $VotesPath = Join-Path $ProjectRoot "recursos\shuffled_votes.txt"
}
else {
    $VotesPath = (Resolve-Path $VotesPath).Path
}

$ExePath = Join-Path $ProjectRoot ("dist\windows\image\{0}\{0}.exe" -f $AppName)
$BuildExeScript = Join-Path $ProjectRoot "scripts\windows\build-cifrador-exe.ps1"
$HostLabel = ($SshHost -replace '[^A-Za-z0-9._-]', '_')
$script:WorkRoot = Join-Path $ProjectRoot (".build\remote-mix-test-" + $HostLabel)
New-Item -ItemType Directory -Force -Path $script:WorkRoot | Out-Null

$RemoteSetupLogPath = Join-Path $script:WorkRoot "remote-setup.log"
$RemoteMixLogPath = Join-Path $script:WorkRoot "remote-mix.log"
$ExeLogPath = Join-Path $script:WorkRoot "cifrador.log"
$ExeStdOutLogPath = Join-Path $script:WorkRoot "cifrador.stdout.log"
$ExeStdErrLogPath = Join-Path $script:WorkRoot "cifrador.stderr.log"
$SummaryPath = Join-Path $script:WorkRoot "summary.json"
$PublicKeyPath = Join-Path $script:WorkRoot "publicKey"
$CiphertextsExtPath = Join-Path $script:WorkRoot "ciphertexts_ext"
$PlaintextsPath = Join-Path $script:WorkRoot "plaintexts"

if ($RebuildExe.IsPresent -or -not (Test-Path $ExePath)) {
    & powershell -NoProfile -ExecutionPolicy Bypass -File $BuildExeScript -ProjectRoot $ProjectRoot -AppName $AppName -AppVersion $AppVersion
    if ($LASTEXITCODE -ne 0) {
        throw "No se pudo generar $AppName.exe."
    }
}

if (-not (Test-Path $ExePath)) {
    throw "No se encontro el ejecutable esperado: $ExePath"
}

$script:SshExe = Resolve-SshExecutable
$script:SshHost = $SshHost
$script:SshPort = $Port
$script:SshUser = $User
$script:AskPassPath = New-AskPassScript -Root $script:WorkRoot -PasswordValue $Password
$SafeSudoPassword = Convert-ToShellSingleQuotedArgument -Value $Password

Write-Host "[1/6] Preparando eleccion remota en $RemoteDir"

$remoteSetupScript = @"
set -euo pipefail
REMOTE_DIR="`$1"
CURRENT_HOST="`$(hostname)"
NORMALIZED_HOST="`$(printf '%s' "`$CURRENT_HOST" | tr '[:upper:]' '[:lower:]')"
if [ "`$CURRENT_HOST" != "`$NORMALIZED_HOST" ]; then
  printf '%s\n' $SafeSudoPassword | sudo -S hostname "`$NORMALIZED_HOST" >/dev/null 2>&1 || true
fi
if [ "`$(hostname)" != "`$NORMALIZED_HOST" ]; then
  echo "No se pudo normalizar el hostname remoto a minúsculas. Host actual: `$(hostname)" >&2
  exit 1
fi
rm -rf "`$REMOTE_DIR"
mkdir -p "`$REMOTE_DIR"
cd "`$REMOTE_DIR"
PGROUP=`$(vog -gen ECqPGroup -name "P-256")
vmni -prot -sid 'ONPE' -name 'Eleccion Onpe' -nopart 1 -thres 1 -pgroup "`$PGROUP"
RAND=`$(vog -gen RandomDevice /dev/urandom)
vmni -party -e -name "Servidor01" -hint "localhost:4041" -http "http://localhost:8041" -rand "`$RAND"
cp localProtInfo.xml protInfo01.xml
vmni -merge protInfo01.xml protInfo.xml
vmn -keygen -e publicKey
wc -c publicKey
"@

$remoteSetupResult = Invoke-RemoteScript `
    -RemoteDirValue $RemoteDir `
    -ScriptContent $remoteSetupScript `
    -TimeoutMs 240000
Write-TextFile -PathValue $RemoteSetupLogPath -Content ($remoteSetupResult.StdOut + $remoteSetupResult.StdErr)
Assert-RemoteSuccess -Label "La preparacion remota" -Result $remoteSetupResult

Write-Host "[2/6] Descargando publicKey"
Receive-RemoteBinaryFile -RemotePath ($RemoteDir + "/publicKey") -LocalPath $PublicKeyPath -TimeoutMs 120000

Write-Host "[3/6] Ejecutando $AppName.exe"
foreach ($path in @($CiphertextsExtPath, $ExeLogPath, $ExeStdOutLogPath, $ExeStdErrLogPath, $PlaintextsPath, $SummaryPath)) {
    if (Test-Path $path) {
        Remove-Item $path -Force -ErrorAction SilentlyContinue
    }
}

$rngFlag = "-" + $RngMode.ToLowerInvariant()
$previousRngDevice = $env:ELGAMAL_RNG_DEVICE
if ($RngDevice -and -not [string]::IsNullOrWhiteSpace($RngDevice)) {
    $env:ELGAMAL_RNG_DEVICE = $RngDevice.Trim()
}

try {
    $exeProcess = Start-Process `
        -FilePath $ExePath `
        -ArgumentList @($PublicKeyPath, $VotesPath, $CiphertextsExtPath, $rngFlag) `
        -NoNewWindow `
        -Wait `
        -PassThru `
        -RedirectStandardOutput $ExeStdOutLogPath `
        -RedirectStandardError $ExeStdErrLogPath
} finally {
    if ($null -eq $previousRngDevice) {
        Remove-Item Env:ELGAMAL_RNG_DEVICE -ErrorAction SilentlyContinue
    } else {
        $env:ELGAMAL_RNG_DEVICE = $previousRngDevice
    }
}

$exeLogContent = ""
if (Test-Path $ExeStdOutLogPath) {
    $exeLogContent += (Get-Content $ExeStdOutLogPath -Raw)
}
if (Test-Path $ExeStdErrLogPath) {
    $exeLogContent += (Get-Content $ExeStdErrLogPath -Raw)
}
Write-TextFile -PathValue $ExeLogPath -Content $exeLogContent

if ($exeProcess.ExitCode -ne 0) {
    throw "$AppName.exe fallo. Revisa $ExeLogPath"
}

if (-not (Test-Path $CiphertextsExtPath) -or (Get-Item $CiphertextsExtPath).Length -eq 0) {
    throw "ciphertexts_ext no fue generado correctamente."
}

if (Test-Path $ExeLogPath) {
    Get-Content $ExeLogPath -Tail 5 | ForEach-Object { Write-Host $_ }
}

Write-Host "[4/6] Subiendo ciphertexts_ext"
Send-RemoteBinaryFile -LocalPath $CiphertextsExtPath -RemotePath ($RemoteDir + "/ciphertexts_ext") -TimeoutMs 240000

Write-Host "[5/6] Ejecutando shuffle, decrypt y verificacion"

$remoteMixScript = @'
set -euo pipefail
REMOTE_DIR="$1"
cd "$REMOTE_DIR"
vmnc -ciphs -sloppy -ini native -width 1 ciphertexts_ext ciphertexts
vmn -shuffle privInfo.xml protInfo.xml ciphertexts ciphertextsout
vmn -decrypt privInfo.xml protInfo.xml ciphertextsout plaintexts_orig
vmnc -plain -outi native plaintexts_orig plaintexts
vmnv -v -e -mix protInfo.xml dir/nizkp/default
wc -l ciphertexts_ext plaintexts
'@

$remoteMixResult = Invoke-RemoteScript `
    -RemoteDirValue $RemoteDir `
    -ScriptContent $remoteMixScript `
    -TimeoutMs 600000
Write-TextFile -PathValue $RemoteMixLogPath -Content ($remoteMixResult.StdOut + $remoteMixResult.StdErr)
Assert-RemoteSuccess -Label "La mezcla remota" -Result $remoteMixResult

Write-Host "[6/6] Descargando plaintexts y comparando votos"
Receive-RemoteBinaryFile -RemotePath ($RemoteDir + "/plaintexts") -LocalPath $PlaintextsPath -TimeoutMs 120000

[string[]]$originalVotes = Get-FileLines -PathValue $VotesPath
[string[]]$decryptedVotes = Get-FileLines -PathValue $PlaintextsPath
[string[]]$originalVotesSorted = @($originalVotes | Sort-Object)
[string[]]$decryptedVotesSorted = @($decryptedVotes | Sort-Object)

$sameOrder = Test-SameSequence -Left $originalVotes -Right $decryptedVotes
$multisetEqual = Test-SameSequence -Left $originalVotesSorted -Right $decryptedVotesSorted
$firstOrderDiff = Find-FirstDifference -Left $originalVotes -Right $decryptedVotes
$firstMultisetDiff = $null
if (-not $multisetEqual) {
    $firstMultisetDiff = Find-FirstDifference -Left $originalVotesSorted -Right $decryptedVotesSorted
}

$summary = [PSCustomObject]@{
    host = $SshHost
    port = $Port
    user = $User
    remote_dir = $RemoteDir
    exe_path = $ExePath
    public_key_path = $PublicKeyPath
    ciphertexts_ext_path = $CiphertextsExtPath
    plaintexts_path = $PlaintextsPath
    original_count = $originalVotes.Count
    decrypted_count = $decryptedVotes.Count
    same_order = $sameOrder
    multiset_equal = $multisetEqual
    sorted_sha256_original = Get-Sha256HexFromLines -Lines $originalVotesSorted
    sorted_sha256_decrypted = Get-Sha256HexFromLines -Lines $decryptedVotesSorted
    first_order_diff = $firstOrderDiff
    first_multiset_diff = $firstMultisetDiff
    remote_setup_log = $RemoteSetupLogPath
    remote_mix_log = $RemoteMixLogPath
    exe_log = $ExeLogPath
}

Write-JsonFile -PathValue $SummaryPath -InputObject $summary

Write-Host ("Resumen comparacion: originales={0}, descifrados={1}, mismo_conjunto={2}, mismo_orden={3}" -f `
    $summary.original_count, `
    $summary.decrypted_count, `
    $summary.multiset_equal, `
    $summary.same_order)
Write-Host "Resumen: $SummaryPath"

if (-not $multisetEqual) {
    throw "La mezcla remota no preservo el conjunto de votos. Revisa $SummaryPath"
}

if ($sameOrder) {
    throw "Los votos descifrados quedaron en el mismo orden. Revisa $SummaryPath"
}

Write-Host "Prueba remota completada correctamente."
