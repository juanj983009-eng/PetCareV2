$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$environmentFile = Join-Path $projectRoot ".env.demo"
$composeFile = Join-Path $projectRoot "compose.demo.yaml"

if (-not (Get-Command docker -ErrorAction SilentlyContinue) -or -not (docker info 2>$null)) {
    throw "Docker no está disponible. Inicia Docker Desktop e inténtalo nuevamente."
}

if (-not (Test-Path -LiteralPath $environmentFile)) {
    throw "Falta .env.demo; no se ejecuta ninguna operación."
}

docker compose --project-name petcare-demo --env-file $environmentFile -f $composeFile ps
