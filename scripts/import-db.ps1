param(
    [Parameter(Mandatory = $true)]
    [string]$InputFile,

    [switch]$BackupCurrent
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
$containerBackupPath = "/tmp/local_backup.dump"
$resolvedInput = [System.IO.Path]::GetFullPath($InputFile)

if (-not (Test-Path $resolvedInput)) {
    throw "Input file not found: $resolvedInput"
}

Write-Host "Starting postgres container if needed..."
docker compose up -d postgres | Out-Host
if ($LASTEXITCODE -ne 0) {
    throw "Failed to start postgres container."
}

if ($BackupCurrent) {
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $backupFile = [System.IO.Path]::GetFullPath(".\local_backup_$timestamp.dump")

    Write-Host "Backing up current local database to $backupFile ..."
    docker compose exec -T postgres pg_dump -U $databaseUser -d $databaseName -Fc -f $containerBackupPath
    if ($LASTEXITCODE -ne 0) {
        throw "Current database backup failed."
    }

    docker cp "${containerName}:$containerBackupPath" $backupFile
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to copy current database backup from container."
    }

    docker compose exec -T postgres rm -f $containerBackupPath | Out-Null
}

Write-Host "Copying dump into container..."
docker cp $resolvedInput "${containerName}:$containerDumpPath"
if ($LASTEXITCODE -ne 0) {
    throw "Failed to copy dump file into container."
}

Write-Host "Restoring database. This will replace existing data..."
docker compose exec -T postgres pg_restore -U $databaseUser -d $databaseName --clean --if-exists $containerDumpPath
if ($LASTEXITCODE -ne 0) {
    throw "pg_restore failed."
}

Write-Host "Cleaning up temporary dump in container..."
docker compose exec -T postgres rm -f $containerDumpPath | Out-Null

Write-Host ""
Write-Host "Import complete from:"
Write-Host $resolvedInput
