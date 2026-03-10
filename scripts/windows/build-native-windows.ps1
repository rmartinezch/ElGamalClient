param(
    [string]$ProjectRoot = (Resolve-Path "$PSScriptRoot/../..").Path,
    [string]$VecSourcePath,
    [string]$GmpmeeSourcePath,
    [string]$VecjSourcePath,
    [string]$VmgjSourcePath,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$MsysRoot = "C:\msys64"
)

$ErrorActionPreference = "Stop"

$VecVersion = "2.5.0"
$GmpmeeVersion = "2.1.0"
$VecjVersion = "2.2.0"
$VmgjVersion = "1.3.0"

function Resolve-PreferredPath {
    param(
        [string]$ExplicitPath,
        [string[]]$Candidates,
        [string]$Label
    )

    if ($ExplicitPath) {
        if (-not (Test-Path $ExplicitPath)) {
            throw "No se encontro $Label en la ruta indicada: $ExplicitPath"
        }
        return (Resolve-Path $ExplicitPath).Path
    }

    foreach ($candidate in $Candidates) {
        if ($candidate -and (Test-Path $candidate)) {
            return (Resolve-Path $candidate).Path
        }
    }

    $candidateList = ($Candidates | Where-Object { $_ }) -join "; "
    throw "No se pudo ubicar $Label. Rutas probadas: $candidateList"
}

function Resolve-JavaHome {
    param([string]$PreferredJavaHome)

    $javacCandidates = @()

    if ($PreferredJavaHome) {
        $javacCandidates += (Join-Path $PreferredJavaHome "bin\javac.exe")
    }

    $javacCommand = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($javacCommand) {
        $javacCandidates += $javacCommand.Source
    }

    foreach ($javacPath in $javacCandidates | Select-Object -Unique) {
        if (Test-Path $javacPath) {
            return Split-Path (Split-Path $javacPath -Parent) -Parent
        }
    }

    throw "No se encontro javac.exe. Define JAVA_HOME o instala un JDK."
}

function Ensure-Msys2 {
    param(
        [string]$MsysRootPath,
        [string]$ProjectRootPath
    )

    $bashPath = Join-Path $MsysRootPath "usr\bin\bash.exe"
    if (Test-Path $bashPath) {
        return
    }

    $buildRoot = Join-Path $ProjectRootPath ".build"
    $installerPath = Join-Path $buildRoot "msys2-base-x86_64-latest.sfx.exe"

    New-Item -ItemType Directory -Force -Path $buildRoot | Out-Null
    Invoke-WebRequest -UseBasicParsing `
        -Uri "https://github.com/msys2/msys2-installer/releases/latest/download/msys2-base-x86_64-latest.sfx.exe" `
        -OutFile $installerPath

    & $installerPath "-y" "-oC:\"

    if (-not (Test-Path $bashPath)) {
        throw "No se pudo instalar MSYS2 en $MsysRootPath"
    }
}

function Convert-ToMsysPath {
    param(
        [string]$Path,
        [string]$CygpathExe
    )

    return (& $CygpathExe -u $Path).Trim()
}

function Invoke-Ucrt64 {
    param(
        [string]$Script,
        [string]$FailureMessage,
        [string]$BashExe,
        [string]$JavaHomePath
    )

    $env:CHERE_INVOKING = "1"
    $env:MSYSTEM = "UCRT64"
    $env:JAVA_HOME = $JavaHomePath

    $MsysRootPath = Split-Path (Split-Path (Split-Path $BashExe -Parent) -Parent) -Parent
    $MsysTmp = Join-Path $MsysRootPath "tmp"
    $ScriptName = "codex-{0}.sh" -f ([guid]::NewGuid().ToString("N"))
    $ScriptPath = Join-Path $MsysTmp $ScriptName
    $Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    $NormalizedScript = $Script -replace "`r`n", "`n"

    New-Item -ItemType Directory -Force -Path $MsysTmp | Out-Null
    [System.IO.File]::WriteAllText($ScriptPath, $NormalizedScript, $Utf8NoBom)

    try {
        & $BashExe -lc "source /etc/profile && /tmp/$ScriptName"
    }
    finally {
        Remove-Item $ScriptPath -Force -ErrorAction SilentlyContinue
    }

    if ($LASTEXITCODE -ne 0) {
        throw $FailureMessage
    }
}

$ProjectRoot = (Resolve-Path $ProjectRoot).Path
$ProjectParent = Split-Path $ProjectRoot -Parent
$JavaHome = Resolve-JavaHome -PreferredJavaHome $JavaHome

