#Requires -Version 7.4
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ConfigPath,
    [Parameter(Mandatory)][string]$WorkRoot,
    [string]$Snapshot = 'latest',
    [switch]$BackupFirst,
    [ValidateRange(30,300)][int]$ApplicationTimeoutSeconds = 120
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Recovery.Common.ps1')
if (-not $IsWindows) { throw 'Используйте Windows / PowerShell 7.4+.' }

$configPath = Expand-RecoveryPath $ConfigPath
$config = Read-PilotJson $configPath
Assert-RecoveryConfig $config
$workRoot = Expand-RecoveryPath $WorkRoot
foreach ($source in @($config.pilotRoot,$config.sourceRoot,$config.settingsRoot,$config.stageRoot)) {
    if ($workRoot -eq $source -or $workRoot.StartsWith($source.TrimEnd('\') + '\',[StringComparison]::OrdinalIgnoreCase)) {
        throw 'WorkRoot должен быть отдельным каталогом, вне рабочих данных и staging.'
    }
}
$progressPath = Join-Path $workRoot 'acceptance-progress.local.json'
$reportPath = Join-Path $workRoot 'recovery-acceptance-report.json'
$restoreRoot = Join-Path $workRoot 'restored-set'
$startedRun = [DateTime]::UtcNow
$masterPassword = $null
$secrets = $null
$adminPassword = $null
$stepName = 'Preflight'

function Save-DrillReport([hashtable]$State, [string]$Result, [string]$Path, [string]$FailureCode = '') {
    $now = [DateTime]::UtcNow
    $manifest = $null
    if (Test-Path -LiteralPath (Join-Path $State.destination 'gm-recovery-set.json')) {
        try { $manifest = Read-PilotJson (Join-Path $State.destination 'gm-recovery-set.json') } catch {}
    }
    $ageMinutes = $null
    $releaseId = $null
    if ($manifest -and $manifest.createdUtc) {
        $ageMinutes = [math]::Round(($now - (ConvertTo-RecoveryUtcDateTime $manifest.createdUtc)).TotalMinutes,1)
        try { $releaseId = (Read-PilotJson (Join-Path $State.destination 'pilot/release-state.json')).current } catch {}
    }
    $applicationSmoke = if ($State.ContainsKey('applicationSmoke')) { $State.applicationSmoke } else { $null }
    $restoredFileCount = if ($manifest) { $manifest.files.Count } else { $null }
    $restoredBytes = $null
    if ($manifest) { $restoredBytes = [long]0; foreach ($file in $manifest.files.Values) { $restoredBytes += [long]$file.bytes } }
    $dbSummary = @{}
    foreach ($kind in @('pilot','dev')) {
        if ($State.databases.ContainsKey($kind)) {
            $step = "Restore$($kind.Substring(0,1).ToUpperInvariant())Database"
            $dbSummary[$kind] = @{
                status = if (Test-RecoveryProgressStep $State $step) { 'PASS' } else { 'NOT_RUN' }
                tableCount = $State.databases[$kind].tableCount
                rowCount = $State.databases[$kind].rowCount
                flywayMigrationCount = $State.databases[$kind].flywayMigrationCount
            }
        }
    }
    $report = @{
        schemaVersion=1;result=$Result;createdUtc=$State.startedUtc;completedUtc=$now.ToString('o')
        durationSeconds=[math]::Round(($now - (ConvertTo-RecoveryUtcDateTime $State.attemptStartedUtc)).TotalSeconds,1)
        totalElapsedWallClockSeconds=[math]::Round(($now - (ConvertTo-RecoveryUtcDateTime $State.startedUtc)).TotalSeconds,1)
        sourceDataAgeMinutes=$ageMinutes;snapshotShort=if ($State.snapshotId) { $State.snapshotId.Substring(0,8) } else { $null }
        sourceHead=if ($manifest) { $manifest.sourceHead } else { $null };pilotReleaseId=$releaseId
        database=$dbSummary;libraryOriginalCount=if ($manifest) { @($manifest.databases.pilot.libraryReady).Count } else { $null }
        restoredFileCount=$restoredFileCount;restoredBytes=$restoredBytes;applicationSmoke=$applicationSmoke
        steps=$State.steps;failureCode=$FailureCode
        limitations=@('Только изолированная проверка; production/PILOT/DEV не изменялись.',
            'Измерено время данного прогона, не полное время восстановления чистого ноутбука.',
            'Проверка приложения локальная; Funnel, SMTP-доставка и внешние OAuth-провайдеры не проверялись.')
    }
    Write-RecoveryProgress $Path $report
}

function Test-RecoveryAcceptanceEnvironment([hashtable]$Config) {
    foreach ($directory in @($Config.pilotRoot,$Config.sourceRoot,$Config.settingsRoot)) {
        if (-not (Test-Path -LiteralPath $directory -PathType Container)) { throw 'Не найден один из обязательных каталогов PILOT, sourceRoot или settingsRoot.' }
    }
    foreach ($file in @('pilot-tools.json','release-state.json')) {
        if (-not (Test-Path -LiteralPath (Join-Path $Config.pilotRoot $file) -PathType Leaf)) { throw 'В каталоге PILOT отсутствует обязательный файл конфигурации/release state.' }
    }
    foreach ($file in @('local-auth-unified.json','pilot-database-password.txt')) {
        if (-not (Test-Path -LiteralPath (Join-Path $Config.settingsRoot $file) -PathType Leaf)) { throw 'В settingsRoot отсутствует обязательный переносимый файл секретов.' }
    }
    if (-not (Test-Path -LiteralPath $Config.rcloneConfig -PathType Leaf)) { throw 'Не найден rclone config. Подключите внешний аккаунт перед запуском.' }
    foreach ($tool in @('git.exe','java.exe','rclone.exe')) { $null=Get-Command $tool -ErrorAction Stop }
    foreach ($tool in @('psql.exe','pg_dump.exe','pg_restore.exe','createdb.exe')) {
        if (-not (Test-Path -LiteralPath (Join-Path $Config.postgresBin $tool) -PathType Leaf)) { throw "Не найдена утилита PostgreSQL $tool." }
    }
    if (Test-Path -LiteralPath $Config.resticPath -PathType Leaf) { }
    else { $null=Get-Command $Config.resticPath -ErrorAction Stop }
    foreach ($folder in $Config.extraFolders) {
        if (-not (Test-Path -LiteralPath (Expand-RecoveryPath $folder.path) -PathType Container)) { throw "Не найден дополнительный каталог extra/$($folder.id)." }
    }
    $pilotConfig=Read-PilotJson (Join-Path $Config.pilotRoot 'pilot-tools.json')
    if (-not (Test-Path -LiteralPath $pilotConfig.tailscale -PathType Leaf)) { throw 'Не найден Tailscale CLI из pilot-tools.json.' }
    $minimum=if ($Config.minimumFreeBytes) { [long]$Config.minimumFreeBytes } else { 1073741824 }
    $free=Assert-RecoveryStageCapacity $Config.stageRoot $minimum
    return @{stageFreeBytes=$free;minimumFreeBytes=$minimum}
}

function Get-DrillDbSummary([string]$Kind, [hashtable]$State, [hashtable]$Manifest,
    [string]$Bin, [string]$AdminUser, [string]$AdminPassword) {
    $entry = $State.databases[$Kind]
    $target = @{host='127.0.0.1';port=[int]$entry.port;name=$entry.name;user=$entry.user}
    $appPassword = [Net.NetworkCredential]::new('',(ConvertTo-SecureString $entry.passwordProtected)).Password
    $admin = @{name='postgres';user=$AdminUser;host='127.0.0.1';port=[int]$entry.port}
    $exists = (Invoke-RecoverySql $Bin $admin $AdminPassword "SELECT (EXISTS(SELECT 1 FROM pg_roles WHERE rolname='$($target.user)') OR EXISTS(SELECT 1 FROM pg_database WHERE datname='$($target.name)'))::int;").Trim()
    if ($exists -eq '0') {
        $actual = Restore-RecoveryDatabase $Bin (Join-Path $State.destination "databases/$Kind.dump") $Manifest.databases[$Kind] $target $appPassword $AdminUser $AdminPassword
    } else {
        $hasDb = (Invoke-RecoverySql $Bin $admin $AdminPassword "SELECT EXISTS(SELECT 1 FROM pg_database WHERE datname='$($target.name)')::int;").Trim()
        $hasRole = (Invoke-RecoverySql $Bin $admin $AdminPassword "SELECT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='$($target.user)')::int;").Trim()
        if ($hasDb -ne '1' -or $hasRole -ne '1') {
            if ($target.name -notmatch '^gm_dr_[a-f0-9]{16}$' -or $target.user -ne $target.name) {
                throw 'Частично созданная БД/роль не прошла защитную проверку; автоматическая очистка запрещена.'
            }
            $null = Invoke-RecoverySql $Bin $admin $AdminPassword "DROP DATABASE IF EXISTS $($target.name) WITH (FORCE);"
            $null = Invoke-RecoverySql $Bin $admin $AdminPassword "DROP ROLE IF EXISTS $($target.user);"
            $actual = Restore-RecoveryDatabase $Bin (Join-Path $State.destination "databases/$Kind.dump") $Manifest.databases[$Kind] $target $appPassword $AdminUser $AdminPassword
        } else {
            $actual = Get-RecoveryDatabaseSummary $Bin $target $appPassword
            $expected = $Manifest.databases[$Kind]
            $matches = ($actual.counts.Count -eq $expected.counts.Count -and $actual.flyway -eq $expected.flyway)
            foreach ($table in $expected.counts.Keys) {
                if (-not $actual.counts.ContainsKey($table) -or $actual.counts[$table] -ne $expected.counts[$table]) { $matches=$false }
            }
            if (-not $matches) {
                if ($target.name -notmatch '^gm_dr_[a-f0-9]{16}$' -or $target.user -ne $target.name) {
                    throw 'Изолированная БД не совпадает с источником; имя не прошло защитную проверку, автоматическая очистка запрещена.'
                }
                $null = Invoke-RecoverySql $Bin $admin $AdminPassword "DROP DATABASE IF EXISTS $($target.name) WITH (FORCE);"
                $null = Invoke-RecoverySql $Bin $admin $AdminPassword "DROP ROLE IF EXISTS $($target.user);"
                $actual = Restore-RecoveryDatabase $Bin (Join-Path $State.destination "databases/$Kind.dump") $expected $target $appPassword $AdminUser $AdminPassword
            }
        }
    }
    $rows = [long]0
    foreach ($value in $actual.counts.Values) { $rows += [long]$value }
    $State.databases[$Kind].tableCount = $actual.counts.Count
    $State.databases[$Kind].rowCount = $rows
    $State.databases[$Kind].flywayMigrationCount = @($actual.flyway | ConvertFrom-Json).Count
    return $target
}

function Invoke-DrillApplicationSmoke([hashtable]$State, [hashtable]$Config, [hashtable]$Secrets,
    [int]$TimeoutSeconds) {
    $entry = $State.databases.pilot
    $dbPassword = [Net.NetworkCredential]::new('',(ConvertTo-SecureString $entry.passwordProtected)).Password
    $releaseState = Read-PilotJson (Join-Path $State.destination 'pilot/release-state.json')
    $release = Join-Path $State.destination ("pilot/releases/" + $releaseState.current)
    $jar = Join-Path $release 'app.jar'
    $webRoot = Join-Path $release 'web'
    if (-not (Test-Path -LiteralPath $jar) -or -not (Test-Path -LiteralPath (Join-Path $webRoot 'index.html'))) {
        throw 'В восстановленной версии отсутствует JAR или web index.'
    }
    $index = Get-Content -LiteralPath (Join-Path $webRoot 'index.html') -Raw
    $asset = [regex]::Match($index,'<script[^>]+src="([^"]+)"').Groups[1].Value
    if ($asset -notmatch '^/assets/[^?#]+\.js$' -or -not (Test-Path -LiteralPath (Join-Path $webRoot $asset.TrimStart('/').Replace('/','\')))) {
        throw 'Не найден JavaScript bundle восстановленного интерфейса.'
    }
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
    $listener.Start();$port=([Net.IPEndPoint]$listener.LocalEndpoint).Port;$listener.Stop()
    $java=(Get-Command java.exe -ErrorAction Stop).Source
    $settings = $Secrets.settings
    $arguments = @('-jar',$jar,'--spring.profiles.active=secure,local-auth',
        "--spring.config.additional-location=file:$((Join-Path $State.destination 'pilot/application-pilot.yml').Replace('\','/'))",
        '--server.address=127.0.0.1',"--server.port=$port",
        "--spring.datasource.url=jdbc:postgresql://127.0.0.1:$($entry.port)/$($entry.name)",
        "--spring.datasource.username=$($entry.user)",'--guitar-mentor.security.enabled=true',
        '--guitar-mentor.local-auth.unified-local=false',"--guitar-mentor.local-auth.public-origin=$($Config.origin)",
        '--guitar-mentor.submissions.enabled=false','--guitar-mentor.library.enabled=false')
    $envValues=@{
        SPRING_DATASOURCE_PASSWORD=$dbPassword
        GUITAR_MENTOR_OIDC_CLIENT_ID=[string]$settings.clientId
        GUITAR_MENTOR_OIDC_CLIENT_SECRET=[string]$settings.clientSecret
        GUITAR_MENTOR_SMTP_HOST=[string]$settings.smtpHost
        GUITAR_MENTOR_SMTP_PORT=[string]$settings.smtpPort
        GUITAR_MENTOR_SMTP_USER=[string]$settings.smtpUser
        GUITAR_MENTOR_SMTP_PASSWORD=[string]$settings.smtpSecret
        GUITAR_MENTOR_MAIL_FROM=[string]$settings.mailFrom
        GUITAR_MENTOR_SMTP_AUTH='true';GUITAR_MENTOR_SMTP_STARTTLS='true'
        HTTP_PROXY='';HTTPS_PROXY='';ALL_PROXY=''
    }
    $info=New-PilotProcessInfo $java $arguments $release $envValues
    $info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    $info.StandardOutputEncoding=[Text.UTF8Encoding]::new($false)
    $info.StandardErrorEncoding=[Text.UTF8Encoding]::new($false)
    $process=[Diagnostics.Process]::new();$process.StartInfo=$info
    $outTask=$null;$errTask=$null;$client=$null;$started=$false
    $failureReason='';$lastHttpStatus=$null;$lastHttpError='';$ready=$false
    $exitCode=$null;$stdout='';$stderr=''
    try {
        if(-not $process.Start()){throw 'Не удалось запустить восстановленный JAR.'}
        $started=$true
        $outTask=$process.StandardOutput.ReadToEndAsync()
        $errTask=$process.StandardError.ReadToEndAsync()
        $client=[Net.Http.HttpClient]::new();$client.Timeout=[TimeSpan]::FromSeconds(3)
        $ready=$false
        for($i=0;$i -lt $TimeoutSeconds;$i++){
            if($process.HasExited){
                $exitCode=$process.ExitCode
                $failureReason="Java завершился до готовности (exit code $exitCode)."
                break
            }
            try {
                $response=$client.GetAsync("http://127.0.0.1:$port/api/v1/csrf").GetAwaiter().GetResult()
                try {
                    $lastHttpStatus=[int]$response.StatusCode
                    if($lastHttpStatus -eq 200){
                        $body=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
                        if($body.token){$ready=$true;break}
                        $lastHttpError='HTTP 200 вернул ответ без CSRF token.'
                    }
                } finally {$response.Dispose()}
            } catch {
                $lastHttpError=$_.Exception.GetType().Name
            }
            Start-Sleep -Seconds 1
        }
        if(-not $ready -and -not $failureReason){
            $failureReason="HTTP/CSRF не готов за $TimeoutSeconds сек."
            if($lastHttpStatus){$failureReason+=" Последний HTTP status: $lastHttpStatus."}
            if($lastHttpError){$failureReason+=" Последняя ошибка запроса: $lastHttpError."}
        }
    } catch {
        $exception=$_.Exception
        $failureReason="Ошибка запуска/проверки Java: $($exception.GetType().FullName): $($exception.Message)"
        if($exception -is [ComponentModel.Win32Exception]) {
            $failureReason+=" (Win32 error $($exception.NativeErrorCode); HRESULT $($exception.HResult))"
        } elseif($exception.InnerException) {
            $failureReason+=" Inner: $($exception.InnerException.GetType().FullName): $($exception.InnerException.Message)"
        }
        $failureReason=Protect-RecoveryDiagnostic $failureReason 3000
    } finally {
        if($client){$client.Dispose()}
        if($started -and $process -and -not $process.HasExited){
            try {$process.Kill($true);$process.WaitForExit(10000)} catch {}
        }
        if($started -and $process -and $process.HasExited -and $null -eq $exitCode){$exitCode=$process.ExitCode}
        if($outTask){try {$stdout=$outTask.GetAwaiter().GetResult()} catch {}}
        if($errTask){try {$stderr=$errTask.GetAwaiter().GetResult()} catch {}}
        $process.Dispose()
    }
    if(-not $ready){
        # Preserve useful startup diagnostics, but redact credential-bearing lines
        # before they reach the local acceptance report.
        $safeOutput = foreach($stream in @($stderr,$stdout)){
            @($stream -split '\r?\n' | Select-Object -Last 80)
        }
        $diagnostic=Protect-RecoveryDiagnostic (@($safeOutput) -join ' | ') 6000
        if(-not $diagnostic){$diagnostic='Java не вывел диагностических сообщений.'}
        $exitText=if($null -ne $exitCode){"exit code $exitCode"}else{'процесс остановлен по тайм-ауту'}
        throw "ApplicationSmoke diagnostic: $failureReason Java $exitText. Последний вывод (stderr/stdout): $diagnostic"
    }
    return @{status='PASS';http='PASS';csrf='PASS';webBundle='PASS';publicAccess='OFF';httpStatus=200}
}

if (-not (Test-Path -LiteralPath $progressPath)) {
    if (Test-Path -LiteralPath $workRoot) {
        $existing = @(Get-ChildItem -LiteralPath $workRoot -Force)
        if ($existing.Count -gt 0) { throw 'WorkRoot уже содержит файлы, но progress отсутствует. Выберите новый каталог.' }
        Remove-Item -LiteralPath $workRoot -Force
    }
    New-PrivateRecoveryDirectory $workRoot
    $state=@{schemaVersion=1;repository=$config.repository;snapshotId='';snapshotTime='';destination=$restoreRoot
        startedUtc=[DateTime]::UtcNow.ToString('o');attemptStartedUtc=[DateTime]::UtcNow.ToString('o');steps=@{};databases=@{}}
    Write-RecoveryProgress $progressPath $state
} else {
    $state=Read-PilotJson $progressPath
    if($state.schemaVersion -ne 1 -or $state.repository -ne $config.repository -or $state.destination -ne $restoreRoot) {
        throw 'Progress относится к другой конфигурации/каталогу. Не переиспользуйте его; проверьте исходный прогон.'
    }
    $state.attemptStartedUtc=[DateTime]::UtcNow.ToString('o')
    Write-RecoveryProgress $progressPath $state
}

if (-not $BackupFirst -and $state.steps.ContainsKey('Backup') -and -not (Test-RecoveryProgressStep $state 'Backup')) {
    throw 'Предыдущий backup не завершён. Повторите запуск с -BackupFirst и тем же WorkRoot.'
}
$stepName='EnvironmentPreflight'
try {
    Set-RecoveryProgressStep $state $stepName 'RUNNING' $progressPath
    $preflight=Test-RecoveryAcceptanceEnvironment $config
    Set-RecoveryProgressStep $state $stepName 'PASS' $progressPath
} catch {
    $failure=Get-RecoveryFailureHint $_.Exception.Message
    Set-RecoveryProgressStep $state $stepName 'FAIL' $progressPath $failure
    Save-DrillReport $state 'FAIL' $reportPath $stepName
    Write-Error "Проверка окружения не пройдена. Прогресс сохранён; исправьте указанное условие и повторите тот же запуск. $failure Отчёт: $reportPath"
    exit 1
}

if ($BackupFirst -and -not (Test-RecoveryProgressStep $state 'Backup')) {
    $stepName='Backup'
    try {
        Set-RecoveryProgressStep $state $stepName 'RUNNING' $progressPath
        $backupScript=Join-Path $PSScriptRoot 'Recovery.ps1'
        & $backupScript -Mode Backup -ConfigPath $configPath
        if (-not $?) { throw 'Команда согласованного backup завершилась с ошибкой.' }
        $masterPassword=Read-RecoveryPassword
        $snapshots=@(Invoke-RecoveryRestic $config $masterPassword @('snapshots','--json','--tag','gm-dr-v1') $PSScriptRoot | ConvertFrom-Json -AsHashtable)
        $selected=$snapshots | Sort-Object time -Descending | Select-Object -First 1
        if (-not $selected -or $selected.id.Length -ne 64 -or $selected.id -notmatch '^[a-f0-9]+$' -or
            (ConvertTo-RecoveryUtcDateTime $selected.time) -lt (ConvertTo-RecoveryUtcDateTime $state.startedUtc)) {
            throw 'После backup не найден новый snapshot с ожидаемым тегом и временем.'
        }
        $state.snapshotId=$selected.id
        $state.snapshotTime=$selected.time
        Write-RecoveryProgress $progressPath $state
        Set-RecoveryProgressStep $state $stepName 'PASS' $progressPath
    } catch {
        $failure=Get-RecoveryFailureHint $_.Exception.Message
        Set-RecoveryProgressStep $state $stepName 'FAIL' $progressPath $failure
        Save-DrillReport $state 'FAIL' $reportPath $stepName
        Write-Error "Backup не завершён. Прогресс сохранён; повторный запуск продолжит с backup. $failure Отчёт: $reportPath"
        exit 1
    }
}
if (-not $masterPassword) { $masterPassword=Read-RecoveryPassword }
if (-not $state.snapshotId) {
    $snapshots=@(Invoke-RecoveryRestic $config $masterPassword @('snapshots','--json','--tag','gm-dr-v1') $PSScriptRoot | ConvertFrom-Json -AsHashtable)
    if (-not $snapshots.Count) { throw 'В облачном repository нет snapshot с тегом gm-dr-v1.' }
    if ($Snapshot -eq 'latest') { $selected=$snapshots | Sort-Object time -Descending | Select-Object -First 1 }
    else { $selected=$snapshots | Where-Object { $_.id -eq $Snapshot } | Select-Object -First 1 }
    if (-not $selected -or $selected.id -notmatch '^[a-f0-9]{64}$') { throw 'Snapshot не найден. Укажите полный ID.' }
    $state.snapshotId=$selected.id
    $state.snapshotTime=$selected.time
    Write-RecoveryProgress $progressPath $state
}
$adminSecure=Read-Host 'Пароль локального PostgreSQL администратора (не мастер-пароль копии)' -AsSecureString
$adminPassword=[Net.NetworkCredential]::new('',$adminSecure).Password
try {
    $stepName='VerifyRepository'
    if (-not (Test-RecoveryProgressStep $state $stepName)) {
        Set-RecoveryProgressStep $state $stepName 'RUNNING' $progressPath
        $null=Invoke-RecoveryRestic $config $masterPassword @('check','--read-data') $PSScriptRoot
        Set-RecoveryProgressStep $state $stepName 'PASS' $progressPath
    }

    $stepName='RestoreSnapshot'
    if (-not (Test-RecoveryProgressStep $state $stepName)) {
        Set-RecoveryProgressStep $state $stepName 'RUNNING' $progressPath
        if (Test-Path -LiteralPath $restoreRoot) {
            $validExisting=$false
            try { $null=Test-RecoverySet $restoreRoot; $validExisting=$true } catch {
                if ($state.steps[$stepName].status -eq 'PASS') { throw 'Ранее подтверждённый восстановленный набор изменился; автоматическая перезапись запрещена.' }
                Remove-Item -LiteralPath $restoreRoot -Recurse -Force
            }
            if (-not $validExisting) { $null=New-Item -ItemType Directory -Path $workRoot -Force }
        }
        if (-not (Test-Path -LiteralPath $restoreRoot)) {
            $nodes=Invoke-RecoveryRestic $config $masterPassword @('ls','--json',$state.snapshotId) $PSScriptRoot
            $markers=@($nodes -split '\r?\n' | Where-Object {$_} | ForEach-Object {$_|ConvertFrom-Json -AsHashtable} |
                Where-Object {$_.ContainsKey('name') -and $_.name -eq 'gm-recovery-set.json'})
            if($markers.Count -ne 1){throw 'Не найден единственный манифест в выбранном snapshot.'}
            $snapshotRoot=$markers[0].path.Substring(0,$markers[0].path.LastIndexOf('/'))
            New-PrivateRecoveryDirectory $restoreRoot
            $null=Invoke-RecoveryRestic $config $masterPassword @('restore',($state.snapshotId+':'+$snapshotRoot),'--target',$restoreRoot,'--verify') $PSScriptRoot
        }
        Set-RecoveryProgressStep $state $stepName 'PASS' $progressPath
    }
    $stepName='ValidateFiles'
    if(-not (Test-RecoveryProgressStep $state $stepName)){
        Set-RecoveryProgressStep $state $stepName 'RUNNING' $progressPath
        $manifest=Test-RecoverySet $restoreRoot
        Set-RecoveryProgressStep $state $stepName 'PASS' $progressPath "files=$($manifest.files.Count)"
    } else {$manifest=Test-RecoverySet $restoreRoot}

    $secrets=Unprotect-RecoverySecrets (Read-PilotJson (Join-Path $restoreRoot 'secrets.enc.json')) $masterPassword
    foreach($kind in @('pilot','dev')){
        if($kind -eq 'dev' -and -not $manifest.databases.ContainsKey('dev')){continue}
        $stepName="Restore$($kind.Substring(0,1).ToUpperInvariant())Database"
        if(-not $state.databases.ContainsKey($kind)){
            $name='gm_dr_'+[guid]::NewGuid().ToString('N').Substring(0,16)
            $password=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
            $port=if($kind -eq 'pilot'){5432}else{[int]$config.devDatabase.port}
            $state.databases[$kind]=@{name=$name;user=$name;port=$port
                passwordProtected=(ConvertTo-SecureString $password -AsPlainText -Force | ConvertFrom-SecureString)
                tableCount=0;rowCount=0;flywayMigrationCount=0}
            Write-RecoveryProgress $progressPath $state
            $password=$null
        }
        if(-not (Test-RecoveryProgressStep $state $stepName)){
            Set-RecoveryProgressStep $state $stepName 'RUNNING' $progressPath
            $null=Get-DrillDbSummary $kind $state $manifest $config.postgresBin 'postgres' $adminPassword
            Set-RecoveryProgressStep $state $stepName 'PASS' $progressPath
        } else {$null=Get-DrillDbSummary $kind $state $manifest $config.postgresBin 'postgres' $adminPassword}
    }

    $stepName='ApplicationSmoke'
    if(-not (Test-RecoveryProgressStep $state $stepName)){
        Set-RecoveryProgressStep $state $stepName 'RUNNING' $progressPath
        $state.applicationSmoke=Invoke-DrillApplicationSmoke $state $config $secrets $ApplicationTimeoutSeconds
        Set-RecoveryProgressStep $state $stepName 'PASS' $progressPath
    }
    $stepName='PostStartDataCheck'
    if(-not (Test-RecoveryProgressStep $state $stepName)){
        Set-RecoveryProgressStep $state $stepName 'RUNNING' $progressPath
        foreach($kind in @('pilot','dev')){
            if($state.databases.ContainsKey($kind)){
                $null=Get-DrillDbSummary $kind $state $manifest $config.postgresBin 'postgres' $adminPassword
            }
        }
        Set-RecoveryProgressStep $state $stepName 'PASS' $progressPath
    }

    Save-DrillReport $state 'PASS' $reportPath
    Write-Host "Восстановление и локальный smoke пройдены. Обезличенный отчёт: $reportPath"
    Write-Host "Длительность прогона: $([math]::Round(([DateTime]::UtcNow-$startedRun).TotalSeconds,1)) сек."
} catch {
    $errorText=[string]$_.Exception.Message
    $diagnosticPrefix='ApplicationSmoke diagnostic:'
    if($stepName -eq 'ApplicationSmoke') {
        # Application startup errors can occur before the smoke function reaches
        # its own diagnostic wrapper. Preserve a sanitized, actionable message
        # in both progress and the final report instead of collapsing it to the
        # generic recovery hint.
        if($errorText.StartsWith($diagnosticPrefix,[StringComparison]::Ordinal)) {
            $safeFailure=$errorText.Substring($diagnosticPrefix.Length).Trim()
        } else {
            $safeFailure=$errorText
        }
        $safeFailure=Protect-RecoveryDiagnostic $safeFailure 6000
        $failure='ApplicationSmoke: '+$safeFailure
        if($state){$state.applicationSmoke=@{status='FAIL';diagnostic=$safeFailure}}
    } else {
        $failure=Get-RecoveryFailureHint $errorText
    }
    if($state){
        try {
            Set-RecoveryProgressStep $state $stepName 'FAIL' $progressPath $failure
            Save-DrillReport $state 'FAIL' $reportPath $stepName
        } catch {}
    }
    Write-Error "Приёмочная проверка остановлена на этапе $stepName. Прогресс сохранён; повторный запуск продолжит с этого этапа. $failure Отчёт: $reportPath"
    exit 1
} finally {
    if($secrets){$secrets.Clear()}
    $masterPassword=$null;$adminPassword=$null
}
