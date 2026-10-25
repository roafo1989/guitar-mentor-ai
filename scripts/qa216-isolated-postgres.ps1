#Requires -Version 7.0
<#
Runs PostgreSQL integration verification for #216 only against a NEW disposable Docker container.
Requires Docker Desktop running and a previously pulled postgres:17-alpine image.
No access to DEV/PILOT connection settings, database dump, backup, or existing Docker databases.
#>
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$docker = Get-Command docker -ErrorAction SilentlyContinue
if (-not $docker) { Write-Host 'SKIP: Docker is not installed'; exit 2 }
& docker info *> $null
if ($LASTEXITCODE -ne 0) { Write-Host 'SKIP: Docker engine is not running'; exit 2 }
& docker image inspect postgres:17-alpine *> $null
if ($LASTEXITCODE -ne 0) { Write-Host 'SKIP: postgres:17-alpine image not cached (no automatic downloads)'; exit 2 }
$maven = Join-Path $root 'backend/mvnw.cmd'
if (-not (Test-Path -LiteralPath $maven)) { throw 'Missing backend Maven Wrapper' }
$name = 'qa216-' + ([guid]::NewGuid().ToString('N').Substring(0,12))
$pass = [guid]::NewGuid().ToString('N')
$previous = @{}
foreach($key in @('SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD','GUITAR_MENTOR_TEST_POSTGRES')) {
  $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
}
$created = $false
try {
  # --mount is deliberately omitted: all database data disappears with this disposable container.
  $id = (& docker run --rm -d --name $name -e "POSTGRES_DB=qa216" -e "POSTGRES_USER=qa216" -e "POSTGRES_PASSWORD=$pass" -p '127.0.0.1::5432' postgres:17-alpine)
  if ($LASTEXITCODE -ne 0 -or -not $id) { throw 'Could not create disposable PostgreSQL container' }
  $created = $true
  $port = $null
  for($i=0; $i -lt 35; $i++) {
    $mapped = (& docker port $name '5432/tcp' 2>$null)
    if($mapped -match '127\.0\.0\.1:(\d+)') { $port = $Matches[1] }
    & docker exec -e "PGPASSWORD=$pass" $name pg_isready -U qa216 -d qa216 *> $null
    if($LASTEXITCODE -eq 0 -and $port) { break }
    Start-Sleep -Seconds 1
  }
  if (-not $port -or $LASTEXITCODE -ne 0) { throw 'Isolated PostgreSQL not ready' }
  $url = "jdbc:postgresql://127.0.0.1:$port/qa216"
  if ($url -notmatch '^jdbc:postgresql://127\.0\.0\.1:\d+/qa216$') { throw 'Unsafe test database URL' }
  [Environment]::SetEnvironmentVariable('SPRING_DATASOURCE_URL', $url, 'Process')
  [Environment]::SetEnvironmentVariable('SPRING_DATASOURCE_USERNAME', 'qa216', 'Process')
  [Environment]::SetEnvironmentVariable('SPRING_DATASOURCE_PASSWORD', $pass, 'Process')
  [Environment]::SetEnvironmentVariable('GUITAR_MENTOR_TEST_POSTGRES', 'true', 'Process')
  Write-Host 'PASS: Started ephemeral PostgreSQL at loopback with fresh qa216 database'
  Push-Location (Join-Path $root 'backend')
  try {
    & $maven -B -Dtest=NextLessonPostgresIntegrationTest,SecurityHttpIntegrationTest test
    $code = $LASTEXITCODE
  } finally { Pop-Location }
  if($code -ne 0) { throw "Isolated PostgreSQL test suite failed (exit $code)" }
  Write-Host 'PASS: PostgreSQL next-lesson and HTTP authorization tests'
  exit 0
} finally {
  foreach($key in $previous.Keys) { [Environment]::SetEnvironmentVariable($key,$previous[$key],'Process') }
  if($created) {
    & docker rm -f $name *> $null
    Write-Host 'Disposable database container removed'
  }
}