$VecSourcePath = Resolve-PreferredPath `
    -ExplicitPath $VecSourcePath `
    -Candidates @(
        (Join-Path $ProjectParent "mixnet\verificatum-vec-$VecVersion"),
        (Join-Path $env:USERPROFILE "verificatum-vmn-3.1.0-full\verificatum-vec-$VecVersion")
    ) `
    -Label "verificatum-vec-$VecVersion"

$GmpmeeSourcePath = Resolve-PreferredPath `
    -ExplicitPath $GmpmeeSourcePath `
    -Candidates @(
        (Join-Path $ProjectParent "mixnet\verificatum-gmpmee-$GmpmeeVersion"),
        (Join-Path $env:USERPROFILE "verificatum-vmn-3.1.0-full\verificatum-gmpmee-$GmpmeeVersion")
    ) `
    -Label "verificatum-gmpmee-$GmpmeeVersion"

$VecjSourcePath = Resolve-PreferredPath `
    -ExplicitPath $VecjSourcePath `
    -Candidates @(
        (Join-Path $ProjectParent "mixnet\verificatum-vecj-$VecjVersion"),
        (Join-Path $env:USERPROFILE "verificatum-vmn-3.1.0-full\verificatum-vecj-$VecjVersion")
    ) `
    -Label "verificatum-vecj-$VecjVersion"

$VmgjSourcePath = Resolve-PreferredPath `
    -ExplicitPath $VmgjSourcePath `
    -Candidates @(
        (Join-Path $ProjectParent "mixnet\verificatum-vmgj-$VmgjVersion"),
        (Join-Path $env:USERPROFILE "verificatum-vmn-3.1.0-full\verificatum-vmgj-$VmgjVersion")
    ) `
    -Label "verificatum-vmgj-$VmgjVersion"

Ensure-Msys2 -MsysRootPath $MsysRoot -ProjectRootPath $ProjectRoot

$BashExe = Join-Path $MsysRoot "usr\bin\bash.exe"
$CygpathExe = Join-Path $MsysRoot "usr\bin\cygpath.exe"

$ProjectUnix = Convert-ToMsysPath -Path $ProjectRoot -CygpathExe $CygpathExe
$VecSourceUnix = Convert-ToMsysPath -Path $VecSourcePath -CygpathExe $CygpathExe
$GmpmeeSourceUnix = Convert-ToMsysPath -Path $GmpmeeSourcePath -CygpathExe $CygpathExe
$VecjSourceUnix = Convert-ToMsysPath -Path $VecjSourcePath -CygpathExe $CygpathExe
$VmgjSourceUnix = Convert-ToMsysPath -Path $VmgjSourcePath -CygpathExe $CygpathExe
$JavaHomeUnix = Convert-ToMsysPath -Path $JavaHome -CygpathExe $CygpathExe

$NativeRoot = Join-Path $ProjectRoot ".build\native"
$PrefixRoot = Join-Path $NativeRoot "prefix"
$WindowsNativeRoot = Join-Path $ProjectRoot "libs\windows-x64"
$VecBuildRoot = Join-Path $NativeRoot "verificatum-vec-$VecVersion"
$GmpmeeBuildRoot = Join-Path $NativeRoot "verificatum-gmpmee-$GmpmeeVersion"
$VecjBuildRoot = Join-Path $NativeRoot "verificatum-vecj-$VecjVersion"
$VmgjBuildRoot = Join-Path $NativeRoot "verificatum-vmgj-$VmgjVersion"
$SmokeRoot = Join-Path $NativeRoot "smoke"

New-Item -ItemType Directory -Force -Path $NativeRoot | Out-Null
New-Item -ItemType Directory -Force -Path $WindowsNativeRoot | Out-Null

$PrefixUnix = Convert-ToMsysPath -Path $PrefixRoot -CygpathExe $CygpathExe
$WindowsNativeUnix = Convert-ToMsysPath -Path $WindowsNativeRoot -CygpathExe $CygpathExe
$VecBuildUnix = Convert-ToMsysPath -Path $VecBuildRoot -CygpathExe $CygpathExe
$GmpmeeBuildUnix = Convert-ToMsysPath -Path $GmpmeeBuildRoot -CygpathExe $CygpathExe
$VecjBuildUnix = Convert-ToMsysPath -Path $VecjBuildRoot -CygpathExe $CygpathExe
$VmgjBuildUnix = Convert-ToMsysPath -Path $VmgjBuildRoot -CygpathExe $CygpathExe

