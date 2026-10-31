#Requires -Version 7.4
param(
    [string]$Root = 'D:\GuitarMentorPilot',
    [string]$InitialRelease = '20261025-143144',
    [string]$Origin = 'https://guitar-mentor.tail3b8201.ts.net',
    [string]$PostgresBin = 'C:\Program Files\PostgreSQL\17\bin',
    [switch]$EnableFiles
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Pilot.Common.ps1')
if (-not $IsWindows) { throw 'Требуются Windows и PowerShell 7.4+.' }
$Root = (Resolve-Path -LiteralPath $Root).Path
if ($Root -match '["&|<>^%!\r\n]') { throw 'Путь установки содержит неподдерживаемые символы.' }
if ($Origin -notmatch '^https://[a-z0-9-]+\.[a-z0-9-]+\.ts\.net$') { throw 'Нужен точный HTTPS origin Tailscale без завершающего слеша.' }
$lock = Enter-PilotLock $Root
try {
    Assert-PilotReady $Root
    Assert-PilotStopped
    foreach ($path in @((Join-Path $Root 'application-pilot.yml'),
        'C:\Tools\Caddy\caddy.exe','C:\Program Files\Tailscale\tailscale.exe',
        (Join-Path $PostgresBin 'pg_dump.exe'),(Join-Path $PostgresBin 'pg_restore.exe'),
        (Join-Path $PostgresBin 'initdb.exe'),(Join-Path $PostgresBin 'pg_ctl.exe'),
        (Join-Path $env:LOCALAPPDATA 'GuitarMentorAI/local-auth-unified.json'),
        (Join-Path $env:LOCALAPPDATA 'GuitarMentorAI/pilot-database-password.txt'))) {
        if (-not (Test-Path -LiteralPath $path)) { throw "Не найден файл: $path" }
    }
    foreach ($tool in 'git.exe','java.exe','node.exe','npm.cmd') { $null = Get-Command $tool -ErrorAction Stop }
    $configPath = Join-Path $Root 'pilot-tools.json'
    $oldConfig = if (Test-Path -LiteralPath $configPath) { Read-PilotJson $configPath } else { @{} }
    $filesEnabled = $EnableFiles.IsPresent -or (Test-PilotFilesEnabled $oldConfig)
    $ffprobe = $null
    if ($filesEnabled) {
        $command = Get-Command ffprobe.exe -ErrorAction SilentlyContinue
        if ($command) { $ffprobe = $command.Source }
        elseif ($oldConfig.ContainsKey('ffprobe') -and (Test-Path -LiteralPath $oldConfig.ffprobe)) { $ffprobe = $oldConfig.ffprobe }
        else { throw 'Нужен ffprobe.exe для обработки аудио. Установите FFmpeg/ffprobe и повторите установку с -EnableFiles.' }
        Invoke-PilotTool $ffprobe @('-version') $Root
        $key = Get-PilotLibraryKey -Create
        $key = $null
        foreach ($name in 'attempts','scores','library') {
            $null = New-Item -ItemType Directory -Force -Path (Join-Path $Root "data/$name")
        }
    }
    $statePath = Join-Path $Root 'release-state.json'
    if (-not (Test-Path -LiteralPath $statePath)) {
        $release = Get-PilotRelease $Root $InitialRelease
        Write-PilotJson (Join-Path $release 'pilot-release.json') (New-PilotManifest $release $null)
        Write-PilotJson $statePath @{ current=$InitialRelease; previous=$null; lastBackup=$null }
    }
    $state = Read-PilotJson $statePath
    $null = Test-PilotRelease (Get-PilotRelease $Root $state.current)
    $installation = Join-Path $Root ("tools/" + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8))
    $null = New-Item -ItemType Directory -Path $installation -Force
    foreach ($name in 'Pilot.ps1','Pilot.Common.ps1') { Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $installation }
    Write-PilotJson $configPath @{
        origin=$Origin; postgresBin=$PostgresBin
        caddy='C:\Tools\Caddy\caddy.exe'; tailscale='C:\Program Files\Tailscale\tailscale.exe'
        filesEnabled=$filesEnabled; ffprobe=$ffprobe
    }
    $shell = New-Object -ComObject WScript.Shell
    $desktop = [Environment]::GetFolderPath('Desktop')
    $commandShell = $env:ComSpec
    if ([string]::IsNullOrWhiteSpace($commandShell) -or -not (Test-Path -LiteralPath $commandShell)) {
        throw 'Не найден системный cmd.exe для ярлыков. Проверьте переменную ComSpec.'
    }
    foreach ($item in @(
        @{file='Start-Pilot.ps1'; mode='Start'; title='Guitar Mentor — для друзей'},
        @{file='Update-Pilot.ps1'; mode='Update'; title='Обновить Guitar Mentor'},
        @{file='Rollback-Pilot.ps1'; mode='Rollback'; title='Вернуть предыдущую версию Guitar Mentor'})) {
        $entry = Join-Path $Root $item.file
        if (Test-Path -LiteralPath $entry) { Copy-Item -LiteralPath $entry -Destination "$entry.$([guid]::NewGuid().ToString('N')).bak" }
        $scriptPath = (Join-Path $installation 'Pilot.ps1').Replace("'","''")
        $rootLiteral = $Root.Replace("'","''")
        "& '$scriptPath' -Mode $($item.mode) -Root '$rootLiteral'" | Set-Content -LiteralPath $entry -Encoding utf8
        $launcher = Join-Path $Root ($item.file -replace '\\.ps1$','.cmd')
        $launcherText = New-PilotShortcutCommand -Mode $item.mode
        [IO.File]::WriteAllText($launcher, ($launcherText -replace "`r?`n", "`r`n") + "`r`n", [Text.Encoding]::ASCII)
        $shortcut = $shell.CreateShortcut((Join-Path $desktop ($item.title + '.lnk')))
        $shortcut.TargetPath = $commandShell
        $shortcut.Arguments = '/d /c ""' + $launcher + '""'
        $shortcut.WorkingDirectory = $Root
        $shortcut.Save()
    }
    Write-Host 'Готово: ярлыки запуска, обновления и возврата созданы. Текущая версия сохранена; сайт не запущен.'
    if ($filesEnabled) { Write-Host 'Загрузки и библиотека включены. Файлы: data/attempts, data/scores, data/library.' }
} finally { $lock.Dispose() }
,'.cmd')
        $launcherText = New-PilotShortcutCommand -Mode $item.mode
        [IO.File]::WriteAllText($launcher, ($launcherText -replace "`r?`n", "`r`n") + "`r`n", [Text.Encoding]::ASCII)
        $shortcut = $shell.CreateShortcut((Join-Path $desktop ($item.title + '.lnk')))
        $shortcut.TargetPath = $commandShell
        $shortcut.Arguments = '/d /c ""' + $launcher + '""'
        $shortcut.WorkingDirectory = $Root
        $shortcut.Save()
    }
    Write-Host 'Готово: ярлыки запуска, обновления и возврата созданы. Текущая версия сохранена; сайт не запущен.'
    if ($filesEnabled) { Write-Host 'Загрузки и библиотека включены. Файлы: data/attempts, data/scores, data/library.' }
} finally { $lock.Dispose() }
