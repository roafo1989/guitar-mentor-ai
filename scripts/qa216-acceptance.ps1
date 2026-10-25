#Requires -Version 7.0
<#
.SYNOPSIS
  One-command non-destructive #216 verification of repository code and available local tools.
.EXAMPLE
  pwsh -File .\scripts\qa216-acceptance.ps1
.EXAMPLE
  pwsh -File .\scripts\qa216-acceptance.ps1 -IncludeBrowser
.NOTES
  Does NOT connect to, backup, restore, migrate, or alter DEV/PILOT databases.
  Does NOT submit an HTTP next-lesson request or interact with real accounts.
  Failing, unavailable and manual checks remain visible in the final report.
#>
[CmdletBinding()]
param(
    [switch]$IncludeBrowser
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runId = Get-Date -Format 'yyyyMMdd-HHmmss'
$out = Join-Path $root ('.qa-local/216/' + $runId)
New-Item -ItemType Directory -Force -Path $out | Out-Null
$results = [System.Collections.Generic.List[object]]::new()
function Add-Result([string]$name,[string]$status,[string]$detail,[string]$log = '') {
    $results.Add([pscustomobject]@{ name=$name; status=$status; detail=$detail; log=$log })
    Write-Host ('{0,-6} {1}: {2}' -f $status,$name,$detail)
}
function Run-Step([string]$name,[string]$command,[string[]]$arguments,[string]$workingDirectory) {
    $resolved = Get-Command $command -ErrorAction SilentlyContinue
    if (-not $resolved) {
        Add-Result $name 'SKIP' ('Command missing: ' + $command)
        return
    }
    $log = Join-Path $out (($name -replace '[^a-zA-Z0-9-]','_') + '.log')
    $oldLocation = Get-Location
    try {
        Set-Location $workingDirectory
        $global:LASTEXITCODE = 0
        & $resolved.Source @arguments *> $log
        $exit = $LASTEXITCODE
        if ($null -eq $exit) { $exit = 0 }
        if ($exit -eq 0) { Add-Result $name 'PASS' 'Exit code 0' $log }
        else { Add-Result $name 'FAIL' ('Exit code ' + $exit + '; inspect log') $log }
    } catch {
        # Error messages are not written to the report: avoid leaking secrets.
        Add-Result $name 'FAIL' 'Execution error; inspect local log' $log
    } finally { Set-Location $oldLocation }
}
Write-Host "Guitar Mentor AI #216 acceptance — LOCAL CODE CHECKS ONLY"
Write-Host "Report directory: $out"
Write-Host 'No database writes, no production/PILOT requests, no automatic merge.'
$branch = & git -C $root branch --show-current 2>$null
if ($LASTEXITCODE -ne 0) { Add-Result 'git' 'SKIP' 'Git is unavailable' }
elseif ($branch -ne 'feature/216-manual-next-lesson') {
    Add-Result 'git' 'FAIL' ('Expected feature/216-manual-next-lesson; found ' + $branch)
} else {
    $changes = & git -C $root status --porcelain 2>$null
    if ($changes) { Add-Result 'git' 'FAIL' 'Working directory not clean' }
    else { Add-Result 'git' 'PASS' 'Expected branch and clean working directory' }
}
if (-not (Test-Path (Join-Path $root 'frontend/node_modules'))) {
    Add-Result 'frontend-deps' 'SKIP' 'node_modules absent; install packages via npm ci first'
} else {
    Run-Step 'frontend-tests' 'npm.cmd' @('test') (Join-Path $root 'frontend')
    Run-Step 'frontend-intake' 'npm.cmd' @('run','test:intake') (Join-Path $root 'frontend')
    Run-Step 'frontend-build' 'npm.cmd' @('run','build') (Join-Path $root 'frontend')
    if ($IncludeBrowser) {
        Run-Step 'browser-lesson' 'npm.cmd' @('run','test:lesson-browser') (Join-Path $root 'frontend')
        Run-Step 'browser-pdf' 'npm.cmd' @('run','test:score-browser') (Join-Path $root 'frontend')
    } else {
        Add-Result 'browser-lesson' 'SKIP' 'Use -IncludeBrowser to run Chrome mock-API tests'
        Add-Result 'browser-pdf' 'SKIP' 'Use -IncludeBrowser to run Chrome PDF tests'
    }
}
$wrapper = Join-Path $root 'backend/mvnw.cmd'
if (Test-Path -LiteralPath $wrapper) {
    Run-Step 'backend-tests' $wrapper @('-B','test') (Join-Path $root 'backend')
} else {
    Run-Step 'backend-tests' 'mvn.cmd' @('-B','test') (Join-Path $root 'backend')
}
# These require dedicated approved disposable environments. Never mark PASS based on code review.
Add-Result 'db-copy-migrate-restore' 'SKIP' 'Isolated PostgreSQL copy + migration + restore must be executed separately (TC-24)'
Add-Result 'http-two-actors-csrf' 'SKIP' 'Two disposable authenticated DEV accounts required (TC-18)'
Add-Result 'real-media-ttl' 'SKIP' 'Controlled fixture, TTL and storage checks required (TC-09)'
Add-Result 'retry-restart-races' 'SKIP' 'Disposable backend and data required (TC-10/11/12)'
Add-Result 'manual-accessibility' 'SKIP' 'Real supported browser, keyboard and recording required (TC-22/23)'
$sha = (& git -C $root rev-parse HEAD 2>$null)
$report = [pscustomobject]@{
    feature = '#216'
    revision = $sha
    timestamp = (Get-Date).ToString('o')
    notice = 'Local code checks only; NOT complete release acceptance.'
    results = @($results.ToArray())
}
$reportFile = Join-Path $out 'report.json'
$report | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $reportFile -Encoding utf8
Write-Host ''
Write-Host ('PASS={0}, FAIL={1}, SKIP={2}' -f @($results | Where-Object status -eq 'PASS').Count,@($results | Where-Object status -eq 'FAIL').Count,@($results | Where-Object status -eq 'SKIP').Count)
Write-Host "JSON report: $reportFile"
if (@($results | Where-Object status -eq 'FAIL').Count -gt 0) { exit 1 }
if (@($results | Where-Object status -eq 'SKIP').Count -gt 0) {
    Write-Host 'Partial verification only: skipped checks must not be treated as accepted.'
}
exit 0