Invoke-Ucrt64 `
    -Script "pacman -Syuu --noconfirm" `
    -FailureMessage "Fallo la actualizacion inicial de MSYS2." `
    -BashExe $BashExe `
    -JavaHomePath $JavaHome

Invoke-Ucrt64 `
    -Script "pacman -Syuu --noconfirm" `
    -FailureMessage "Fallo la segunda actualizacion de MSYS2." `
    -BashExe $BashExe `
    -JavaHomePath $JavaHome

Invoke-Ucrt64 `
    -Script "pacman -S --needed --noconfirm autoconf automake base-devel libtool pkgconf mingw-w64-ucrt-x86_64-gcc mingw-w64-ucrt-x86_64-gmp" `
    -FailureMessage "No se pudieron instalar los paquetes requeridos de MSYS2." `
    -BashExe $BashExe `
    -JavaHomePath $JavaHome

$BuildVecScript = @"
set -euo pipefail
PROJECT='$ProjectUnix'
SRC='$VecSourceUnix'
BUILD='$VecBuildUnix'
PREFIX='$PrefixUnix'
rm -rf "`$BUILD"
mkdir -p "`$PROJECT/.build/native"
mkdir -p "`$BUILD"
cp -a "`$SRC/." "`$BUILD"
cd "`$BUILD"
mkdir -p m4
printf '$VecVersion' > .version.m4
autoreconf -fi
./configure --prefix="`$PREFIX"
make -j4
make install
"@

Invoke-Ucrt64 `
    -Script $BuildVecScript `
    -FailureMessage "Fallo la compilacion de verificatum-vec para Windows x64." `
    -BashExe $BashExe `
    -JavaHomePath $JavaHome

$BuildGmpmeeScript = @"
set -euo pipefail
PROJECT='$ProjectUnix'
SRC='$GmpmeeSourceUnix'
BUILD='$GmpmeeBuildUnix'
PREFIX='$PrefixUnix'
rm -rf "`$BUILD"
mkdir -p "`$PROJECT/.build/native" "`$PREFIX/lib" "`$PREFIX/include"
mkdir -p "`$BUILD"
cp -a "`$SRC/." "`$BUILD"
cd "`$BUILD"
autoreconf -fi
./configure --prefix="`$PREFIX"
make -j4 libgmpmee.la
cp .libs/libgmpmee.a "`$PREFIX/lib/libgmpmee.a"
cp gmpmee.h "`$PREFIX/include/gmpmee.h"
"@

Invoke-Ucrt64 `
    -Script $BuildGmpmeeScript `
    -FailureMessage "Fallo la compilacion de verificatum-gmpmee para Windows x64." `
    -BashExe $BashExe `
    -JavaHomePath $JavaHome

$BuildVecjScript = @"
set -euo pipefail
SRC='$VecjSourceUnix'
BUILD='$VecjBuildUnix'
PREFIX='$PrefixUnix'
JAVA_HOME='$JavaHomeUnix'
OUT='$WindowsNativeUnix'
rm -rf "`$BUILD"
mkdir -p "`$OUT"
mkdir -p "`$BUILD"
cp -a "`$SRC/." "`$BUILD"
cd "`$BUILD"
printf 'define(M4_VERSION, $VecjVersion)dnl\n' > scriptmacros.m4
cat scriptmacros.m4 src/java/com/verificatum/vecj/VEC.magic | m4 > src/java/com/verificatum/vecj/VEC.java
"`$JAVA_HOME/bin/javac" -h native src/java/com/verificatum/vecj/VEC.java
mkdir -p out
gcc -shared -O3 -Wall -W -Werror -Wno-attributes -Wno-ignored-attributes \
  -I"`$JAVA_HOME/include" \
  -I"`$JAVA_HOME/include/win32" \
  -I"`$PREFIX/include" \
  -o out/vecj-$VecjVersion.dll \
  native/com_verificatum_vecj_VEC.c \
  native/convert.c \
  "`$PREFIX/lib/libvec.a" \
  /ucrt64/lib/libgmp.a \
  -Wl,--out-implib,out/libvecj-$VecjVersion.dll.a
cp out/vecj-$VecjVersion.dll "`$OUT/vecj-$VecjVersion.dll"
"@

Invoke-Ucrt64 `
    -Script $BuildVecjScript `
    -FailureMessage "Fallo la compilacion de verificatum-vecj para Windows x64." `
    -BashExe $BashExe `
    -JavaHomePath $JavaHome

