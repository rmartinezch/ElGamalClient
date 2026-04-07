param(
    [string]$ProjectRoot = (Resolve-Path "$PSScriptRoot/../../..").Path,
    [string]$JavaHome,
    [string]$MavenCmd,
    [string]$AppVersion = "1.1.0",
    [switch]$ForceNativeBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Normalize-OptionalPath {
    param([string]$Value)

    if (-not $Value) {
        return $null
    }

    $normalized = $Value.Trim()
    if (-not $normalized) {
        return $null
    }

    if ($normalized.Length -ge 2 -and $normalized.StartsWith('"') -and $normalized.EndsWith('"')) {
        $normalized = $normalized.Substring(1, $normalized.Length - 2).Trim()
    }

    if (-not $normalized) {
        return $null
    }

    foreach ($invalidChar in [System.IO.Path]::GetInvalidPathChars()) {
        if ($normalized.Contains([string]$invalidChar)) {
            return $null
        }
    }

    return $normalized
}

function Resolve-JavaHome {
    param([string]$PreferredJavaHome)

    $knownHomes = @(
        (Normalize-OptionalPath $PreferredJavaHome),
        "C:\Users\soett\.antigravity\extensions\redhat.java-1.51.0-win32-x64\jre\21.0.9-win32-x86_64",
        (Normalize-OptionalPath $env:JAVA_HOME)
    ) | Where-Object { $_ }

    foreach ($candidate in $knownHomes | Select-Object -Unique) {
        try {
            $javacPath = Join-Path $candidate "bin\javac.exe"
            if (Test-Path -LiteralPath $javacPath) {
                return (Resolve-Path -LiteralPath $candidate).Path
            }
        }
        catch {
            continue
        }
    }

    $javacCommand = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($javacCommand) {
        return Split-Path (Split-Path $javacCommand.Source -Parent) -Parent
    }

    throw "No se encontro un JDK valido con javac.exe."
}

function Resolve-MavenExecutable {
    param([string]$PreferredMavenCmd)

    $commandsFromPath = @("mvn.cmd", "mvn") | ForEach-Object {
        Get-Command $_ -ErrorAction SilentlyContinue
    } | Where-Object { $_ } | Select-Object -ExpandProperty Source

    $candidates = @(
        (Normalize-OptionalPath $PreferredMavenCmd),
        $commandsFromPath,
        "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2024.2.3\plugins\maven\lib\maven3\bin\mvn.cmd"
    ) | Where-Object { $_ }

    foreach ($candidate in $candidates | Select-Object -Unique) {
        try {
            if ($candidate -and (Test-Path -LiteralPath $candidate)) {
                return (Resolve-Path -LiteralPath $candidate).Path
            }
        }
        catch {
            continue
        }
    }

    throw "No se encontro Maven. Define -MavenCmd o agrega mvn.cmd al PATH."
}

function Test-LocalArtifact {
    param(
        [string]$Root,
        [string]$GroupPath,
        [string]$ArtifactId,
        [string]$Version
    )

    $artifactPath = Join-Path $Root (Join-Path $GroupPath (Join-Path $ArtifactId (Join-Path $Version ($ArtifactId + "-" + $Version + ".jar"))))
    return (Test-Path $artifactPath)
}

function Resolve-UcrtGcc {
    $candidates = @(
        "C:\msys64\ucrt64\bin\gcc.exe"
    )

    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    $gccFromPath = Get-Command gcc.exe -ErrorAction SilentlyContinue
    if ($gccFromPath) {
        return $gccFromPath.Source
    }

    return $null
}

$ProjectRoot = (Resolve-Path $ProjectRoot).Path
$JavaHome = Resolve-JavaHome -PreferredJavaHome $JavaHome
$MavenExecutable = Resolve-MavenExecutable -PreferredMavenCmd $MavenCmd
$BootstrapScript = Join-Path $ProjectRoot "scripts\windows\compilacion\bootstrap-verificatum.ps1"
$BuildNativeScript = Join-Path $ProjectRoot "scripts\windows\compilacion\build-native-windows.ps1"
$LocalRepoRoot = Join-Path $ProjectRoot ".mvn\local-repo"
$NativeDir = Join-Path $ProjectRoot "prebuilt\windows-x64"
$CanonicalJavaDir = Join-Path $ProjectRoot "prebuilt\java"
$RequiredDlls = @(
    "vecj-2.2.0.dll",
    "vmgj-1.3.0.dll"
)

$missingMavenArtifacts = @(@(
    @{ GroupPath = "com\verificatum"; ArtifactId = "verificatum-vmgj"; Version = "1.3.0" },
    @{ GroupPath = "com\verificatum"; ArtifactId = "verificatum-vecj"; Version = "2.2.0" },
    @{ GroupPath = "com\verificatum"; ArtifactId = "verificatum-vcr-vmgj-vecj"; Version = "3.1.0" }
) | Where-Object {
    -not (Test-LocalArtifact -Root $LocalRepoRoot -GroupPath $_.GroupPath -ArtifactId $_.ArtifactId -Version $_.Version)
})

if ($missingMavenArtifacts.Count -gt 0) {
    & powershell -NoProfile -ExecutionPolicy Bypass -File $BootstrapScript -ProjectRoot $ProjectRoot -JavaHome $JavaHome -MavenCmd $MavenExecutable
    if ($LASTEXITCODE -ne 0) {
        throw "Fallo bootstrap-verificatum.ps1."
    }
}

$env:JAVA_HOME = $JavaHome
& $MavenExecutable -DskipTests clean package
if ($LASTEXITCODE -ne 0) {
    throw "Maven no pudo compilar el proyecto."
}

$TargetJarPath = Join-Path $ProjectRoot "target\ElGamalCipher-$AppVersion.jar"
if (-not (Test-Path $TargetJarPath)) {
    throw "No se encontro el JAR esperado tras Maven: $TargetJarPath"
}

New-Item -ItemType Directory -Force -Path $CanonicalJavaDir | Out-Null
$CanonicalJarPath = Join-Path $CanonicalJavaDir ("ElGamalCipher-" + $AppVersion + ".jar")
Copy-Item $TargetJarPath $CanonicalJarPath -Force

$needNativeBuild = $ForceNativeBuild.IsPresent
foreach ($dll in $RequiredDlls) {
    if (-not (Test-Path (Join-Path $NativeDir $dll))) {
        $needNativeBuild = $true
    }
}

$GccExe = Resolve-UcrtGcc
if (-not $GccExe) {
    $needNativeBuild = $true
}

if ($needNativeBuild) {
    & powershell -NoProfile -ExecutionPolicy Bypass -File $BuildNativeScript -ProjectRoot $ProjectRoot -JavaHome $JavaHome
    if ($LASTEXITCODE -ne 0) {
        throw "Fallo build-native-windows.ps1."
    }
}

foreach ($dll in $RequiredDlls) {
    $dllPath = Join-Path $NativeDir $dll
    if (-not (Test-Path $dllPath)) {
        throw "No se encontro la DLL requerida: $dllPath"
    }
}

Write-Host "Cifrador Windows compilado:"
Write-Host " - $CanonicalJarPath"
Write-Host " - $(Join-Path $NativeDir 'vecj-2.2.0.dll')"
Write-Host " - $(Join-Path $NativeDir 'vmgj-1.3.0.dll')"
