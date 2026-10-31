#Requires -Version 7.4
param(
    [Parameter(Mandatory)][string]$RecoveryRoot,
    [ValidateSet('Pilot','Dev')][string]$Kind = 'Pilot',
    [ValidateSet('Drill','Recover')][string]$Purpose = 'Drill',
    [string]$PostgresBin = 'C:\Program Files\PostgreSQL\17\bin',
    [string]$AdminUser = 'postgres',
    [ValidateRange(1,65535)][int]$Port = 5432
)
. (Join-Path $PSScriptRoot 'Recovery.Common.ps1')
if (-not $IsWindows) { throw 'Используйте Windows / PowerShell 7.4+.' }
$RecoveryRoot = Expand-RecoveryPath $RecoveryRoot
$manifest = Test-RecoverySet $RecoveryRoot
$key = $Kind.ToLowerInvariant()
if (-not $manifest.databases.ContainsKey($key)) { throw "В копии нет БД $Kind" }
$config = Read-PilotJson (Join-Path $RecoveryRoot 'recovery-config.json')
$password = Read-RecoveryPassword
$adminSecure = Read-Host 'Пароль локального PostgreSQL администратора (не мастер-пароль копии)' -AsSecureString
$adminPassword = [Net.NetworkCredential]::new('', $adminSecure).Password
$secrets = $null
try {
    $secrets = Unprotect-RecoverySecrets (Read-PilotJson (Join-Path $RecoveryRoot 'secrets.enc.json')) $password
    if ($Purpose -eq 'Drill') {
        $id = 'gm_dr_' + [guid]::NewGuid().ToString('N').Substring(0,16)
        $target = @{host='127.0.0.1';port=$Port;name=$id;user=$id}
        $appPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    } elseif ($key -eq 'pilot') {
        $target = @{host='127.0.0.1';port=$Port;name='guitar_mentor_pilot';user='guitar_mentor_pilot'}
        $appPassword = $secrets.files['pilot-database-password.txt']
    } else {
        $target = $config.devDatabase.Clone();$target.port=$Port
        $appPassword = $secrets.settings.databaseSecret
    }
    $null = Restore-RecoveryDatabase $PostgresBin (Join-Path $RecoveryRoot "databases/$key.dump") $manifest.databases[$key] $target $appPassword $AdminUser $adminPassword
    # Outside the immutable set: adding files inside it would invalidate its hashes.
    $report = "$RecoveryRoot.$key.$Purpose.$([guid]::NewGuid().ToString('N').Substring(0,8)).json"
    Write-PilotJson $report @{
        completedUtc=[DateTime]::UtcNow.ToString('o');purpose=$Purpose;kind=$Kind
        database=$target.name;user=$target.user;port=$Port
        dataRestore='PASS: pg_restore + table counts + Flyway + file hashes'
        applicationSmoke='NOT_RUN';publicAccess='OFF';cleanup='manual: only the named drill DB/role'
    }
    Write-Host "Восстановление БД проверено: $($target.name). Отчёт: $report"
    Write-Host 'Сайт не запущен. Проверка приложения выполняется отдельно по START-HERE.md.'
} finally {
    if ($secrets) { $secrets.Clear() }
    $password=$null;$adminPassword=$null;$appPassword=$null
}
