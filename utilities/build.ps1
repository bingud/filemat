# build.ps1 - Deployment building script
param(
    [string]$ImageVersion,
    [switch]$NoPush
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


# Get script directory and set base path
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$BasePath = Split-Path -Parent $ScriptDir

# Define paths relative to base directory
$FrontendFolder = Join-Path $BasePath "web"
$BackendFolder = Join-Path $BasePath "server"
$FrontendBuildFolder = Join-Path $FrontendFolder "build"
$BackendStaticFolder = Join-Path $BackendFolder "src\main\resources\static"
$DockerfilePath = Join-Path $BackendFolder "Dockerfile"
$BackendJarFile = Join-Path $BackendFolder "build\libs\server-app.jar"
$BuildOutputFolder = Join-Path $BasePath "build"

# Docker configuration
$DockerRepo = "bingud/filemat"
$ImageTag = "" # Initialize variable

Write-Host "=== Deployment Build Script ===" -ForegroundColor Green
Write-Host "Base Path: $BasePath" -ForegroundColor Gray

if (-not (Get-Process -Name 'docker' -ErrorAction SilentlyContinue)) {
    Write-Warning 'Docker is not running.'
    Start-Sleep -Seconds 1
}

# Get image version from user if not provided
if (-not $ImageVersion) {
    $ImageVersion = Read-Host "Enter image version"
    if (-not $ImageVersion) {
        Write-Host "Error: Image version is required" -ForegroundColor Red
        exit 1
    }
}

Write-Host "Building version: $ImageVersion" -ForegroundColor Yellow

try {
    # Step 1: Build frontend
    Write-Host "`n1. Building frontend..." -ForegroundColor Cyan
    Set-Location $FrontendFolder
    if (-not (Test-Path "package.json")) {
        throw "package.json not found in frontend folder"
    }
    Invoke-WithRetry -ScriptBlock { npm install }
    if (Test-Path $FrontendBuildFolder) {
        Remove-Item $FrontendBuildFolder -Recurse -Force
    }
    npm run build
    if ($LASTEXITCODE -ne 0) { throw "Frontend build failed" }
    Write-Host "(Success) Frontend build completed" -ForegroundColor Green

    # Step 2: Copy frontend files to backend static folder
    Write-Host "`n2. Copying frontend files to backend..." -ForegroundColor Cyan
    if (-not (Test-Path $BackendStaticFolder)) {
        New-Item -ItemType Directory -Path $BackendStaticFolder -Force | Out-Null
    }
    if (Test-Path $BackendStaticFolder) {
        Get-ChildItem $BackendStaticFolder -Recurse | Remove-Item -Force -Recurse
    }
    if (Test-Path $FrontendBuildFolder) {
        Copy-Item -Path "$FrontendBuildFolder\*" `
            -Destination $BackendStaticFolder -Recurse -Force
        Write-Host `
            "(Success) Frontend files copied to backend static folder" `
            -ForegroundColor Green
    }
    else {
        throw "Frontend build folder not found: $FrontendBuildFolder"
    }

    # Step 3: Build backend
    Write-Host "`n3. Building backend..." -ForegroundColor Cyan
    Set-Location $BackendFolder
    if (-not (Test-Path "gradlew.bat") -and -not (Test-Path "gradlew")) {
        throw "Gradle wrapper not found in backend folder"
    }
    $GradlewCommand = if (Test-Path "gradlew.bat") { ".\gradlew.bat" } else { ".\gradlew" }
    & $GradlewCommand build
    if ($LASTEXITCODE -ne 0) { throw "Backend build failed" }
    if (-not (Test-Path $BackendJarFile)) {
        throw "Backend jar file not found: $BackendJarFile"
    }
    Write-Host "(Success) Backend build completed" -ForegroundColor Green

    # Step 4 & 6: Docker build and push (optional)
    $BuildDockerChoice = Read-Host "`nDo you want to build a Docker container? (y/N)"
    if ($BuildDockerChoice -match "^[Yy]") {
        # Step 4: Build Docker container
        Write-Host "`n4. Building Docker container..." -ForegroundColor Cyan
        if (-not (Test-Path $DockerfilePath)) {
            throw "Dockerfile not found: $DockerfilePath"
        }
        $ImageTag = "${DockerRepo}:${ImageVersion}"
        $LatestTag = "${DockerRepo}:latest"
        Invoke-WithRetry -ScriptBlock {
            docker build -t $ImageTag -t $LatestTag -f $DockerfilePath .
        }
        Write-Host "(Success) Docker image built: $ImageTag" -ForegroundColor Green

        # Step 6: Push to Docker Hub (optional)
        if (-not $NoPush) {
            Write-Host "`n6. Docker Hub push..." -ForegroundColor Cyan
            $PushChoice = Read-Host "Do you want to push to Docker Hub? (y/N)"
            if ($PushChoice -match "^[Yy]") {
                Write-Host "Pushing to Docker Hub..." -ForegroundColor Yellow
                Invoke-WithRetry -ScriptBlock { docker push $ImageTag }
                Invoke-WithRetry -ScriptBlock { docker push $LatestTag }
                Write-Host "(Success) Images pushed to Docker Hub" -ForegroundColor Green
            }
            else {
                Write-Host "Skipping Docker Hub push" -ForegroundColor Yellow
            }
        }
        else {
            Write-Host "`n6. Skipping Docker Hub push (NoPush flag specified)" `
                -ForegroundColor Yellow
        }
    }
    else {
        Write-Host "`nSkipping Docker build and push." -ForegroundColor Yellow
    }

    # Step 5: Move and rename backend JAR
    Write-Host "`n5. Moving final backend JAR..." -ForegroundColor Cyan
    if (-not (Test-Path $BuildOutputFolder)) {
        New-Item -ItemType Directory -Path $BuildOutputFolder -Force | Out-Null
    }
    $NewJarFileName = "filemat-server-${ImageVersion}.jar"
    $DestinationJarPath = Join-Path $BuildOutputFolder $NewJarFileName
    Move-Item -Path $BackendJarFile -Destination $DestinationJarPath -Force
    if (-not (Test-Path $DestinationJarPath)) {
        throw "Failed to move backend JAR file"
    }
    Write-Host "(Success) Backend JAR moved to $DestinationJarPath" -ForegroundColor Green

    Write-Host "`n=== Build completed successfully! ===" -ForegroundColor Green
    if ($ImageTag) {
        Write-Host "Image: $ImageTag" -ForegroundColor White
    }
    Write-Host "JAR file: $DestinationJarPath" -ForegroundColor White

}
catch {
    Write-Host "`nError: $($_.Exception.Message)" -ForegroundColor Red
    Read-Host 'Press any key to exit'
    exit 1
}
finally {
    Set-Location "$BasePath\utilities"
}

# Usage examples
Write-Host "`nUsage examples:" -ForegroundColor Gray
Write-Host "  .\utilities\build.ps1 -ImageVersion '1.0.0'" -ForegroundColor Gray
Write-Host "  .\utilities\build.ps1 -ImageVersion '1.0.0' -NoPush" `
    -ForegroundColor Gray