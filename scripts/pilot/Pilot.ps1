#Requires -Version 7.4
param(
    [ValidateSet('Start','Update','Rollback')][string]$Mode = 'Start',
    [string]$Root = 'D:\GuitarMentorPilot'
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Pilot.Common.ps1')
$lock = $null
$failed = $false
try {
    if (-not $IsWindows) { throw 'Этот скрипт предназначен для Windows и PowerShell 7.4+.' }
    $Root = (Resolve-Path -LiteralPath $Root).Path
    $lock = Enter-PilotLock $Root
    Assert-PilotReady $Root
    Assert-PilotStopped
    $config = Read-PilotJson (Join-Path $Root 'pilot-tools.json')
    $state = Read-PilotJson (Join-Path $Root 'release-state.json')
    switch ($Mode) {
        Start { Invoke-PilotRuntime $Root $state.current $config -Publish }
        Update {
            Write-Host 'Получение main, тесты и сборка. Рабочая папка разработки не используется.'
            $target = Build-PilotRelease $Root $config
            Switch-PilotRelease $Root $config $state $target
            Write-Host "Обновление завершено: $target. Запустите ярлык «Guitar Mentor — для друзей»."
        }
        Rollback {
            if (-not $state.previous) { throw 'Предыдущей версии пока нет.' }
            Switch-PilotRelease $Root $config $state $state.previous -Rollback
            Write-Host 'Предыдущая версия возвращена. Запустите ярлык «Guitar Mentor — для друзей».'
        }
    }
} catch {
    $failed = $true
    Write-Host "Ошибка: $($_.Exception.Message)" -ForegroundColor Red
    if (Test-Path -LiteralPath (Join-Path $Root 'pending-update.json')) {
        Write-Host 'Обновление прервано после подготовки БД. Сначала разберите восстановление по pending-update.json и инструкции.'
    } elseif ($Mode -ne 'Start') {
        Write-Host 'Операция не завершена. Текущую версию можно запустить обычным ярлыком.'
    }
} finally {
    if ($null -ne $lock) { $lock.Dispose() }
    if ($Mode -ne 'Start' -or $failed) { $null = Read-Host 'Нажмите Enter, чтобы закрыть окно' }
}
if ($failed) { exit 1 }