$VmgjNativeSource = Join-Path $VmgjBuildRoot "native\com_verificatum_vmgj_VMG.c"

$BuildVmgjPrepScript = @"
set -euo pipefail
SRC='$VmgjSourceUnix'
BUILD='$VmgjBuildUnix'
JAVA_HOME='$JavaHomeUnix'
rm -rf "`$BUILD"
mkdir -p "`$BUILD"
cp -a "`$SRC/." "`$BUILD"
cd "`$BUILD"
printf 'define(M4_VERSION, $VmgjVersion)dnl\n' > scriptmacros.m4
cat scriptmacros.m4 src/java/com/verificatum/vmgj/VMG.magic | m4 > src/java/com/verificatum/vmgj/VMG.java
"`$JAVA_HOME/bin/javac" -h native src/java/com/verificatum/vmgj/VMG.java
"@

Invoke-Ucrt64 `
    -Script $BuildVmgjPrepScript `
    -FailureMessage "Fallo la preparacion de fuentes de verificatum-vmgj." `
    -BashExe $BashExe `
    -JavaHomePath $JavaHome

$VmgjNativeContent = Get-Content $VmgjNativeSource -Raw
if ($VmgjNativeContent -notmatch "#include <stdint.h>") {
    $VmgjNativeContent = $VmgjNativeContent -replace "#include <stdlib.h>", "#include <stdlib.h>`r`n#include <stdint.h>"
}
$VmgjNativeContent = $VmgjNativeContent -replace "\(long\)", "(intptr_t)"
Set-Content -Path $VmgjNativeSource -Value $VmgjNativeContent -Encoding ascii

$BuildVmgjScript = @"
set -euo pipefail
BUILD='$VmgjBuildUnix'
PREFIX='$PrefixUnix'
JAVA_HOME='$JavaHomeUnix'
OUT='$WindowsNativeUnix'
cd "`$BUILD"
mkdir -p out
gcc -shared -O3 -Wall -W -Werror -Wno-attributes -Wno-ignored-attributes \
  -I"`$JAVA_HOME/include" \
  -I"`$JAVA_HOME/include/win32" \
  -I"`$PREFIX/include" \
  -o out/vmgj-$VmgjVersion.dll \
  native/com_verificatum_vmgj_VMG.c \
  native/convert.c \
  "`$PREFIX/lib/libgmpmee.a" \
  /ucrt64/lib/libgmp.a \
  -Wl,--out-implib,out/libvmgj-$VmgjVersion.dll.a
cp out/vmgj-$VmgjVersion.dll "`$OUT/vmgj-$VmgjVersion.dll"
"@

Invoke-Ucrt64 `
    -Script $BuildVmgjScript `
    -FailureMessage "Fallo la compilacion de verificatum-vmgj para Windows x64." `
    -BashExe $BashExe `
    -JavaHomePath $JavaHome

New-Item -ItemType Directory -Force -Path $SmokeRoot | Out-Null

$SmokeSource = @'
import java.math.BigInteger;

import com.verificatum.vecj.VEC;
import com.verificatum.vmgj.VMG;

public class SmokeNative {
    public static void main(String[] args) {
        String[] curves = VEC.getCurveNames();
        System.out.println("curves=" + curves.length);
        System.out.println("legendre=" + VMG.legendre(BigInteger.valueOf(2), BigInteger.valueOf(23)));
    }
}
'@

$SmokeJava = Join-Path $SmokeRoot "SmokeNative.java"
Set-Content -Path $SmokeJava -Value $SmokeSource -Encoding ascii

$SmokeClasspath = @(
    (Join-Path $VecjBuildRoot "src\java"),
    (Join-Path $VmgjBuildRoot "src\java"),
    $SmokeRoot
) -join ";"

$JavacExe = Join-Path $JavaHome "bin\javac.exe"
$JavaExe = Join-Path $JavaHome "bin\java.exe"

& $JavacExe -cp $SmokeClasspath $SmokeJava
if ($LASTEXITCODE -ne 0) {
    throw "Fallo la compilacion de la prueba minima JNI."
}

& $JavaExe `
    "-Djava.library.path=$WindowsNativeRoot" `
    -cp $SmokeClasspath `
    SmokeNative

if ($LASTEXITCODE -ne 0) {
    throw "Fallo la prueba minima JNI con vecj/vmgj."
}

Write-Host "Bibliotecas nativas generadas en: $WindowsNativeRoot"
Write-Host " - vecj-$VecjVersion.dll"
Write-Host " - vmgj-$VmgjVersion.dll"

