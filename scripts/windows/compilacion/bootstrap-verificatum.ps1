param(
    [string]$ProjectRoot = (Resolve-Path "$PSScriptRoot/../../..").Path,
    [string]$JavaHome,
    [string]$MavenCmd,
    [string]$VcrSourceRoot,
    [string]$VecjSourceRoot,
    [string]$VmgjJarPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-JavaHome {
    param([string]$Candidate)

    $knownHomes = @(
        $Candidate,
        "C:\Users\soett\.antigravity\extensions\redhat.java-1.51.0-win32-x64\jre\21.0.9-win32-x86_64",
        $env:JAVA_HOME
    ) | Where-Object { $_ }

    foreach ($javaHomeCandidate in $knownHomes) {
        if (Test-Path (Join-Path $javaHomeCandidate "bin\javac.exe")) {
            return (Resolve-Path $javaHomeCandidate).Path
        }
    }

    throw "No se encontro un JAVA_HOME valido con javac.exe."
}

function Resolve-MavenCmd {
    param([string]$Candidate)

    $mvnFromPath = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    $knownCommands = @(
        $Candidate,
        $(if ($mvnFromPath) { $mvnFromPath.Source }),
        "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2024.2.3\plugins\maven\lib\maven3\bin\mvn.cmd"
    ) | Where-Object { $_ }

    foreach ($command in $knownCommands) {
        if (Test-Path $command) {
            return (Resolve-Path $command).Path
        }
    }

    throw "No se encontro mvn.cmd. Configure Maven o pase -MavenCmd."
}

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

function Reset-Directory {
    param([string]$PathValue)

    if (Test-Path $PathValue) {
        Remove-Item -Recurse -Force $PathValue
    }
    New-Item -ItemType Directory -Force -Path $PathValue | Out-Null
}

function Copy-DirectoryContent {
    param(
        [string]$Source,
        [string]$Destination
    )

    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    Copy-Item (Join-Path $Source "*") $Destination -Recurse -Force
}

function Convert-MagicFile {
    param(
        [string]$SourcePath,
        [string]$DestinationPath,
        [hashtable]$FeatureFlags,
        [hashtable]$Replacements
    )

    $lines = Get-Content -LiteralPath $SourcePath
    $stack = New-Object System.Collections.Generic.List[bool]
    $output = New-Object System.Collections.Generic.List[string]

    foreach ($line in $lines) {
        if ($line -match '^\s*//\s*([A-Z0-9]+)_PURE_JAVA_BEGIN\s*$') {
            $feature = $matches[1]
            $enabled = $false
            if ($FeatureFlags.ContainsKey($feature)) {
                $enabled = [bool]$FeatureFlags[$feature]
            }
            [void]$stack.Add(-not $enabled)
            continue
        }

        if ($line -match '^\s*//\s*([A-Z0-9]+)_BEGIN\s*$') {
            $feature = $matches[1]
            $enabled = $false
            if ($FeatureFlags.ContainsKey($feature)) {
                $enabled = [bool]$FeatureFlags[$feature]
            }
            [void]$stack.Add($enabled)
            continue
        }

        if ($line -match '^\s*//\s*([A-Z0-9]+)(?:_PURE_JAVA)?_END\s*$') {
            if ($stack.Count -eq 0) {
                throw "Bloque de preprocesado desbalanceado en $SourcePath"
            }
            $stack.RemoveAt($stack.Count - 1)
            continue
        }

        if ($stack.Contains($false)) {
            continue
        }

        $processed = $line
        foreach ($key in $Replacements.Keys) {
            $processed = $processed.Replace($key, $Replacements[$key])
        }
        [void]$output.Add($processed)
    }

    if ($stack.Count -ne 0) {
        throw "Quedaron bloques de preprocesado abiertos en $SourcePath"
    }

    $parent = Split-Path $DestinationPath -Parent
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllLines($DestinationPath, $output, $utf8NoBom)
}

function Copy-TextResources {
    param(
        [string]$SourceRoot,
        [string]$ClassesRoot
    )

    Get-ChildItem $SourceRoot -Recurse -File -Filter *.txt | ForEach-Object {
        $relative = $_.FullName.Substring($SourceRoot.Length).TrimStart('\')
        $target = Join-Path $ClassesRoot $relative
        New-Item -ItemType Directory -Force -Path (Split-Path $target -Parent) | Out-Null
        Copy-Item $_.FullName $target -Force
    }
}

function Invoke-JavacCompile {
    param(
        [string]$JavacPath,
        [string]$SourceRoot,
        [string]$ClassesRoot,
        [string]$ClassPath
    )

    New-Item -ItemType Directory -Force -Path $ClassesRoot | Out-Null
    $argFile = Join-Path $ClassesRoot "sources.args"
    $javaFiles = Get-ChildItem $SourceRoot -Recurse -File -Filter *.java |
        Sort-Object FullName |
        Select-Object -ExpandProperty FullName

    if (@($javaFiles).Count -eq 0) {
        throw "No se encontraron fuentes Java en $SourceRoot"
    }

    $javaFiles |
        ForEach-Object { '"' + ($_.Replace('\', '/')) + '"' } |
        Set-Content -LiteralPath $argFile -Encoding ascii

    $arguments = @("-encoding", "UTF-8", "-d", $ClassesRoot)
    if ($ClassPath) {
        $arguments += @("-classpath", $ClassPath)
    }
    $arguments += "@$argFile"

    & $JavacPath @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "javac fallo al compilar fuentes desde $SourceRoot"
    }
}

function New-JarFromClasses {
    param(
        [string]$JarToolPath,
        [string]$ClassesRoot,
        [string]$JarPath
    )

    New-Item -ItemType Directory -Force -Path (Split-Path $JarPath -Parent) | Out-Null
    if (Test-Path $JarPath) {
        Remove-Item -Force $JarPath
    }
    & $JarToolPath --create --file $JarPath -C $ClassesRoot .
    if ($LASTEXITCODE -ne 0) {
        throw "jar fallo al empaquetar clases desde $ClassesRoot"
    }
}

function Install-MavenArtifact {
    param(
        [string]$MavenExecutable,
        [string]$JavaHomePath,
        [string]$LocalRepositoryPath,
        [string]$JarPath,
        [string]$GroupId,
        [string]$ArtifactId,
        [string]$Version
    )

    $env:JAVA_HOME = $JavaHomePath
    & $MavenExecutable `
        org.apache.maven.plugins:maven-install-plugin:3.1.2:install-file `
        "-DlocalRepositoryPath=$LocalRepositoryPath" `
        "-Dfile=$JarPath" `
        "-DgroupId=$GroupId" `
        "-DartifactId=$ArtifactId" `
        "-Dversion=$Version" `
        -Dpackaging=jar `
        -DgeneratePom=true
    if ($LASTEXITCODE -ne 0) {
        throw "Maven no pudo instalar ${ArtifactId}:${Version} en $LocalRepositoryPath"
    }
}

$resolvedJavaHome = Resolve-JavaHome -Candidate $JavaHome
$resolvedMavenCmd = Resolve-MavenCmd -Candidate $MavenCmd
$javacPath = Join-Path $resolvedJavaHome "bin\javac.exe"
$jarToolPath = Join-Path $resolvedJavaHome "bin\jar.exe"
$VcrSourceRoot = Resolve-PreferredPath `
    -ExplicitPath $VcrSourceRoot `
    -Candidates @(
        (Join-Path $ProjectRoot "native\verificatum-src\verificatum-vcr-3.1.0"),
        (Join-Path (Split-Path $ProjectRoot -Parent) "mixnet\verificatum-vcr-3.1.0"),
        "D:\_Proyectos\mixnet\verificatum-vcr-3.1.0"
    ) `
    -Label "verificatum-vcr-3.1.0"
$VecjSourceRoot = Resolve-PreferredPath `
    -ExplicitPath $VecjSourceRoot `
    -Candidates @(
        (Join-Path $ProjectRoot "native\verificatum-src\verificatum-vecj-2.2.0"),
        (Join-Path (Split-Path $ProjectRoot -Parent) "mixnet\verificatum-vecj-2.2.0"),
        "D:\_Proyectos\mixnet\verificatum-vecj-2.2.0"
    ) `
    -Label "verificatum-vecj-2.2.0"
$VmgjJarPath = Resolve-PreferredPath `
    -ExplicitPath $VmgjJarPath `
    -Candidates @(
        (Join-Path $ProjectRoot ".mvn\local-repo\com\verificatum\verificatum-vmgj\1.3.0\verificatum-vmgj-1.3.0.jar"),
        (Join-Path $ProjectRoot "native\verificatum-src\verificatum-vmgj-1.3.0\verificatum-vmgj-1.3.0.jar"),
        "C:\Users\soett\verificatum-vmn-3.1.0-full\verificatum-vmgj-1.3.0\verificatum-vmgj-1.3.0.jar"
    ) `
    -Label "verificatum-vmgj-1.3.0.jar"

foreach ($requiredPath in @($VcrSourceRoot, $VecjSourceRoot, $VmgjJarPath, $javacPath, $jarToolPath, $resolvedMavenCmd)) {
    if (-not (Test-Path $requiredPath)) {
        throw "Ruta requerida no encontrada: $requiredPath"
    }
}

$localRepo = Join-Path $ProjectRoot ".mvn\local-repo"
$buildRoot = Join-Path $ProjectRoot ".build\verificatum-bootstrap"
$artifactsRoot = Join-Path $buildRoot "artifacts"
$sourcesRoot = Join-Path $buildRoot "sources"
$classesRoot = Join-Path $buildRoot "classes"

Reset-Directory $buildRoot
New-Item -ItemType Directory -Force -Path $localRepo | Out-Null
New-Item -ItemType Directory -Force -Path $artifactsRoot | Out-Null
New-Item -ItemType Directory -Force -Path $sourcesRoot | Out-Null
New-Item -ItemType Directory -Force -Path $classesRoot | Out-Null

$vecjTempRoot = Join-Path $sourcesRoot "vecj"
$vcrTempRoot = Join-Path $sourcesRoot "vcr"
Copy-DirectoryContent -Source $VecjSourceRoot -Destination $vecjTempRoot
Copy-DirectoryContent -Source $VcrSourceRoot -Destination $vcrTempRoot

Convert-MagicFile `
    -SourcePath (Join-Path $vecjTempRoot "src\java\com\verificatum\vecj\VEC.magic") `
    -DestinationPath (Join-Path $vecjTempRoot "src\java\com\verificatum\vecj\VEC.java") `
    -FeatureFlags @{} `
    -Replacements @{ "M4_VERSION" = "2.2.0" }

$vcrMagicFiles = @(
    "src\java\com\verificatum\arithm\ECqPGroup.magic",
    "src\java\com\verificatum\arithm\ECqPGroupElement.magic",
    "src\java\com\verificatum\arithm\LargeInteger.magic",
    "src\java\com\verificatum\arithm\LargeIntegerFixModPowTab.magic"
)

foreach ($magicFile in $vcrMagicFiles) {
    Convert-MagicFile `
        -SourcePath (Join-Path $vcrTempRoot $magicFile) `
        -DestinationPath (Join-Path $vcrTempRoot ($magicFile -replace '\.magic$', '.java')) `
        -FeatureFlags @{ VMGJ = $true; VECJ = $true } `
        -Replacements @{}
}

$vecjClassesRoot = Join-Path $classesRoot "vecj"
$vecjJarPath = Join-Path $artifactsRoot "verificatum-vecj-2.2.0.jar"
Invoke-JavacCompile `
    -JavacPath $javacPath `
    -SourceRoot (Join-Path $vecjTempRoot "src\java") `
    -ClassesRoot $vecjClassesRoot `
    -ClassPath ""
Copy-TextResources -SourceRoot (Join-Path $vecjTempRoot "src\java") -ClassesRoot $vecjClassesRoot
New-JarFromClasses -JarToolPath $jarToolPath -ClassesRoot $vecjClassesRoot -JarPath $vecjJarPath

$vcrClassesRoot = Join-Path $classesRoot "vcr"
$vcrJarPath = Join-Path $artifactsRoot "verificatum-vcr-vmgj-vecj-3.1.0.jar"
$vcrClassPath = ($vecjJarPath, $VmgjJarPath) -join ';'
Invoke-JavacCompile `
    -JavacPath $javacPath `
    -SourceRoot (Join-Path $vcrTempRoot "src\java") `
    -ClassesRoot $vcrClassesRoot `
    -ClassPath $vcrClassPath
Copy-TextResources -SourceRoot (Join-Path $vcrTempRoot "src\java") -ClassesRoot $vcrClassesRoot
New-JarFromClasses -JarToolPath $jarToolPath -ClassesRoot $vcrClassesRoot -JarPath $vcrJarPath

Install-MavenArtifact `
    -MavenExecutable $resolvedMavenCmd `
    -JavaHomePath $resolvedJavaHome `
    -LocalRepositoryPath $localRepo `
    -JarPath $VmgjJarPath `
    -GroupId "com.verificatum" `
    -ArtifactId "verificatum-vmgj" `
    -Version "1.3.0"

Install-MavenArtifact `
    -MavenExecutable $resolvedMavenCmd `
    -JavaHomePath $resolvedJavaHome `
    -LocalRepositoryPath $localRepo `
    -JarPath $vecjJarPath `
    -GroupId "com.verificatum" `
    -ArtifactId "verificatum-vecj" `
    -Version "2.2.0"

Install-MavenArtifact `
    -MavenExecutable $resolvedMavenCmd `
    -JavaHomePath $resolvedJavaHome `
    -LocalRepositoryPath $localRepo `
    -JarPath $vcrJarPath `
    -GroupId "com.verificatum" `
    -ArtifactId "verificatum-vcr-vmgj-vecj" `
    -Version "3.1.0"

Write-Host "Repositorio Maven local listo en: $localRepo"
Write-Host "JAR VECJ: $vecjJarPath"
Write-Host "JAR VCR:  $vcrJarPath"
