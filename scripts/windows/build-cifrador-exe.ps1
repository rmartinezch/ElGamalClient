param(
    [string]$ProjectRoot = (Resolve-Path "$PSScriptRoot/../..").Path,
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

function Resolve-MavenExecutable {
    param([string]$PreferredMavenCmd)

    $commandsFromPath = @("mvn.cmd", "mvn") | ForEach-Object {
        Get-Command $_ -ErrorAction SilentlyContinue
    } | Where-Object { $_ } | Select-Object -ExpandProperty Source

    $candidates = @(
        $PreferredMavenCmd,
        $commandsFromPath,
        "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2024.2.3\plugins\maven\lib\maven3\bin\mvn.cmd"
    ) | Where-Object { $_ }

    foreach ($candidate in $candidates | Select-Object -Unique) {
        if ($candidate -and (Test-Path $candidate)) {
            return (Resolve-Path $candidate).Path
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
        if (Test-Path $candidate) {
            return (Resolve-Path $candidate).Path
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
$BootstrapScript = Join-Path $ProjectRoot "scripts\windows\bootstrap-verificatum.ps1"
$BuildNativeScript = Join-Path $ProjectRoot "scripts\windows\build-native-windows.ps1"
$LocalRepoRoot = Join-Path $ProjectRoot ".mvn\local-repo"
$NativeDir = Join-Path $ProjectRoot "libs\windows-x64"
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
& $MavenExecutable -DskipTests package
if ($LASTEXITCODE -ne 0) {
    throw "Maven no pudo compilar el proyecto."
}

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

$GccExe = Resolve-UcrtGcc
if (-not $GccExe) {
    throw "No se encontro gcc.exe de MSYS2 UCRT64 para compilar el launcher."
}

$MsysRoot = Split-Path (Split-Path (Split-Path $GccExe -Parent) -Parent) -Parent
$BashExe = Join-Path $MsysRoot "usr\bin\bash.exe"
$CygpathExe = Join-Path $MsysRoot "usr\bin\cygpath.exe"

if (-not (Test-Path $BashExe) -or -not (Test-Path $CygpathExe)) {
    throw "No se encontraron bash.exe y cygpath.exe en $MsysRoot."
}

$ImageRoot = Join-Path $ProjectRoot "dist\windows\image\$AppName"
$AppRoot = Join-Path $ImageRoot "app"
$RuntimeRoot = Join-Path $ImageRoot "runtime"
$ImageNativeRoot = Join-Path $ImageRoot "libs\windows-x64"
$LauncherBuildRoot = Join-Path $ProjectRoot ".build\launcher"
$LauncherSourcePath = Join-Path $LauncherBuildRoot "cifrador-launcher.c"
$JarPath = Join-Path $ProjectRoot "target\ElGamalCipher-$AppVersion.jar"
$JarName = Split-Path $JarPath -Leaf
$ExePath = Join-Path $ImageRoot ($AppName + ".exe")

if (-not (Test-Path $JarPath)) {
    throw "No se encontro el JAR esperado: $JarPath"
}

if (Test-Path $ImageRoot) {
    Remove-Item -Recurse -Force $ImageRoot
}

if (Test-Path $LauncherBuildRoot) {
    Remove-Item -Recurse -Force $LauncherBuildRoot
}

New-Item -ItemType Directory -Force -Path $AppRoot | Out-Null
New-Item -ItemType Directory -Force -Path $RuntimeRoot | Out-Null
New-Item -ItemType Directory -Force -Path $ImageNativeRoot | Out-Null
New-Item -ItemType Directory -Force -Path $LauncherBuildRoot | Out-Null

Copy-Item $JarPath $AppRoot -Force
Copy-Item -Recurse (Join-Path $ProjectRoot "recursos") $ImageRoot -Force
Copy-Item (Join-Path $NativeDir "*") $ImageNativeRoot -Recurse -Force
Write-Host "JAVA_HOME usado: $JavaHome"
& robocopy $JavaHome $RuntimeRoot /E /NFL /NDL /NJH /NJS /NC /NS | Out-Null
if ($LASTEXITCODE -gt 7) {
    throw "No se pudo copiar el runtime Java desde $JavaHome"
}

$LauncherSource = @"
#include <process.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>
#include <windows.h>

static wchar_t *dup_string(const wchar_t *value) {
    size_t length = wcslen(value) + 1;
    wchar_t *copy = (wchar_t *)malloc(length * sizeof(wchar_t));
    if (copy != NULL) {
        memcpy(copy, value, length * sizeof(wchar_t));
    }
    return copy;
}

static wchar_t *join_path(const wchar_t *left, const wchar_t *right) {
    size_t left_length = wcslen(left);
    size_t right_length = wcslen(right);
    int needs_separator = left_length > 0 && left[left_length - 1] != L'\\' && left[left_length - 1] != L'/';
    size_t total = left_length + (needs_separator ? 1 : 0) + right_length + 1;
    wchar_t *joined = (wchar_t *)malloc(total * sizeof(wchar_t));

    if (joined == NULL) {
        return NULL;
    }

    wcscpy(joined, left);
    if (needs_separator) {
        wcscat(joined, L"\\");
    }
    wcscat(joined, right);
    return joined;
}

static int file_exists(const wchar_t *path) {
    DWORD attributes = GetFileAttributesW(path);
    return attributes != INVALID_FILE_ATTRIBUTES && (attributes & FILE_ATTRIBUTE_DIRECTORY) == 0;
}

int wmain(int argc, wchar_t **argv) {
    wchar_t module_path[32768];
    DWORD module_length = GetModuleFileNameW(NULL, module_path, (DWORD)(sizeof(module_path) / sizeof(module_path[0])));

    if (module_length == 0 || module_length >= (DWORD)(sizeof(module_path) / sizeof(module_path[0]))) {
        fwprintf(stderr, L"No se pudo resolver la ruta del ejecutable.\\n");
        return 1;
    }

    while (module_length > 0 && module_path[module_length - 1] != L'\\' && module_path[module_length - 1] != L'/') {
        module_length--;
    }

    if (module_length == 0) {
        fwprintf(stderr, L"No se pudo determinar el directorio del ejecutable.\\n");
        return 1;
    }

    module_path[module_length - 1] = L'\0';

    wchar_t *app_dir = dup_string(module_path);
    wchar_t *java_exe = join_path(app_dir, L"runtime\\\\bin\\\\java.exe");
    wchar_t *jar_path = join_path(app_dir, L"app\\\\$JarName");
    wchar_t *lib_path = join_path(app_dir, L"libs\\\\windows-x64");
    const wchar_t *java_option_prefix = L"-Djava.library.path=";
    size_t option_length = wcslen(java_option_prefix) + wcslen(lib_path) + 1;
    wchar_t *java_option = (wchar_t *)malloc(option_length * sizeof(wchar_t));
    wchar_t **child_argv = (wchar_t **)calloc((size_t)argc + 4, sizeof(wchar_t *));

    if (app_dir == NULL || java_exe == NULL || jar_path == NULL || lib_path == NULL || java_option == NULL || child_argv == NULL) {
        fwprintf(stderr, L"No se pudo reservar memoria para iniciar la aplicacion.\\n");
        free(app_dir);
        free(java_exe);
        free(jar_path);
        free(lib_path);
        free(java_option);
        free(child_argv);
        return 1;
    }

    if (!file_exists(java_exe)) {
        fwprintf(stderr, L"No se encontro el runtime Java esperado en %ls\\n", java_exe);
        free(app_dir);
        free(java_exe);
        free(jar_path);
        free(lib_path);
        free(java_option);
        free(child_argv);
        return 1;
    }

    if (!file_exists(jar_path)) {
        fwprintf(stderr, L"No se encontro el JAR esperado en %ls\\n", jar_path);
        free(app_dir);
        free(java_exe);
        free(jar_path);
        free(lib_path);
        free(java_option);
        free(child_argv);
        return 1;
    }

    swprintf(java_option, option_length, L"%ls%ls", java_option_prefix, lib_path);

    child_argv[0] = java_exe;
    child_argv[1] = java_option;
    child_argv[2] = L"-jar";
    child_argv[3] = jar_path;

    for (int index = 1; index < argc; index++) {
        child_argv[index + 3] = argv[index];
    }

    child_argv[argc + 3] = NULL;

    intptr_t exit_code = _wspawnv(_P_WAIT, java_exe, (const wchar_t * const *)child_argv);

    free(app_dir);
    free(java_exe);
    free(jar_path);
    free(lib_path);
    free(java_option);
    free(child_argv);

    if (exit_code == -1) {
        fwprintf(stderr, L"No se pudo iniciar el runtime Java embebido.\\n");
        return 1;
    }

    return (int)exit_code;
}
"@

[System.IO.File]::WriteAllText($LauncherSourcePath, $LauncherSource, [System.Text.UTF8Encoding]::new($false))

$LauncherSourceUnix = (& $CygpathExe -u $LauncherSourcePath).Trim()
$ExeUnix = (& $CygpathExe -u $ExePath).Trim()
$env:CHERE_INVOKING = "1"
$env:MSYSTEM = "UCRT64"

& $BashExe -lc "source /etc/profile && /ucrt64/bin/gcc -municode -O2 -s -static-libgcc -o '$ExeUnix' '$LauncherSourceUnix'"
if ($LASTEXITCODE -ne 0) {
    throw "gcc no pudo compilar $AppName.exe."
}

if (-not (Test-Path $ExePath)) {
    throw "No se encontro el ejecutable esperado: $ExePath"
}

Write-Host "Runtime copiado en: $RuntimeRoot"
Write-Host "Ejecutable generado en: $ExePath"
