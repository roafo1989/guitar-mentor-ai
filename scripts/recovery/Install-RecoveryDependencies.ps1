#Requires -Version 7.4
param([switch]$Install)
$ErrorActionPreference='Stop'
if (-not $IsWindows) { throw 'Windows required.' }
$packages = [ordered]@{
    'Git.Git'='git.exe'; 'Microsoft.PowerShell'='pwsh.exe'
    'EclipseAdoptium.Temurin.21.JDK'='java.exe'; 'OpenJS.NodeJS.LTS'='node.exe'
    'restic.restic'='restic.exe'; 'Rclone.Rclone'='rclone.exe'
    'CaddyServer.Caddy'='caddy.exe'; 'Tailscale.Tailscale'='tailscale.exe'
    'Gyan.FFmpeg'='ffprobe.exe'
}
foreach ($id in $packages.Keys) {
    if ($Install -and -not (Get-Command $packages[$id] -ErrorAction SilentlyContinue)) {
        & winget.exe install --exact --id $id --source winget --accept-source-agreements --accept-package-agreements
        if ($LASTEXITCODE -ne 0) { throw "Не удалось установить $id. Установите по официальной инструкции и повторите." }
    }
    Write-Host "$id : $([bool](Get-Command $packages[$id] -ErrorAction SilentlyContinue))"
}
Write-Host 'PostgreSQL 17 установите отдельно официальным установщиком, задайте новый admin-пароль в менеджере паролей.'
Write-Host 'После установки откройте новое окно PowerShell. Проверьте Java 21, PostgreSQL 17 и Node >=22.12; Maven скачает wrapper проекта.'
Write-Host 'Вход в облако и Tailscale выполняется заново; сайт этот скрипт не запускает.'
