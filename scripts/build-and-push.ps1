# build-and-push.ps1
# Uso: .\scripts\build-and-push.ps1 -DockerUser jrivera340
param(
    [string]$DockerUser = "jrivera340",
    [string]$Tag = "latest"
)

$ErrorActionPreference = "Stop"

$services = @(
    [pscustomobject]@{ Name = "circleguard-auth-service";         Port = "8180" },
    [pscustomobject]@{ Name = "circleguard-identity-service";     Port = "8083" },
    [pscustomobject]@{ Name = "circleguard-form-service";         Port = "8086" },
    [pscustomobject]@{ Name = "circleguard-promotion-service";    Port = "8088" },
    [pscustomobject]@{ Name = "circleguard-gateway-service";      Port = "8087" },
    [pscustomobject]@{ Name = "circleguard-notification-service"; Port = "8082" }
)

Write-Host "CircleGuard - Build and Push Pipeline" -ForegroundColor Cyan
Write-Host "Docker Hub: $DockerUser | Tag: $Tag" -ForegroundColor Cyan

# STEP 1: Compilar todos los JARs
Write-Host "`n[1/3] Compilando JARs..." -ForegroundColor Yellow
.\gradlew bootJar --no-daemon -x test
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: Gradle build failed" -ForegroundColor Red
    exit 1
}
Write-Host "JARs compilados OK" -ForegroundColor Green

# STEP 2: Docker build por servicio (sin heredoc, sin ampersands)
Write-Host "`n[2/3] Docker build..." -ForegroundColor Yellow
foreach ($svc in $services) {
    $svcName = $svc.Name
    $svcPort = $svc.Port
    $image = "$DockerUser/" + $svcName + ":" + $Tag
    Write-Host "  Building $image ..." -ForegroundColor White

    # Escribir Dockerfile temporal sin caracteres especiales de PowerShell
    $line1 = "FROM eclipse-temurin:21-jre-jammy"
    $line2 = "RUN groupadd -r circleguard"
    $line3 = "RUN useradd -r -g circleguard circleguard"
    $line4 = "USER circleguard"
    $line5 = "WORKDIR /app"
    $line6 = "COPY services/" + $svcName + "/build/libs/*.jar app.jar"
    $line7 = "EXPOSE " + $svcPort
    $line8 = 'ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]'

    $tmpFile = "Dockerfile.tmp." + $svcName
    @($line1,$line2,$line3,$line4,$line5,$line6,$line7,$line8) | Set-Content $tmpFile -Encoding UTF8

    docker build -t $image -f $tmpFile .
    $buildExit = $LASTEXITCODE
    Remove-Item $tmpFile -Force

    if ($buildExit -ne 0) {
        Write-Host "  ERROR building $svcName" -ForegroundColor Red
        exit 1
    }
    Write-Host "  OK: $image" -ForegroundColor Green
}

# STEP 3: Push a Docker Hub
Write-Host "`n[3/3] Push a Docker Hub..." -ForegroundColor Yellow
foreach ($svc in $services) {
    $image = "$DockerUser/" + $svc.Name + ":" + $Tag
    Write-Host "  Pushing $image ..." -ForegroundColor White
    docker push $image
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  ERROR: Push failed. Asegurate de haber hecho docker login primero." -ForegroundColor Red
        exit 1
    }
    Write-Host "  OK: Pushed $image" -ForegroundColor Green
}

Write-Host "`nBUILD Y PUSH COMPLETADO" -ForegroundColor Green
Write-Host "Imagenes en: https://hub.docker.com/u/$DockerUser" -ForegroundColor Green
