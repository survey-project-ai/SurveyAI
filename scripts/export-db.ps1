param(
    [string]$OutputFile = ".\survey_ai.dump"
)

$ErrorActionPreference = "Stop"

function Require-Command {
    param([string]$Name)

    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command not found: $Name"
    }
}

Require-Command "docker"

$containerName = "survey-ai-postgres"
$databaseName = "survey_ai"
$databaseUser = "survey_ai_user"
$containerDumpPath = "/tmp/survey_ai.dump"
$resolvedOutput = [System.IO.Path]::GetFullPath($OutputFile)
$outputDir = Split-Path -Parent $resolvedOutput

if (-not (Test-Path $outputDir)) {
    New-Item -ItemType Directory -Path $outputDir | Out-Null
}

Write-Host "Starting postgres container if needed..."
docker compose up -d postgres | Out-Host
if ($LASTEXITCODE -ne 0) {
    throw "Failed to start postgres container."
}

Write-Host "Creating database dump inside container..."
docker compose exec -T postgres pg_dump -U $databaseUser -d $databaseName -Fc -f $containerDumpPath
if ($LASTEXITCODE -ne 0) {
    throw "pg_dump failed."
}

Write-Host "Copying dump to $resolvedOutput ..."
docker cp "${containerName}:$containerDumpPath" $resolvedOutput
if ($LASTEXITCODE -ne 0) {
    throw "Failed to copy dump file from container."
}

Write-Host "Cleaning up temporary dump in container..."
docker compose exec -T postgres rm -f $containerDumpPath | Out-Null

Write-Host ""
Write-Host "Export complete:"
Write-Host $resolvedOutput
