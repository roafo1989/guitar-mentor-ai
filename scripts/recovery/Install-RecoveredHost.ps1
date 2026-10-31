#Requires -Version 7.4
param(
    [Parameter(Mandatory)][string]$RecoveryRoot,
    [string]$PilotRoot = 'D:\GuitarMentorPilot',
    [string]$SourceRoot = 'D:\Development\guitar-mentor-ai',
    [string]$SettingsRoot = (Join-Path $env:LOCALAPPDATA 'GuitarMentorAI'),
    [string]$PostgresBin = 'C:\Program Files\PostgreSQL\17\bin',
    [string]$CaddyPath = 'C:\Tools\Caddy\caddy.exe',
    [string]$TailscalePath = 'C:\Program Files\Tailscale\tailscale.exe'
)
. (Join-Path $PSScriptRoot 'Recovery.Common.ps1')
if (-not $IsWindows) { throw 'Используйте Windows / PowerShell 7.4+.' }
$RecoveryRoot = Expand-RecoveryPath $RecoveryRoot
$PilotRoot = Expand-RecoveryPath $PilotRoot
$SourceRoot = Expand-RecoveryPath $SourceRoot
$SettingsRoot = Expand-RecoveryPath $SettingsRoot
if ($SettingsRoot -ne (Expand-RecoveryPath (Join-Path $env:LOCALAPPDATA 'GuitarMentorAI'))) {
    throw 'Launcher ожидает секреты в LOCALAPPDATA/GuitarMentorAI. Другой SettingsRoot не поддерживается.'
}
$manifest = Test-RecoverySet $RecoveryRoot
foreach ($path in $PilotRoot,$SourceRoot,$SettingsRoot) {
    if (Test-Path -LiteralPath $path) { throw "Путь уже существует, перезапись запрещена: $path" }
}
foreach ($tool in 'git.exe','java.exe','node.exe','npm.cmd') { $null = Get-Command $tool -ErrorAction Stop }
foreach ($path in $CaddyPath,$TailscalePath,(Join-Path $PostgresBin 'psql.exe')) {
    if (-not (Test-Path -LiteralPath $path)) { throw "Не найден инструмент: $path" }
}
$password = Read-RecoveryPassword
$secrets = $null
try {
    $secrets = Unprotect-RecoverySecrets (Read-PilotJson (Join-Path $RecoveryRoot 'secrets.enc.json')) $password
    $target = @{host='127.0.0.1';port=5432;name='guitar_mentor_pilot';user='guitar_mentor_pilot'}
    $summary = Get-RecoveryDatabaseSummary $PostgresBin $target $secrets.files['pilot-database-password.txt']
    if ($summary.flyway -ne $manifest.databases.pilot.flyway) { throw 'Сначала восстановите pilot БД через Restore-Database -Purpose Recover.' }
    foreach ($name in $manifest.databases.pilot.counts.Keys) {
        if (-not $summary.counts.ContainsKey($name) -or $summary.counts[$name] -ne $manifest.databases.pilot.counts[$name]) { throw 'Pilot БД не соответствует копии.' }
    }
    $git = (Get-Command git.exe).Source
    Restore-RecoveryWorkspace $RecoveryRoot $SourceRoot $manifest.sourceHead $git
    Copy-RecoveryTree (Join-Path $RecoveryRoot 'pilot') $PilotRoot
    foreach ($folder in 'data/attempts','data/scores','data/library') { $null = New-Item -ItemType Directory -Force -Path (Join-Path $PilotRoot $folder) }
    if (Test-Path -LiteralPath (Join-Path $RecoveryRoot 'dev-library')) {
        Copy-RecoveryTree (Join-Path $RecoveryRoot 'dev-library') (Join-Path $SourceRoot 'backend/.local-media/library')
    }
    Import-RecoverySecrets $secrets $SettingsRoot
    $pilotConfig = Read-PilotJson (Join-Path $PilotRoot 'pilot-tools.json')
    if (Test-PilotFilesEnabled $pilotConfig) { $pilotConfig.ffprobe = (Get-Command ffprobe.exe -ErrorAction Stop).Source }
    Write-PilotJson (Join-Path $PilotRoot 'pilot-tools.json') $pilotConfig
    $installer = Join-Path $PSScriptRoot '../pilot/Install-PilotTools.ps1'
    if (-not (Test-Path -LiteralPath $installer)) { $installer = Join-Path $PSScriptRoot '../pilot-tools/Install-PilotTools.ps1' }
    & $installer -Root $PilotRoot -Origin $pilotConfig.origin -PostgresBin $PostgresBin -CaddyPath $CaddyPath -TailscalePath $TailscalePath
    Write-Host 'Файлы, локальные изменения, Windows-секреты и ярлыки восстановлены. Публичный доступ не включался.'
    Write-Host "Исходный checkout оставлен на точном HEAD $($manifest.sourceHead), без автоматического merge/pull."
    Write-Host 'Дополнительные папки остаются в extra/<id>; верните их по карте путей из START-HERE.md.'
} finally { if ($secrets) { $secrets.Clear() };$password=$null }
