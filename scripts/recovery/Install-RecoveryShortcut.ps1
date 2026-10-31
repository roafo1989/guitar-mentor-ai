#Requires -Version 7.4
param([Parameter(Mandatory)][string]$ConfigPath)
. (Join-Path $PSScriptRoot 'Recovery.Common.ps1')
if (-not $IsWindows) { throw 'Windows required.' }
$config = Read-PilotJson $ConfigPath
Assert-RecoveryConfig $config
$scriptPath = Join-Path $PSScriptRoot 'Recovery.ps1'
$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut((Join-Path ([Environment]::GetFolderPath('Desktop')) 'Сохранить и запустить Guitar Mentor.lnk'))
$shortcut.TargetPath = [Environment]::ProcessPath
$shortcut.Arguments = '-NoProfile -NoExit -File "' + $scriptPath + '" -Mode Backup -ConfigPath "' + (Expand-RecoveryPath $ConfigPath) + '" -StartPilot'
$shortcut.WorkingDirectory = $PSScriptRoot
$shortcut.Save()
Write-Host 'Создан отдельный ярлык «Сохранить и запустить Guitar Mentor». Существующие ярлыки сохранены.'
