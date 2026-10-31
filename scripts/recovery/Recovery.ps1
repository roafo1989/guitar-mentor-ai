#Requires -Version 7.4
param(
    [Parameter(Mandatory)][ValidateSet('Inventory','Init','Backup','Status','Snapshots','Check','Restore')][string]$Mode,
    [Parameter(Mandatory)][string]$ConfigPath,
    [string]$Snapshot = 'latest',
    [string]$Destination,
    [switch]$StartPilot
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Recovery.Common.ps1')
if (-not $IsWindows) { throw 'Используйте PowerShell 7.4+ на Windows.' }
$config = Read-PilotJson $ConfigPath
Assert-RecoveryConfig $config
if ($StartPilot -and $Mode -ne 'Backup') { throw '-StartPilot допустим только после Backup.' }
$statusPath = Join-Path (Split-Path (Expand-RecoveryPath $ConfigPath)) 'recovery-status.local.json'
if ($Mode -eq 'Status') {
    if (-not (Test-Path -LiteralPath $statusPath)) { throw 'Успешных облачных копий на этом компьютере ещё нет.' }
    $status = Read-PilotJson $statusPath
    $status | ConvertTo-Json
    $age = [DateTime]::UtcNow - [DateTime]::Parse($status.lastSuccessUtc).ToUniversalTime()
    if ($age.TotalHours -gt 26) { throw 'Последняя подтверждённая копия старше 26 часов.' }
    exit 0
}
if ($Mode -eq 'Inventory') {
    Write-Host 'Инвентаризация (без чтения значений секретов). Ничего не отправлено в облако.'
    foreach ($key in 'pilotRoot','sourceRoot','settingsRoot','postgresBin','rcloneConfig','devLibraryPath') {
        $path = Expand-RecoveryPath $config[$key]
        Write-Host "$key : $path ; существует=$(Test-Path -LiteralPath $path)"
    }
    foreach ($folder in $config.extraFolders) {
        $path = Expand-RecoveryPath $folder.path
        Write-Host "extra/$($folder.id) : $path ; существует=$(Test-Path -LiteralPath $path)"
    }
    $state = Read-PilotJson (Join-Path $config.pilotRoot 'release-state.json')
    Write-Host "Текущая сборка: $($state.current); предыдущая: $($state.previous)"
    Write-Host "DEV БД: $($config.devDatabase.name); включена=$($config.devEnabled)"
    Write-Host 'Просмотрите список папок вручную: архив нот, документы/скрипты вне репозитория, .env и другие локальные настройки.'
    Write-Host 'Секреты в окружении Windows/других DPAPI-файлах перенесите в менеджер паролей; неизвестные форматы автоматически не расшифровываются.'
    exit 0
}
$password = Read-RecoveryPassword
$stage = $null; $lock = $null
$backupPhase = 'initialization'
try {
    switch ($Mode) {
        Init {
            $confirm = Read-Host 'Повторите мастер-пароль' -AsSecureString
            if ([Net.NetworkCredential]::new('', $confirm).Password -cne $password) { throw 'Пароли не совпадают.' }
            $null = Invoke-RecoveryRestic $config $password @('init') $PSScriptRoot
            Write-Host 'Облачное хранилище создано. Первая копия ещё НЕ сделана.'
        }
        Snapshots { Write-Host (Invoke-RecoveryRestic $config $password @('snapshots','--tag','gm-dr-v1') $PSScriptRoot) }
        Check {
            $null = Invoke-RecoveryRestic $config $password @('check','--read-data') $PSScriptRoot
            Write-Host 'Все зашифрованные данные в облаке прочитаны и проверены. Это ещё не репетиция запуска приложения.'
        }
        Backup {
            if (-not $config.inventoryReviewed) { throw 'Сначала Inventory и проверка extraFolders; затем inventoryReviewed=true в личном config.' }
            $backupPhase = 'cloud repository preflight'
            # Fail before upload if the cloud is inaccessible. No automatic init.
            $null = Invoke-RecoveryRestic $config $password @('cat','config') $PSScriptRoot
            $backupPhase = 'PILOT lock and release validation'
            $lock = Enter-PilotLock $config.pilotRoot
            Assert-PilotReady $config.pilotRoot
            Assert-PilotStopped
            if (@(Get-NetTCPConnection -State Listen | Where-Object { $_.LocalPort -in 8080,5173 }).Count) {
                throw 'Остановите DEV backend/Vite и SQL-клиенты перед согласованной копией.'
            }
            $backupPhase = 'PILOT configuration and Funnel validation'
            $pilotConfig = Read-PilotJson (Join-Path $config.pilotRoot 'pilot-tools.json')
            Get-PilotFunnelStatus $config.pilotRoot $pilotConfig
            $state = Read-PilotJson (Join-Path $config.pilotRoot 'release-state.json')
            $minimumFreeBytes = if ($config.minimumFreeBytes) { [long]$config.minimumFreeBytes } else { 1073741824 }
            $availableFreeBytes = Assert-RecoveryStageCapacity $config.stageRoot $minimumFreeBytes
            Write-Host ("Свободное место staging: {0:N2} GiB; защитный резерв: {1:N2} GiB." -f ($availableFreeBytes/1GB),($minimumFreeBytes/1GB))
            $backupPhase = 'staging directory and encrypted secrets'
            $stage = Join-Path $config.stageRoot ('set-' + [guid]::NewGuid().ToString('N'))
            New-PrivateRecoveryDirectory $stage
            $null = New-Item -ItemType Directory -Path (Join-Path $stage 'databases')
            $envelope = Export-RecoverySecrets $config.settingsRoot (Test-PilotFilesEnabled $pilotConfig) $password
            Write-PilotJson (Join-Path $stage 'secrets.enc.json') $envelope
            $secrets = Unprotect-RecoverySecrets $envelope $password
            $dbInfo = @{}
            Write-Host 'Создание согласованных копий PILOT/DEV. Приложения должны оставаться остановленными.'
            $backupPhase = 'PostgreSQL consistent dumps'
            try {
                $pilotDb = @{host='127.0.0.1';port=5432;name='guitar_mentor_pilot';user='guitar_mentor_pilot'}
                $dbInfo.pilot = Backup-RecoveryDatabase $config.postgresBin $pilotDb $secrets.files['pilot-database-password.txt'] (Join-Path $stage 'databases/pilot.dump')
                if ($config.devEnabled) {
                    $dbInfo.dev = Backup-RecoveryDatabase $config.postgresBin $config.devDatabase $secrets.settings.databaseSecret (Join-Path $stage 'databases/dev.dump')
                }
            } finally { $secrets.Clear() }
            $backupPhase = 'PILOT configuration and release files'
            foreach ($name in 'application-pilot.yml','pilot-tools.json','release-state.json') {
                Copy-RecoveryTree (Join-Path $config.pilotRoot $name) (Join-Path $stage "pilot/$name")
            }
            foreach ($id in @($state.current,$state.previous) | Where-Object { $_ } | Select-Object -Unique) {
                $release = Get-PilotRelease $config.pilotRoot $id
                $null = Test-PilotRelease $release
                Copy-RecoveryTree $release (Get-PilotRelease (Join-Path $stage 'pilot') $id)
            }
            $backupPhase = 'PILOT launchers and PowerShell tooling'
            # Preserve installed launchers/tool versions, including scripts created locally.
            foreach ($item in Get-ChildItem -LiteralPath $config.pilotRoot -Force) {
                if (($item.PSIsContainer -and $item.Name -eq 'tools') -or (-not $item.PSIsContainer -and $item.Extension -in '.ps1','.cmd','.bat','.caddyfile')) {
                    Copy-RecoveryTree $item.FullName (Join-Path $stage "pilot/$($item.Name)")
                }
            }
            $backupPhase = 'permanent library originals'
            $library = Join-Path $config.pilotRoot 'data/library'
            Write-Host 'Сохранение постоянной библиотеки, сборок, настроек и локальных изменений кода.'
            if (Test-Path -LiteralPath $library) { Copy-RecoveryTree $library (Join-Path $stage 'pilot/data/library') }
            elseif (Test-PilotFilesEnabled $pilotConfig) { throw 'Включена библиотека, но отсутствует её каталог.' }
            $devLibrary = Expand-RecoveryPath $config.devLibraryPath
            if ($config.devEnabled -and (Test-Path -LiteralPath $devLibrary)) {
                Copy-RecoveryTree $devLibrary (Join-Path $stage 'dev-library')
            }
            $backupPhase = 'workspace and local source changes'
            $excluded = @('.git','node_modules','target','dist','.vite','.local-media','data','media','uploads','.qa-local','qa-reports','.venv','__pycache__')
            Copy-RecoveryTree $config.sourceRoot (Join-Path $stage 'workspace') $excluded
            $git = (Get-Command git.exe -ErrorAction Stop).Source
            $null = Invoke-RecoveryNative $git @('bundle','create',(Join-Path $stage 'workspace.bundle'),'--all') $config.sourceRoot
            $null = Invoke-RecoveryNative $git @('bundle','verify',(Join-Path $stage 'workspace.bundle')) $config.sourceRoot
            $backupPhase = 'additional inventoried folders'
            foreach ($folder in $config.extraFolders) {
                Copy-RecoveryTree (Expand-RecoveryPath $folder.path) (Join-Path $stage "extra/$($folder.id)")
            }
            $backupPhase = 'recovery configuration and tooling'
            # Cloud OAuth config is sensitive, but needed for repeat backups after recovery.
            Copy-RecoveryTree $config.rcloneConfig (Join-Path $stage 'cloud/rclone.conf')
            Write-PilotJson (Join-Path $stage 'recovery-config.json') $config
            Copy-RecoveryTree $PSScriptRoot (Join-Path $stage 'recovery-tools') @('tests')
            Copy-RecoveryTree (Join-Path $PSScriptRoot '../pilot') (Join-Path $stage 'pilot-tools') @('tests')
            Copy-RecoveryTree (Join-Path $PSScriptRoot '../../docs/operations/disaster-recovery.md') (Join-Path $stage 'START-HERE.md')
            $backupPhase = 'local manifest and file integrity validation'
            $head = (Invoke-RecoveryNative $git @('rev-parse','HEAD') $config.sourceRoot).Trim()
            $branch = (Invoke-RecoveryNative $git @('rev-parse','--abbrev-ref','HEAD') $config.sourceRoot).Trim()
            $patch = Invoke-RecoveryNative $git @('diff','--binary','--no-ext-diff','--no-textconv','HEAD','--') $config.sourceRoot
            [IO.File]::WriteAllText((Join-Path $stage 'workspace.patch'),$patch,[Text.UTF8Encoding]::new($false))
            Write-PilotJson (Join-Path $stage 'gm-recovery-set.json') @{
                version=1;kind='guitar-mentor-disaster-recovery';createdUtc=[DateTime]::UtcNow.ToString('o')
                sourceHead=$head;sourceBranch=$branch;databases=$dbInfo;files=(Get-RecoveryFileIndex $stage)
                excluded='temporary attempts/scores, caches, old builds/backups/logs; see START-HERE.md'
            }
            $null = Test-RecoverySet $stage
            # Do not publish until every included path and every DPAPI credential passed.
            Write-Host 'Проверки локального комплекта пройдены. Отправка зашифрованной копии в облако; дождитесь snapshot ID.'
            $backupPhase = 'encrypted OneDrive upload (restic backup)'
            $output = Invoke-RecoveryRestic $config $password @('backup','--json','--tag','gm-dr-v1','.') $stage
            $summary = @($output -split '\r?\n' | Where-Object { $_ } | ForEach-Object { $_ | ConvertFrom-Json -AsHashtable } | Where-Object { $_.message_type -eq 'summary' })
            if ($summary.Count -ne 1 -or $summary[0].snapshot_id -notmatch '^[a-f0-9]{64}$') { throw 'Не получен точный ID успешного snapshot.' }
            $backupPhase = 'post-upload repository check'
            $null = Invoke-RecoveryRestic $config $password @('check') $stage
            Write-PilotJson $statusPath @{
                lastSuccessUtc=[DateTime]::UtcNow.ToString('o');snapshot=$summary[0].snapshot_id
                repository=$config.repository;restoreDrill='NOT_RUN; see separate drill report'
            }
            Write-Host "Копия отправлена в облако: $($summary[0].snapshot_id). Целостность структуры проверена."
        }
        Restore {
            if ([string]::IsNullOrWhiteSpace($Destination)) { throw 'Укажите новый -Destination для восстановления.' }
            $Destination = Expand-RecoveryPath $Destination
            if (Test-Path -LiteralPath $Destination) { throw 'Destination уже существует. Перезапись запрещена.' }
            $snapshots = @(Invoke-RecoveryRestic $config $password @('snapshots','--json','--tag','gm-dr-v1') $PSScriptRoot | ConvertFrom-Json -AsHashtable)
            if ($Snapshot -eq 'latest') { $selected = @($snapshots | Sort-Object time -Descending)[0] }
            else { $selected = @($snapshots | Where-Object { $_.id -eq $Snapshot }) | Select-Object -First 1 }
            if (-not $selected -or $selected.id -notmatch '^[a-f0-9]{64}$') { throw 'Snapshot не найден; используйте полный ID из Snapshots.' }
            $nodes = Invoke-RecoveryRestic $config $password @('ls','--json',$selected.id) $PSScriptRoot
            $markers = @($nodes -split '\r?\n' | Where-Object { $_ } | ForEach-Object { $_ | ConvertFrom-Json -AsHashtable } |
                Where-Object { $_.ContainsKey('name') -and $_.name -eq 'gm-recovery-set.json' })
            if ($markers.Count -ne 1) { throw 'Не найден единственный манифест комплекта.' }
            $snapshotRoot = $markers[0].path.Substring(0,$markers[0].path.LastIndexOf('/'))
            New-PrivateRecoveryDirectory $Destination
            Write-Host 'Загрузка зашифрованного комплекта из облака и проверка всех файлов. Рабочие БД не изменяются.'
            $null = Invoke-RecoveryRestic $config $password @('restore',($selected.id + ':' + $snapshotRoot),'--target',$Destination,'--verify') $PSScriptRoot
            $null = Test-RecoverySet $Destination
            Write-Host "Файлы восстановлены и проверены: $Destination. БД, Windows-секреты и сайт ещё не активированы."
        }
    }
} catch {
    if ($Mode -eq 'Backup') {
        $line = if ($_.InvocationInfo.ScriptLineNumber) { [int]$_.InvocationInfo.ScriptLineNumber } else { 0 }
        $type = $_.Exception.GetType().Name
        throw "Резервная копия остановлена на этапе '$backupPhase' (тип ошибки: $type; Recovery.ps1:$line). Секретные данные и исходный текст исключения скрыты."
    }
    throw
} finally {
    $password = $null
    if ($null -ne $lock) { $lock.Dispose() }
    if ($stage -and (Test-Path -LiteralPath $stage)) { Remove-Item -LiteralPath $stage -Recurse -Force }
}
if ($StartPilot) { & (Join-Path $config.pilotRoot 'Start-Pilot.ps1') }
