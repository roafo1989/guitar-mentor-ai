#Requires -Version 7.0
<#
.SYNOPSIS
 Safely synchronize issue #216 with main and run complete local acceptance.
 Stop on any conflict; never auto-resolve contested code or touch PILOT.
.EXAMPLE
 pwsh -NoProfile -File .\scripts\qa216-finalize.ps1
#>
[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$report=Join-Path $root '.qa-local/216/finalize'
New-Item -ItemType Directory -Force -Path $report | Out-Null
function Step([string]$name,[string[]]$gitArguments) {
 Write-Host "== $name ==" -ForegroundColor Cyan
 & git -C $root @gitArguments
 if($LASTEXITCODE -ne 0){throw "$name failed (exit $LASTEXITCODE)"}
}
try{
 $branch=(& git -C $root branch --show-current).Trim()
 if($LASTEXITCODE -ne 0 -or $branch -ne 'feature/216-manual-next-lesson'){
  throw 'Wrong branch; expected feature/216-manual-next-lesson'
 }
 $dirty=@(& git -C $root status --porcelain)
 if($dirty.Count -gt 0){throw 'Working tree is dirty. Commit or stash your changes first.'}
 Step 'Fetch' @('fetch','origin')
 Step 'Update feature' @('merge','--ff-only','origin/feature/216-manual-next-lesson')
 # Never merge if a local worktree became dirty.
 if(@(& git -C $root status --porcelain).Count -gt 0){throw 'Unexpected dirty working tree'}
 $main=(& git -C $root rev-parse origin/main).Trim()
 $ancestor=(& git -C $root merge-base HEAD origin/main).Trim()
 if($ancestor -ne $main){
  Write-Host "Merge current main into feature (no fast-forward, Git default 3-way conflict resolution)"
  & git -C $root -c commit.gpgsign=false merge --no-edit --no-ff origin/main
  if($LASTEXITCODE -ne 0){
   $unmerged= @(& git -C $root diff --name-only --diff-filter=U)
   $unmerged | Set-Content -LiteralPath (Join-Path $report 'conflicts.txt') -Encoding utf8
   Write-Host 'STOP: merge conflicts require a reviewed resolution. No files were auto-picked.' -ForegroundColor Yellow
   Write-Host "Conflicts saved in $report\conflicts.txt"
   Write-Host 'No tests, push, or release performed. Preserve the working tree for review.'
   exit 3
  }
 }
 # A merge commit can be made even if no tests pass; never push it until checks do.
 Write-Host 'Running complete local acceptance (browser and disposable PostgreSQL).' -ForegroundColor Cyan
 & pwsh -NoProfile -File (Join-Path $root 'scripts/qa216-acceptance.ps1') -IncludeBrowser -IncludeIsolatedPostgres
 if($LASTEXITCODE -ne 0){throw 'Acceptance failed. No push performed.'}
 $latest=Get-ChildItem (Join-Path $root '.qa-local/216') -Directory |
   Where-Object Name -match '^\d{8}-\d{6}$' | Sort-Object Name -Descending | Select-Object -First 1
 if(-not $latest){throw 'No acceptance JSON report found'}
 $qa=Get-Content -Raw -LiteralPath (Join-Path $latest.FullName 'report.json') | ConvertFrom-Json
 $failed=@($qa.results | Where-Object status -eq 'FAIL')
 $passed=@($qa.results | Where-Object status -eq 'PASS')
 $isolated=@($qa.results | Where-Object name -eq 'postgres-isolated-tests')
 if($failed.Count -gt 0 -or $passed.Count -lt 8 -or $isolated.Count -ne 1 -or $isolated[0].status -ne 'PASS'){
  throw 'Release candidate gate not met (need >=8 PASS, 0 FAIL, isolated PG PASS). No push.'
 }
 Step 'Push checked feature only' @('push','origin','HEAD:refs/heads/feature/216-manual-next-lesson')
 Write-Host 'READY: feature branch pushed; draft PR still requires GitHub CI and remaining release gates.' -ForegroundColor Green
 Write-Host "Report: $($latest.FullName)\report.json"
}catch{
 Write-Host "STOP: $($_.Exception.Message)" -ForegroundColor Red
 Write-Host 'No PILOT/prod update, no merge into main.'
 exit 1
}
