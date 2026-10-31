# build.ps1 - Release build: the server JAR with the web app inside, and optionally the Windows zip and the Docker image.
#
# Usage:
#   .\utilities\build.ps1 -ImageVersion 1.0.0                  JAR only
#   .\utilities\build.ps1 -ImageVersion 1.0.0 -Windows         JAR and the portable Windows zips, x64 and arm64 (needs Windows)
#   .\utilities\build.ps1 -ImageVersion 1.0.0 -Docker -Push    JAR and Docker image, pushed to Docker Hub
#
# The results are copied to build\ in the repository root.
param(
    [Parameter(Mandatory = $true)]
    [string]$ImageVersion,
    [switch]$Windows,
    [switch]$Docker,
    [switch]$Push
)

# Set error action preference to stop on errors
$ErrorActionPreference = "Stop"

# Helper function for retrying commands
function Invoke-WithRetry {
    param(
        [Parameter(Mandatory = $true)]
        [scriptblock]$ScriptBlock,
        [int]$MaxRetries = 3,
        [int]$RetryDelaySeconds = 5
    )

    for ($attempt = 1; $attempt -le $MaxRetries; $attempt++) {
        try {
            & $ScriptBlock
            if ($LASTEXITCODE -ne 0) {
                throw "Command failed with exit code $LASTEXITCODE"
            }
            return # Success
        }
        catch {
            if ($attempt -lt $MaxRetries) {
                Write-Warning "Attempt $attempt of $MaxRetries failed: $($_.Exception.Message)"
                Write-Warning "Retrying in $RetryDelaySeconds seconds..."
                Start-Sleep -Seconds $RetryDelaySeconds
            }
            else {
                Write-Error "All $MaxRetries attempts failed."
                throw # Re-throw the last exception
            }
        }
    }
}

$BasePath = Split-Path -Parent $PSScriptRoot
$BackendFolder = Join-Path $BasePath "server"
$BackendJarFile = Join-Path $BackendFolder "build\libs\server-app.jar"
$WindowsZipFiles = @("x64", "arm64") | ForEach-Object { Join-Path $BackendFolder "build\dist\filemat-$ImageVersion-windows-$_.zip" }
$BuildOutputFolder = Join-Path $BasePath "build"

# Docker configuration
$DockerRepo = "bingud/filemat"
$ImageTag = ""

Write-Host "=== Deployment Build Script ===" -ForegroundColor Green
Write-Host "Base Path: $BasePath" -ForegroundColor Gray
Write-Host "Building version: $ImageVersion" -ForegroundColor Yellow

Push-Location $BackendFolder
try {
    # Step 1: Gradle builds the web app, the JAR and the Windows zips
    Write-Host "`n1. Building with Gradle..." -ForegroundColor Cyan
    $Tasks = @("build")
    if ($Windows) { $Tasks += "windowsDist" }
    & .\gradlew.bat @Tasks "-PreleaseVersion=$ImageVersion"
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
    Write-Host "(Success) Gradle build completed" -ForegroundColor Green

    # Step 2: Docker image
    if ($Docker) {
        Write-Host "`n2. Building Docker container..." -ForegroundColor Cyan
        if (-not (Get-Process -Name 'docker' -ErrorAction SilentlyContinue)) {
            Write-Warning 'Docker is not running.'
        }
        $ImageTag = "${DockerRepo}:${ImageVersion}"
        $LatestTag = "${DockerRepo}:latest"
        Invoke-WithRetry -ScriptBlock {
            docker build -t $ImageTag -t $LatestTag -f Dockerfile .
        }
        Write-Host "(Success) Docker image built: $ImageTag" -ForegroundColor Green

        if ($Push) {
            Write-Host "Pushing to Docker Hub..." -ForegroundColor Yellow
            Invoke-WithRetry -ScriptBlock { docker push $ImageTag }
            Invoke-WithRetry -ScriptBlock { docker push $LatestTag }
            Write-Host "(Success) Images pushed to Docker Hub" -ForegroundColor Green
        }
    }

    # Step 3: Copy the results to build\
    Write-Host "`n3. Copying the results..." -ForegroundColor Cyan
    New-Item -ItemType Directory -Path $BuildOutputFolder -Force | Out-Null
    $DestinationJarPath = Join-Path $BuildOutputFolder "filemat-server-${ImageVersion}.jar"
    Copy-Item -Path $BackendJarFile -Destination $DestinationJarPath -Force
    if ($Windows) {
        Copy-Item -Path $WindowsZipFiles -Destination $BuildOutputFolder -Force
    }

    Write-Host "`n=== Build completed successfully! ===" -ForegroundColor Green
    if ($ImageTag) {
        Write-Host "Image: $ImageTag" -ForegroundColor White
    }
    Write-Host "JAR file: $DestinationJarPath" -ForegroundColor White
    if ($Windows) {
        foreach ($ZipFile in $WindowsZipFiles) {
            Write-Host "Windows zip: $(Join-Path $BuildOutputFolder (Split-Path -Leaf $ZipFile))" -ForegroundColor White
        }
    }
}
catch {
    Write-Host "`nError: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
finally {
    Pop-Location
}
