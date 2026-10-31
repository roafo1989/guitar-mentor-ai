#Requires -Version 7.4
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$pilotCommon = Join-Path $PSScriptRoot '../pilot/Pilot.Common.ps1'
if (-not (Test-Path -LiteralPath $pilotCommon)) { $pilotCommon = Join-Path $PSScriptRoot '../pilot-tools/Pilot.Common.ps1' }
. $pilotCommon

function Expand-RecoveryPath([string]$Path) {
    [IO.Path]::GetFullPath([Environment]::ExpandEnvironmentVariables($Path))
}

function New-PrivateRecoveryDirectory([string]$Path) {
    if (Test-Path -LiteralPath $Path) { throw "Каталог уже существует: $Path. Выберите новый пустой путь." }
    $null = New-Item -ItemType Directory -Path $Path
    if ($IsWindows) {
        $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
        $acl = [Security.AccessControl.DirectorySecurity]::new()
        $acl.SetOwner($sid)
        $acl.SetAccessRuleProtection($true,$false)
        $rule = [Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl',
            'ContainerInherit,ObjectInherit','None','Allow')
        $acl.AddAccessRule($rule)
        Set-Acl -LiteralPath $Path -AclObject $acl
    } else { & chmod 700 $Path; if ($LASTEXITCODE) { throw 'chmod failed' } }
}

function Assert-RecoveryTree([string]$Path) {
    $item = Get-Item -LiteralPath $Path -Force
    if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Ссылка вместо данных: $Path" }
    if ($item.PSIsContainer) {
        foreach ($child in Get-ChildItem -LiteralPath $Path -Force) { Assert-RecoveryTree $child.FullName }
    }
}

function Copy-RecoveryTree([string]$Source, [string]$Destination, [string[]]$ExcludeNames = @()) {
    $item = Get-Item -LiteralPath $Source -Force
    if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Ссылка вместо данных: $Source" }
    if ($item.PSIsContainer) {
        $null = New-Item -ItemType Directory -Force -Path $Destination
        foreach ($child in Get-ChildItem -LiteralPath $Source -Force) {
            if ($child.Name -notin $ExcludeNames) {
                Copy-RecoveryTree $child.FullName (Join-Path $Destination $child.Name) $ExcludeNames
            }
        }
    } else {
        $null = New-Item -ItemType Directory -Force -Path (Split-Path $Destination)
        Copy-Item -LiteralPath $Source -Destination $Destination
    }
}

function ConvertTo-RecoveryUtcDateTime([object]$Value) {
    if ($null -eq $Value) { throw 'Не задано время для recovery.' }
    if ($Value -is [DateTimeOffset]) { return $Value.UtcDateTime }
    if ($Value -is [DateTime]) { return $Value.ToUniversalTime() }
    $parsed = [DateTimeOffset]::Parse(
        [string]$Value,
        [Globalization.CultureInfo]::InvariantCulture,
        [Globalization.DateTimeStyles]::AssumeUniversal
    )
    return $parsed.UtcDateTime
}

function Get-RecoveryFailureHint([string]$ErrorText) {
    # ApplicationSmoke emits a sanitized, bounded Java startup/HTTP diagnostic.
    # Preserve it through the outer acceptance workflow instead of replacing it
    # with the generic fallback message.
    if ($ErrorText -match '(?s)ApplicationSmoke diagnostic:\s*(.*)    if ($ErrorText -match 'Корневой каталог комплекта является ссылкой') {
        return 'Корневой каталог подготовленного комплекта является ссылкой. Облачная загрузка не выполнялась.'
    }
    if ($ErrorText -match 'В комплекте обнаружена ссылка \(reparse point\): ([^\r\n]+)') {
        return "В подготовленный комплект попала ссылка вместо самостоятельных данных: $($Matches[1]). Облачная загрузка не выполнялась."
    }
    if ($ErrorText -match 'Не удалось прочитать каталог комплекта: ([^\r\n]+)') {
        return "Не удалось прочитать каталог подготовленного комплекта: $($Matches[1]). Проверьте права доступа и занятость файла. Облачная загрузка не выполнялась."
    }
    if ($ErrorText -match 'Комплект содержит недопустимую ссылку или недоступный каталог') {
        return 'Локальный комплект содержит ссылку или недоступный каталог. Требуется проверить состав staging; облачная загрузка не выполнялась.'
    }
    if ($ErrorText -match 'Не удалось прочитать/посчитать SHA-256 файла комплекта: ([^\r\n]+)') {
        return "Не удалось прочитать один из подготовленных файлов: $($Matches[1]). Облачная загрузка не выполнялась."
    }
    if ($ErrorText -match 'Не совпала контрольная сумма: ([^\r\n]+)') {
        return "Контрольная сумма не совпала у файла комплекта: $($Matches[1]). Облачная загрузка не выполнялась."
    }
    if ($ErrorText -match 'Оригинал библиотеки не соответствует SHA/размеру из БД') {
        return 'Оригинал постоянной библиотеки не совпадает с SHA-256 или размером, записанным в PostgreSQL. Облачная загрузка не выполнялась.'
    }
    if ($ErrorText -match 'Отсутствует оригинал READY-материала') {
        return 'В PostgreSQL есть READY-материал, но его оригинал отсутствует в постоянной библиотеке. Облачная загрузка не выполнялась.'
    }
    if ($ErrorText -match 'Набор файлов копии не совпадает с манифестом') {
        return 'Количество файлов подготовленного комплекта не совпало с манифестом. Облачная загрузка не выполнялась.'
    }
    if ($ErrorText -match 'Неполная копия: нет ([^\r\n]+)') {
        return "В комплекте отсутствует обязательный файл: $($Matches[1]). Облачная загрузка не выполнялась."
    }
    # Preserve the complete deliberately sanitized diagnostic emitted by Recovery.ps1.
    # The outer acceptance workflow classifies the message a second time; do not
    # discard the safe detail suffix when the underlying error is not recognized.
    if ($ErrorText -match "(?s)Резервная копия остановлена на этапе '([^']+)' \(тип ошибки: ([^;]+); Recovery\.ps1:(\d+)\)\.\s*(.*?)\s*(?:Секретные данные и исходный текст исключения скрыты\.)?$") {
        $detail = [string]$Matches[4]
        $prefix = "Резервная копия остановлена на этапе '$($Matches[1])' (тип ошибки: $($Matches[2]); Recovery.ps1:$($Matches[3]))."
        if ([string]::IsNullOrWhiteSpace($detail)) { return $prefix }
        return "$prefix $detail"
    }
    # Invoke-RecoveryNative already converts provider stderr into one of these
    # safe actionable messages. Preserve that classification when outer
    # workflow catches classify the exception again (idempotent diagnostics).
    if ($ErrorText -match 'Недостаточно места или превышена квота хранилища') {
        return 'Недостаточно места или превышена квота хранилища. Освободите место/увеличьте квоту и повторите только неуспешный шаг.'
    }
    if ($ErrorText -match 'Облако или локальный ресурс отказал в доступе') {
        return 'Облако или локальный ресурс отказал в доступе. Проверьте вход, MFA, права remote и доступ к файлам.'
    }
    if ($ErrorText -match 'Сетевой сбой или тайм-аут') {
        return 'Сетевой сбой или тайм-аут. Проверьте соединение и повторите этот шаг; существующий snapshot не удаляется.'
    }
    if ($ErrorText -match 'Обнаружена ошибка целостности') {
        return 'Обнаружена ошибка целостности. Не используйте эту копию для восстановления; сохраните диагностический отчёт.'
    }
    # Provider-specific diagnostics for the first real cloud preflight. Only
    # classify known-safe signatures; never echo raw rclone/restic stderr because
    # provider errors can contain account identifiers, remote paths or tokens.
    if ($ErrorText -match '(?i)wrong password or no key found|no key found for repository') {
        return 'Мастер-пароль restic не подходит к этому облачному хранилищу. Не выполняйте Init: проверьте сохранённый пароль в менеджере паролей.'
    }
    if ($ErrorText -match '(?i)didn.t find section in config file|section .* not found|config file .* not found|failed to create file system.*config') {
        return 'rclone не нашёл remote в указанном rclone.conf. Проверьте имя remote gm-backup и путь файла конфигурации; повторная авторизация пока не нужна.'
    }
    if ($ErrorText -match '(?i)invalid_grant|AADSTS[0-9]+|token.*(expired|refresh)|failed to.*(refresh|renew).*token|authorization.*expired|reauthori[sz]') {
        return 'Авторизация OneDrive через rclone истекла или отклонена. Нужно повторно авторизовать существующий remote gm-backup; не создавайте новый remote и не запускайте Init.'
    }
    if ($ErrorText -match '(?i)driveNotFound|itemNotFound|couldn.t find.*drive|no drive found|drive.*not found') {
        return 'OneDrive не разрешил доступ к выбранному диску/папке. Проверьте, что remote gm-backup указывает на личный OneDrive нужного аккаунта.'
    }
    if ($ErrorText -match '(?i)HTTP 429|status code 429|too many requests|throttl') {
        return 'OneDrive временно ограничил частоту запросов. Подождите 10–15 минут и повторите только проверку облачного подключения.'
    }
    if ($ErrorText -match '(?i)HTTP 507|status code 507|insufficientStorage|quota exceeded|quota.*full') {
        return 'В OneDrive закончилась доступная квота. Освободите место или увеличьте объём; существующие snapshot не удаляйте.'
    }
    if ($ErrorText -match '(?i)certificate verify failed|x509:|TLS handshake|certificate.*(invalid|expired)') {
        return 'Не удалось установить защищённое TLS-соединение с облаком. Проверьте дату/время Windows, системные сертификаты и сетевой фильтр.'
    }
    if ($ErrorText -match '(?i)repository does not exist|config file does not exist|unable to open config file|Is there a repository at') {
        return 'Restic не нашёл инициализированное хранилище по заданному адресу. Не запускайте Init автоматически: сначала нужно сверить remote и путь repository.'
    }
    if ($ErrorText -match '(?i)not enough space|no space left|disk full|quota exceeded|there is insufficient space|недостаточно.*мест|квот.*превыш') {
        return 'Недостаточно места или превышена квота хранилища. Освободите место/увеличьте квоту и повторите только неуспешный шаг.'
    }
    if ($ErrorText -match '(?i)unauthorized|forbidden|invalid_grant|authentication failed|access denied|permission denied|401|403') {
        return 'Облако или локальный ресурс отказал в доступе. Проверьте вход, MFA, права remote и доступ к файлам.'
    }
    if ($ErrorText -match '(?i)timeout|timed out|connection reset|network is unreachable|temporary failure|no route to host') {
        return 'Сетевой сбой или тайм-аут. Проверьте соединение и повторите этот шаг; существующий snapshot не удаляется.'
    }
    if ($ErrorText -match '(?i)checksum|hash mismatch|integrity|corrupt|unexpected end|invalid data') {
        return 'Обнаружена ошибка целостности. Не используйте эту копию для восстановления; сохраните диагностический отчёт.'
    }
    return 'Операция не подтверждена. Проверьте доступ, место и настройки; подробный вывод с секретами намеренно скрыт.'
}

function Invoke-RecoveryNative([string]$File, [string[]]$Arguments, [string]$Directory,
    [hashtable]$Environment = @{}, [AllowNull()][string]$InputText = $null) {
    $info = New-PilotProcessInfo $File $Arguments $Directory $Environment
    # Do not inherit libpq service files/options, database overrides or a restic
    # password command that could unexpectedly override the explicit connection.
    foreach ($name in @($info.Environment.Keys)) {
        if ($name -match '^(PG|RESTIC_|RCLONE_|GIT_)') { $null = $info.Environment.Remove($name) }
    }
    foreach ($name in $Environment.Keys) { $info.Environment[$name] = [string]$Environment[$name] }
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.RedirectStandardInput = $true
    $info.StandardInputEncoding = [Text.UTF8Encoding]::new($false)
    $info.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
    $info.StandardErrorEncoding = [Text.UTF8Encoding]::new($false)
    $process = [Diagnostics.Process]::Start($info)
    try {
        $outTask = $process.StandardOutput.ReadToEndAsync()
        $errTask = $process.StandardError.ReadToEndAsync()
        if ($null -ne $InputText) { $process.StandardInput.Write($InputText) }
        $process.StandardInput.Close()
        $process.WaitForExit()
        if (-not $outTask.Wait(10000) -or -not $errTask.Wait(10000)) {
            throw 'Команда завершилась, но оставила открытые каналы вывода. Для запуска фоновых служб используйте отдельный launcher.'
        }
        $output = $outTask.GetAwaiter().GetResult()
        $errorText = $errTask.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) {
            # Classify the failure without exposing raw provider/SQL output.
            $hint = Get-RecoveryFailureHint $errorText
            throw "$([IO.Path]::GetFileName($File)): код $($process.ExitCode). $hint"
        }
        return $output
    } finally {
        if (-not $process.HasExited) { $process.Kill($true);$process.WaitForExit() }
        $process.Dispose()
    }
}

function Assert-RecoveryConfig([hashtable]$Config) {
    if ($Config.schemaVersion -ne 1) { throw 'Неизвестная версия recovery config.' }
    # This workflow is deliberately off-host. A second partition/synced folder
    # is not evidence that data has reached a remote service.
    if ($Config.repository -notmatch '^rclone:[a-zA-Z0-9_-]+:.+' -or $Config.repository -match '[\r\n]') {
        throw 'Нужен прямой облачный repository rclone:remote:folder, не папка этого SSD.'
    }
    foreach ($key in 'pilotRoot','sourceRoot','settingsRoot','postgresBin','stageRoot','rcloneConfig') {
        if ([string]::IsNullOrWhiteSpace($Config[$key])) { throw "Не заполнено $key" }
        $Config[$key] = Expand-RecoveryPath $Config[$key]
    }
    foreach ($source in @($Config.pilotRoot,$Config.sourceRoot,$Config.settingsRoot) + @($Config.extraFolders | ForEach-Object { Expand-RecoveryPath $_.path })) {
        $prefix = $source.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
        if ($Config.stageRoot.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase) -or $Config.stageRoot -eq $source) {
            throw 'Временный каталог не должен находиться внутри копируемых данных.'
        }
    }
    $ids = @()
    foreach ($folder in $Config.extraFolders) {
        if ($folder.id -notmatch '^[a-z][a-z0-9_-]{0,39}$' -or $folder.id -in $ids) { throw 'Нужны уникальные id дополнительных каталогов.' }
        $ids += $folder.id
    }
    if ($Config.devEnabled -and $Config.devDatabase.name -eq 'guitar_mentor_pilot') { throw 'DEV и PILOT должны иметь отдельные БД.' }
    if ($Config.ContainsKey('minimumFreeBytes') -and $null -ne $Config.minimumFreeBytes -and ([long]$Config.minimumFreeBytes -lt 268435456 -or [long]$Config.minimumFreeBytes -gt 1099511627776)) {
        throw 'minimumFreeBytes должен быть от 256 MiB до 1 TiB.'
    }
}

function Read-RecoveryPassword {
    $secure = Read-Host 'Мастер-пароль резервной копии (из менеджера паролей)' -AsSecureString
    if ($secure.Length -lt 16) { throw 'Используйте отдельный мастер-пароль длиной не менее 16 символов.' }
    [Net.NetworkCredential]::new('', $secure).Password
}

function Protect-RecoverySecrets([string]$Plaintext, [string]$Password) {
    $salt = [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
    $nonce = [Security.Cryptography.RandomNumberGenerator]::GetBytes(12)
    $key = [Security.Cryptography.Rfc2898DeriveBytes]::Pbkdf2($Password,$salt,600000,
        [Security.Cryptography.HashAlgorithmName]::SHA256,32)
    $bytes = [Text.Encoding]::UTF8.GetBytes($Plaintext)
    $cipher = [byte[]]::new($bytes.Length); $tag = [byte[]]::new(16)
    $aes = [Security.Cryptography.AesGcm]::new($key,16)
    try {
        $aes.Encrypt($nonce,$bytes,$cipher,$tag)
        @{version=1; rounds=600000; salt=[Convert]::ToBase64String($salt)
            nonce=[Convert]::ToBase64String($nonce); tag=[Convert]::ToBase64String($tag)
            ciphertext=[Convert]::ToBase64String($cipher)}
    } finally {
        $aes.Dispose(); [Array]::Clear($key); [Array]::Clear($bytes)
    }
}

function Unprotect-RecoverySecrets([hashtable]$Envelope, [string]$Password) {
    if ($Envelope.version -ne 1 -or $Envelope.rounds -ne 600000) { throw 'Неизвестный формат переносимых секретов.' }
    $key = [Security.Cryptography.Rfc2898DeriveBytes]::Pbkdf2($Password,
        [Convert]::FromBase64String($Envelope.salt),600000,[Security.Cryptography.HashAlgorithmName]::SHA256,32)
    $cipher = [Convert]::FromBase64String($Envelope.ciphertext)
    $plain = [byte[]]::new($cipher.Length)
    $aes = [Security.Cryptography.AesGcm]::new($key,16)
    try {
        $aes.Decrypt([Convert]::FromBase64String($Envelope.nonce),$cipher,
            [Convert]::FromBase64String($Envelope.tag),$plain)
        [Text.Encoding]::UTF8.GetString($plain) | ConvertFrom-Json -AsHashtable
    } finally { $aes.Dispose(); [Array]::Clear($key); [Array]::Clear($plain) }
}

function Export-RecoverySecrets([string]$Directory, [bool]$FilesEnabled, [string]$Password) {
    if (-not $IsWindows) { throw 'Экспорт DPAPI выполняется текущим пользователем на исходном Windows.' }
    $settings = Read-PilotJson (Join-Path $Directory 'local-auth-unified.json')
    $fields = @('clientSecret','smtpSecret','databaseSecret')
    if ($settings.ContainsKey('libraryCursorSecret')) { $fields += 'libraryCursorSecret' }
    foreach ($name in $fields) { $settings[$name] = Unlock-PilotSecret $settings[$name] }
    $files = @{'pilot-database-password.txt' = Unlock-PilotSecret (Get-Content -LiteralPath (Join-Path $Directory 'pilot-database-password.txt') -Raw)}
    if ($FilesEnabled -or (Test-Path -LiteralPath (Join-Path $Directory 'pilot-library-cursor.txt'))) {
        $files['pilot-library-cursor.txt'] = Unlock-PilotSecret (Get-Content -LiteralPath (Join-Path $Directory 'pilot-library-cursor.txt') -Raw)
    }
    try { Protect-RecoverySecrets (@{settings=$settings; secretFields=$fields; files=$files} | ConvertTo-Json -Depth 12) $Password }
    finally { $settings.Clear(); $files.Clear() }
}

function Import-RecoverySecrets([hashtable]$Secrets, [string]$Directory) {
    if (-not $IsWindows) { throw 'Повторное шифрование DPAPI выполняется на новом Windows.' }
    New-PrivateRecoveryDirectory $Directory
    $settings = $Secrets.settings.Clone()
    foreach ($name in $Secrets.secretFields) {
        if ($name -notin @('clientSecret','smtpSecret','databaseSecret','libraryCursorSecret')) { throw 'Неизвестное поле секрета.' }
        $settings[$name] = ConvertTo-SecureString $settings[$name] -AsPlainText -Force | ConvertFrom-SecureString
    }
    Write-PilotJson (Join-Path $Directory 'local-auth-unified.json') $settings
    foreach ($name in $Secrets.files.Keys) {
        if ($name -notin @('pilot-database-password.txt','pilot-library-cursor.txt')) { throw 'Неизвестный файл секрета.' }
        ConvertTo-SecureString $Secrets.files[$name] -AsPlainText -Force | ConvertFrom-SecureString |
            Set-Content -LiteralPath (Join-Path $Directory $name) -Encoding utf8
    }
}

function Invoke-RecoveryRestic([hashtable]$Config, [string]$Password, [string[]]$Arguments, [string]$Directory) {
    if (-not (Test-Path -LiteralPath $Config.rcloneConfig)) { throw 'Подключите облако через rclone config; файл конфигурации не найден.' }
    Invoke-RecoveryNative $Config.resticPath (@('--repo',$Config.repository,'--no-cache') + $Arguments) $Directory @{
        RESTIC_PASSWORD=$Password; RCLONE_CONFIG=$Config.rcloneConfig
    }
}

function Get-RecoveryFileIndex([string]$Root) {
    $rootItem = Get-Item -LiteralPath $Root -Force -ErrorAction Stop
    if ($rootItem.Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw 'Корневой каталог комплекта является ссылкой (reparse point).'
    }
    $index = @{}
    $pending = [Collections.Generic.Stack[IO.FileSystemInfo]]::new()
    foreach ($item in Get-ChildItem -LiteralPath $Root -Force -ErrorAction Stop) { $pending.Push($item) }
    while ($pending.Count -gt 0) {
        $item = $pending.Pop()
        $relative = [IO.Path]::GetRelativePath($Root,$item.FullName).Replace('\','/')
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw "В комплекте обнаружена ссылка (reparse point): $relative"
        }
        if ($item.PSIsContainer) {
            try {
                foreach ($child in Get-ChildItem -LiteralPath $item.FullName -Force -ErrorAction Stop) { $pending.Push($child) }
            } catch {
                throw "Не удалось прочитать каталог комплекта: $relative"
            }
            continue
        }
        if ($relative -eq 'gm-recovery-set.json') { continue }
        try {
            $hash = (Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256 -ErrorAction Stop).Hash
            $length = (Get-Item -LiteralPath $item.FullName -Force -ErrorAction Stop).Length
            $index[$relative] = @{bytes=$length; sha256=$hash}
        } catch {
            throw "Не удалось прочитать/посчитать SHA-256 файла комплекта: $relative"
        }
    }
    return $index
}

function Test-RecoverySet([string]$Root) {
    $manifest = Read-PilotJson (Join-Path $Root 'gm-recovery-set.json')
    if ($manifest.version -ne 1 -or $manifest.kind -ne 'guitar-mentor-disaster-recovery') { throw 'Это не комплект восстановления Guitar Mentor.' }
    if ($manifest.sourceHead -notmatch '^[a-f0-9]{40}$') { throw 'Некорректный HEAD исходников.' }
    $actual = Get-RecoveryFileIndex $Root
    if ($actual.Count -ne $manifest.files.Count) { throw 'Набор файлов копии не совпадает с манифестом.' }
    foreach ($path in $manifest.files.Keys) {
        if (-not $actual.ContainsKey($path) -or $actual[$path].sha256 -ne $manifest.files[$path].sha256 -or $actual[$path].bytes -ne $manifest.files[$path].bytes) {
            throw "Не совпала контрольная сумма: $path"
        }
    }
    foreach ($required in 'databases/pilot.dump','secrets.enc.json','pilot/release-state.json','pilot/pilot-tools.json','pilot/application-pilot.yml','workspace.bundle','workspace.patch','recovery-config.json') {
        if (-not $actual.ContainsKey($required)) { throw "Неполная копия: нет $required" }
    }
    $state = Read-PilotJson (Join-Path $Root 'pilot/release-state.json')
    foreach ($id in @($state.current,$state.previous) | Where-Object { $_ } | Select-Object -Unique) {
        $null = Test-PilotRelease (Get-PilotRelease (Join-Path $Root 'pilot') $id)
    }
    Test-RecoveryLibrary (Join-Path $Root 'pilot/data/library') $manifest.databases.pilot.libraryReady
    if ($manifest.databases.ContainsKey('dev')) { Test-RecoveryLibrary (Join-Path $Root 'dev-library') $manifest.databases.dev.libraryReady }
    return $manifest
}

function Test-RecoveryLibrary([string]$Root, [object[]]$Assets) {
    foreach ($asset in $Assets) {
        if ($asset.object_key -notmatch '^[a-f0-9-]{36}$') { throw 'Некорректный storage key библиотеки.' }
        $file = Join-Path $Root $asset.object_key
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw 'Отсутствует оригинал READY-материала. Проверьте путь библиотеки.' }
        Assert-RecoveryTree $file
        if ((Get-Item -LiteralPath $file).Length -ne $asset.byte_size -or
            (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $asset.sha256) {
            throw 'Оригинал библиотеки не соответствует SHA/размеру из БД.'
        }
    }
}

function Get-RecoveryPgArguments([hashtable]$Database) {
    foreach ($key in 'name','user') {
        if ($Database[$key] -notmatch '^[a-z][a-z0-9_]{0,62}$') { throw "Недопустимое имя PostgreSQL $key" }
    }
    if ($Database.host -ne '127.0.0.1' -or $Database.port -lt 1 -or $Database.port -gt 65535) { throw 'Разрешён только явно заданный loopback PostgreSQL.' }
    @('--no-password',"--host=$($Database.host)","--port=$($Database.port)","--username=$($Database.user)")
}

function Invoke-RecoveryPg([string]$File, [string[]]$Arguments, [string]$Directory,
    [string]$Password, [AllowNull()][string]$InputText = $null) {
    # Windows native getenv may convert a Unicode PGPASSWORD through the system
    # ANSI code page. A UTF-8 pgpass file preserves the original credential bytes.
    if ($Password -match '[\r\n\x00]') { throw 'Пароль с переводом строки/NUL не поддерживается форматом pgpass.' }
    $temporary=Join-Path ([IO.Path]::GetTempPath()) ('gm-dr-auth-'+[guid]::NewGuid().ToString('N'))
    New-PrivateRecoveryDirectory $temporary
    try {
        $path=Join-Path $temporary 'pgpass'
        $escaped=$Password.Replace('\','\\').Replace(':','\:')
        [IO.File]::WriteAllText($path,('*:*:*:*:'+$escaped+"`n"),[Text.UTF8Encoding]::new($false))
        if (-not $IsWindows) { [IO.File]::SetUnixFileMode($path,([IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite)) }
        Invoke-RecoveryNative $File $Arguments $Directory @{PGPASSFILE=$path;PGCLIENTENCODING='UTF8'} $InputText
    } finally { Remove-Item -LiteralPath $temporary -Recurse -Force }
}

function Invoke-RecoverySql([string]$Bin, [hashtable]$Database, [string]$Password, [string]$Sql) {
    $arguments = @(Get-RecoveryPgArguments $Database) + @('--no-psqlrc','--tuples-only','--no-align','--set=ON_ERROR_STOP=1',"--dbname=$($Database.name)")
    Invoke-RecoveryPg (Join-Path $Bin 'psql.exe') $arguments $Bin $Password $Sql
}

function Get-RecoveryDatabaseSummary([string]$Bin, [hashtable]$Database, [string]$Password) {
    $names = (Invoke-RecoverySql $Bin $Database $Password "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename;").Trim() -split '\r?\n'
    $counts = @{}
    foreach ($name in $names) {
        if ($name -notmatch '^[a-z][a-z0-9_]*$') { throw 'Неожиданное имя таблицы.' }
        $counts[$name] = [long](Invoke-RecoverySql $Bin $Database $Password (('SELECT count(*) FROM public."{0}";' -f $name))).Trim()
    }
    $flyway = (Invoke-RecoverySql $Bin $Database $Password 'SELECT COALESCE(json_agg(t ORDER BY installed_rank), ''[]''::json)::text FROM (SELECT installed_rank,version,script,checksum,success FROM public.flyway_schema_history) t;').Trim()
    $library = @()
    if ($counts.ContainsKey('library_assets') -and $counts.ContainsKey('library_materials')) {
        $json = Invoke-RecoverySql $Bin $Database $Password @'
SELECT COALESCE(json_agg(t ORDER BY object_key), '[]'::json)::text FROM
(SELECT a.object_key,a.byte_size,a.sha256 FROM public.library_assets a
 JOIN public.library_materials m ON m.id=a.material_id WHERE m.state='READY') t;
'@
        $library = @($json | ConvertFrom-Json -AsHashtable)
    }
    @{counts=$counts; flyway=$flyway; libraryReady=$library}
}

function Backup-RecoveryDatabase([string]$Bin, [hashtable]$Database, [string]$Password, [string]$Path) {
    $connections = [int](Invoke-RecoverySql $Bin $Database $Password 'SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid();').Trim()
    if ($connections -ne 0) { throw "В $($Database.name) есть другие подключения. Закройте приложение и SQL-клиент." }
    $arguments = @(Get-RecoveryPgArguments $Database) + @("--dbname=$($Database.name)",'--format=custom',"--file=$Path")
    $null = Invoke-RecoveryPg (Join-Path $Bin 'pg_dump.exe') $arguments $Bin $Password
    if ((Get-Item -LiteralPath $Path).Length -eq 0) { throw 'Пустой dump.' }
    $null = Invoke-RecoveryNative (Join-Path $Bin 'pg_restore.exe') @('--list',$Path) $Bin
    Get-RecoveryDatabaseSummary $Bin $Database $Password
}

function Restore-RecoveryDatabase([string]$Bin, [string]$Dump, [hashtable]$SourceSummary,
    [hashtable]$Target, [string]$AppPassword, [string]$AdminUser, [string]$AdminPassword) {
    $null = Get-RecoveryPgArguments $Target
    $admin = @{name='postgres'; user=$AdminUser; host=$Target.host; port=$Target.port}
    # CREATE fails if anything already exists. Never DROP/--clean/--create or repair.
    $safePassword = $AppPassword.Replace("'","''")
    $exists = (Invoke-RecoverySql $Bin $admin $AdminPassword ("SELECT (EXISTS(SELECT 1 FROM pg_roles WHERE rolname='$($Target.user)') OR EXISTS(SELECT 1 FROM pg_database WHERE datname='$($Target.name)'))::int;")).Trim()
    if ($exists -ne '0') { throw 'Целевая роль или БД уже существует; никакие изменения не сделаны.' }
    $sql = ('SET standard_conforming_strings=on; CREATE ROLE "{0}" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD ''{1}'';' -f $Target.user,$safePassword)
    $null = Invoke-RecoverySql $Bin $admin $AdminPassword $sql
    $null = Invoke-RecoveryPg (Join-Path $Bin 'createdb.exe') (@(Get-RecoveryPgArguments $admin) + @(
        '--maintenance-db=postgres','--template=template0','--encoding=UTF8',"--owner=$($Target.user)",$Target.name)) $Bin $AdminPassword
    $null = Invoke-RecoveryPg (Join-Path $Bin 'pg_restore.exe') (@(Get-RecoveryPgArguments $Target) + @(
        "--dbname=$($Target.name)",'--no-owner','--no-acl','--exit-on-error','--single-transaction',$Dump)) $Bin $AppPassword
    $actual = Get-RecoveryDatabaseSummary $Bin $Target $AppPassword
    if (($actual.counts | ConvertTo-Json -Compress) -ne ($SourceSummary.counts | ConvertTo-Json -Compress)) {
        # Hashtable property order is not a data invariant.
        if ($actual.counts.Count -ne $SourceSummary.counts.Count) { throw 'Изменился набор таблиц.' }
        foreach ($name in $SourceSummary.counts.Keys) {
            if (-not $actual.counts.ContainsKey($name) -or $actual.counts[$name] -ne $SourceSummary.counts[$name]) { throw "Не совпало число строк $name" }
        }
    }
    if ($actual.flyway -ne $SourceSummary.flyway) { throw 'Не совпала история Flyway.' }
    return $actual
}

function Restore-RecoveryWorkspace([string]$RecoveryRoot, [string]$Destination, [string]$Head, [string]$Git) {
    if ($Head -notmatch '^[a-f0-9]{40}$' -or (Test-Path -LiteralPath $Destination)) { throw 'Нужны точный git SHA и новый путь рабочего дерева.' }
    $parent=Split-Path ([IO.Path]::GetFullPath($Destination))
    $null=New-Item -ItemType Directory -Force -Path $parent
    $null=Invoke-RecoveryNative $Git @('clone','--no-checkout',(Join-Path $RecoveryRoot 'workspace.bundle'),$Destination) $parent
    $null=Invoke-RecoveryNative $Git @('-c','core.autocrlf=false','checkout','--detach',$Head) $Destination
    if ((Get-Item -LiteralPath (Join-Path $RecoveryRoot 'workspace.patch')).Length -gt 0) {
        $null=Invoke-RecoveryNative $Git @('apply','--binary',(Join-Path $RecoveryRoot 'workspace.patch')) $Destination
    }
    Copy-RecoveryTree (Join-Path $RecoveryRoot 'workspace') $Destination
    $null=Invoke-RecoveryNative $Git @('remote','set-url','origin','https://github.com/roafo1989/guitar-mentor-ai.git') $Destination
}

function Write-RecoveryProgress([string]$Path, [hashtable]$State) {
    $directory = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $directory)) { $null = New-Item -ItemType Directory -Path $directory -Force }
    $temporary = Join-Path $directory ('.' + [IO.Path]::GetFileName($Path) + '.' + [guid]::NewGuid().ToString('N') + '.tmp')
    try {
        [IO.File]::WriteAllText($temporary,($State | ConvertTo-Json -Depth 24),[Text.UTF8Encoding]::new($false))
        if (Test-Path -LiteralPath $Path) {
            $backup = $temporary + '.previous'
            try { [IO.File]::Replace($temporary,$Path,$backup,$true) }
            finally { if (Test-Path -LiteralPath $backup) { Remove-Item -LiteralPath $backup -Force } }
        } else {
            [IO.File]::Move($temporary,$Path)
        }
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
    }
}

function Set-RecoveryProgressStep([hashtable]$State, [string]$Name, [ValidateSet('RUNNING','PASS','FAIL','NOT_RUN')][string]$Status,
    [string]$ProgressPath, [string]$Detail = '') {
    if (-not $State.steps) { $State.steps = @{} }
    $now = [DateTime]::UtcNow.ToString('o')
    $previous = $State.steps[$Name]
    $hasPreviousStart = ($null -ne $previous -and $previous.startedUtc)
    $started = if ($Status -eq 'RUNNING') { $now } elseif ($hasPreviousStart) { $previous.startedUtc } else { $now }
    $elapsed = if ($Status -in @('PASS','FAIL') -and $hasPreviousStart) {
        [math]::Round(([DateTime]::UtcNow - (ConvertTo-RecoveryUtcDateTime $previous.startedUtc)).TotalSeconds,1)
    } else { 0 }
    $State.steps[$Name] = @{status=$Status;startedUtc=$started;updatedUtc=$now;elapsedSeconds=$elapsed;detail=$Detail}
    Write-RecoveryProgress $ProgressPath $State
}

function Test-RecoveryProgressStep([hashtable]$State, [string]$Name) {
    return ($State.steps -and $State.steps.ContainsKey($Name) -and $State.steps[$Name].status -eq 'PASS')
}

function Assert-RecoveryStageCapacity([string]$Path, [long]$MinimumFreeBytes = 1073741824) {
    if ($MinimumFreeBytes -lt 268435456 -or $MinimumFreeBytes -gt 1099511627776) {
        throw 'Резерв свободного места должен быть от 256 MiB до 1 TiB.'
    }
    $root = [IO.Path]::GetPathRoot((Expand-RecoveryPath $Path))
    $drive = [IO.DriveInfo]::new($root)
    if (-not $drive.IsReady) { throw 'Диск для staging недоступен.' }
    if ($drive.AvailableFreeSpace -lt $MinimumFreeBytes) {
        $available = [math]::Round($drive.AvailableFreeSpace / 1GB,2)
        $required = [math]::Round($MinimumFreeBytes / 1GB,2)
        throw "Недостаточно свободного места на диске staging: доступно $available GiB, защитный резерв $required GiB. Перенесите stageRoot на диск со свободным местом."
    }
    return $drive.AvailableFreeSpace
}
) {
        return "ApplicationSmoke: $($Matches[1])"
    }
    if ($ErrorText -match 'Корневой каталог комплекта является ссылкой') {
        return 'Корневой каталог подготовленного комплекта является ссылкой. Облачная загрузка не выполнялась.'
    }
    if ($ErrorText -match 'В комплекте обнаружена ссылка \(reparse point\): ([^\r\n]+)') {
        return "В подготовленный комплект попала ссылка вместо самостоятельных данных: $($Matches[1]). Облачная загрузка не выполнялась."
    }
    if ($ErrorText -match 'Не удалось прочитать каталог комплекта: ([^\r\n]+)') {
        return "Не удалось прочитать каталог подготовленного комплекта: $($Matches[1]). Проверьте права доступа и занятость файла. Облачная загрузка не выполнялась."
    }
    if ($ErrorText -match 'Комплект содержит недопустимую ссылку или недоступный каталог') {
        return 'Локальный комплект содержит ссылку или недоступный каталог. Требуется проверить состав staging; облачная загрузка не выполнялась.'
    }
    if ($ErrorText -match 'Не удалось прочитать/посчитать SHA-256 файла комплекта: ([^\r\n]+)') {
        return "Не удалось прочитать один из подготовленных файлов: $($Matches[1]). Облачная загрузка не выполнялась."
    }
    if ($ErrorText -match 'Не совпала контрольная сумма: ([^\r\n]+)') {
        return "Контрольная сумма не совпала у файла комплекта: $($Matches[1]). Облачная загрузка не выполнялась."
    }
    if ($ErrorText -match 'Оригинал библиотеки не соответствует SHA/размеру из БД') {
        return 'Оригинал постоянной библиотеки не совпадает с SHA-256 или размером, записанным в PostgreSQL. Облачная загрузка не выполнялась.'
    }
    if ($ErrorText -match 'Отсутствует оригинал READY-материала') {
        return 'В PostgreSQL есть READY-материал, но его оригинал отсутствует в постоянной библиотеке. Облачная загрузка не выполнялась.'
    }
    if ($ErrorText -match 'Набор файлов копии не совпадает с манифестом') {
        return 'Количество файлов подготовленного комплекта не совпало с манифестом. Облачная загрузка не выполнялась.'
    }
    if ($ErrorText -match 'Неполная копия: нет ([^\r\n]+)') {
        return "В комплекте отсутствует обязательный файл: $($Matches[1]). Облачная загрузка не выполнялась."
    }
    # Preserve the complete deliberately sanitized diagnostic emitted by Recovery.ps1.
    # The outer acceptance workflow classifies the message a second time; do not
    # discard the safe detail suffix when the underlying error is not recognized.
    if ($ErrorText -match "(?s)Резервная копия остановлена на этапе '([^']+)' \(тип ошибки: ([^;]+); Recovery\.ps1:(\d+)\)\.\s*(.*?)\s*(?:Секретные данные и исходный текст исключения скрыты\.)?$") {
        $detail = [string]$Matches[4]
        $prefix = "Резервная копия остановлена на этапе '$($Matches[1])' (тип ошибки: $($Matches[2]); Recovery.ps1:$($Matches[3]))."
        if ([string]::IsNullOrWhiteSpace($detail)) { return $prefix }
        return "$prefix $detail"
    }
    # Invoke-RecoveryNative already converts provider stderr into one of these
    # safe actionable messages. Preserve that classification when outer
    # workflow catches classify the exception again (idempotent diagnostics).
    if ($ErrorText -match 'Недостаточно места или превышена квота хранилища') {
        return 'Недостаточно места или превышена квота хранилища. Освободите место/увеличьте квоту и повторите только неуспешный шаг.'
    }
    if ($ErrorText -match 'Облако или локальный ресурс отказал в доступе') {
        return 'Облако или локальный ресурс отказал в доступе. Проверьте вход, MFA, права remote и доступ к файлам.'
    }
    if ($ErrorText -match 'Сетевой сбой или тайм-аут') {
        return 'Сетевой сбой или тайм-аут. Проверьте соединение и повторите этот шаг; существующий snapshot не удаляется.'
    }
    if ($ErrorText -match 'Обнаружена ошибка целостности') {
        return 'Обнаружена ошибка целостности. Не используйте эту копию для восстановления; сохраните диагностический отчёт.'
    }
    # Provider-specific diagnostics for the first real cloud preflight. Only
    # classify known-safe signatures; never echo raw rclone/restic stderr because
    # provider errors can contain account identifiers, remote paths or tokens.
    if ($ErrorText -match '(?i)wrong password or no key found|no key found for repository') {
        return 'Мастер-пароль restic не подходит к этому облачному хранилищу. Не выполняйте Init: проверьте сохранённый пароль в менеджере паролей.'
    }
    if ($ErrorText -match '(?i)didn.t find section in config file|section .* not found|config file .* not found|failed to create file system.*config') {
        return 'rclone не нашёл remote в указанном rclone.conf. Проверьте имя remote gm-backup и путь файла конфигурации; повторная авторизация пока не нужна.'
    }
    if ($ErrorText -match '(?i)invalid_grant|AADSTS[0-9]+|token.*(expired|refresh)|failed to.*(refresh|renew).*token|authorization.*expired|reauthori[sz]') {
        return 'Авторизация OneDrive через rclone истекла или отклонена. Нужно повторно авторизовать существующий remote gm-backup; не создавайте новый remote и не запускайте Init.'
    }
    if ($ErrorText -match '(?i)driveNotFound|itemNotFound|couldn.t find.*drive|no drive found|drive.*not found') {
        return 'OneDrive не разрешил доступ к выбранному диску/папке. Проверьте, что remote gm-backup указывает на личный OneDrive нужного аккаунта.'
    }
    if ($ErrorText -match '(?i)HTTP 429|status code 429|too many requests|throttl') {
        return 'OneDrive временно ограничил частоту запросов. Подождите 10–15 минут и повторите только проверку облачного подключения.'
    }
    if ($ErrorText -match '(?i)HTTP 507|status code 507|insufficientStorage|quota exceeded|quota.*full') {
        return 'В OneDrive закончилась доступная квота. Освободите место или увеличьте объём; существующие snapshot не удаляйте.'
    }
    if ($ErrorText -match '(?i)certificate verify failed|x509:|TLS handshake|certificate.*(invalid|expired)') {
        return 'Не удалось установить защищённое TLS-соединение с облаком. Проверьте дату/время Windows, системные сертификаты и сетевой фильтр.'
    }
    if ($ErrorText -match '(?i)repository does not exist|config file does not exist|unable to open config file|Is there a repository at') {
        return 'Restic не нашёл инициализированное хранилище по заданному адресу. Не запускайте Init автоматически: сначала нужно сверить remote и путь repository.'
    }
    if ($ErrorText -match '(?i)not enough space|no space left|disk full|quota exceeded|there is insufficient space|недостаточно.*мест|квот.*превыш') {
        return 'Недостаточно места или превышена квота хранилища. Освободите место/увеличьте квоту и повторите только неуспешный шаг.'
    }
    if ($ErrorText -match '(?i)unauthorized|forbidden|invalid_grant|authentication failed|access denied|permission denied|401|403') {
        return 'Облако или локальный ресурс отказал в доступе. Проверьте вход, MFA, права remote и доступ к файлам.'
    }
    if ($ErrorText -match '(?i)timeout|timed out|connection reset|network is unreachable|temporary failure|no route to host') {
        return 'Сетевой сбой или тайм-аут. Проверьте соединение и повторите этот шаг; существующий snapshot не удаляется.'
    }
    if ($ErrorText -match '(?i)checksum|hash mismatch|integrity|corrupt|unexpected end|invalid data') {
        return 'Обнаружена ошибка целостности. Не используйте эту копию для восстановления; сохраните диагностический отчёт.'
    }
    return 'Операция не подтверждена. Проверьте доступ, место и настройки; подробный вывод с секретами намеренно скрыт.'
}

function Invoke-RecoveryNative([string]$File, [string[]]$Arguments, [string]$Directory,
    [hashtable]$Environment = @{}, [AllowNull()][string]$InputText = $null) {
    $info = New-PilotProcessInfo $File $Arguments $Directory $Environment
    # Do not inherit libpq service files/options, database overrides or a restic
    # password command that could unexpectedly override the explicit connection.
    foreach ($name in @($info.Environment.Keys)) {
        if ($name -match '^(PG|RESTIC_|RCLONE_|GIT_)') { $null = $info.Environment.Remove($name) }
    }
    foreach ($name in $Environment.Keys) { $info.Environment[$name] = [string]$Environment[$name] }
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.RedirectStandardInput = $true
    $info.StandardInputEncoding = [Text.UTF8Encoding]::new($false)
    $info.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
    $info.StandardErrorEncoding = [Text.UTF8Encoding]::new($false)
    $process = [Diagnostics.Process]::Start($info)
    try {
        $outTask = $process.StandardOutput.ReadToEndAsync()
        $errTask = $process.StandardError.ReadToEndAsync()
        if ($null -ne $InputText) { $process.StandardInput.Write($InputText) }
        $process.StandardInput.Close()
        $process.WaitForExit()
        if (-not $outTask.Wait(10000) -or -not $errTask.Wait(10000)) {
            throw 'Команда завершилась, но оставила открытые каналы вывода. Для запуска фоновых служб используйте отдельный launcher.'
        }
        $output = $outTask.GetAwaiter().GetResult()
        $errorText = $errTask.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) {
            # Classify the failure without exposing raw provider/SQL output.
            $hint = Get-RecoveryFailureHint $errorText
            throw "$([IO.Path]::GetFileName($File)): код $($process.ExitCode). $hint"
        }
        return $output
    } finally {
        if (-not $process.HasExited) { $process.Kill($true);$process.WaitForExit() }
        $process.Dispose()
    }
}

function Assert-RecoveryConfig([hashtable]$Config) {
    if ($Config.schemaVersion -ne 1) { throw 'Неизвестная версия recovery config.' }
    # This workflow is deliberately off-host. A second partition/synced folder
    # is not evidence that data has reached a remote service.
    if ($Config.repository -notmatch '^rclone:[a-zA-Z0-9_-]+:.+' -or $Config.repository -match '[\r\n]') {
        throw 'Нужен прямой облачный repository rclone:remote:folder, не папка этого SSD.'
    }
    foreach ($key in 'pilotRoot','sourceRoot','settingsRoot','postgresBin','stageRoot','rcloneConfig') {
        if ([string]::IsNullOrWhiteSpace($Config[$key])) { throw "Не заполнено $key" }
        $Config[$key] = Expand-RecoveryPath $Config[$key]
    }
    foreach ($source in @($Config.pilotRoot,$Config.sourceRoot,$Config.settingsRoot) + @($Config.extraFolders | ForEach-Object { Expand-RecoveryPath $_.path })) {
        $prefix = $source.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
        if ($Config.stageRoot.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase) -or $Config.stageRoot -eq $source) {
            throw 'Временный каталог не должен находиться внутри копируемых данных.'
        }
    }
    $ids = @()
    foreach ($folder in $Config.extraFolders) {
        if ($folder.id -notmatch '^[a-z][a-z0-9_-]{0,39}$' -or $folder.id -in $ids) { throw 'Нужны уникальные id дополнительных каталогов.' }
        $ids += $folder.id
    }
    if ($Config.devEnabled -and $Config.devDatabase.name -eq 'guitar_mentor_pilot') { throw 'DEV и PILOT должны иметь отдельные БД.' }
    if ($Config.ContainsKey('minimumFreeBytes') -and $null -ne $Config.minimumFreeBytes -and ([long]$Config.minimumFreeBytes -lt 268435456 -or [long]$Config.minimumFreeBytes -gt 1099511627776)) {
        throw 'minimumFreeBytes должен быть от 256 MiB до 1 TiB.'
    }
}

function Read-RecoveryPassword {
    $secure = Read-Host 'Мастер-пароль резервной копии (из менеджера паролей)' -AsSecureString
    if ($secure.Length -lt 16) { throw 'Используйте отдельный мастер-пароль длиной не менее 16 символов.' }
    [Net.NetworkCredential]::new('', $secure).Password
}

function Protect-RecoverySecrets([string]$Plaintext, [string]$Password) {
    $salt = [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
    $nonce = [Security.Cryptography.RandomNumberGenerator]::GetBytes(12)
    $key = [Security.Cryptography.Rfc2898DeriveBytes]::Pbkdf2($Password,$salt,600000,
        [Security.Cryptography.HashAlgorithmName]::SHA256,32)
    $bytes = [Text.Encoding]::UTF8.GetBytes($Plaintext)
    $cipher = [byte[]]::new($bytes.Length); $tag = [byte[]]::new(16)
    $aes = [Security.Cryptography.AesGcm]::new($key,16)
    try {
        $aes.Encrypt($nonce,$bytes,$cipher,$tag)
        @{version=1; rounds=600000; salt=[Convert]::ToBase64String($salt)
            nonce=[Convert]::ToBase64String($nonce); tag=[Convert]::ToBase64String($tag)
            ciphertext=[Convert]::ToBase64String($cipher)}
    } finally {
        $aes.Dispose(); [Array]::Clear($key); [Array]::Clear($bytes)
    }
}

function Unprotect-RecoverySecrets([hashtable]$Envelope, [string]$Password) {
    if ($Envelope.version -ne 1 -or $Envelope.rounds -ne 600000) { throw 'Неизвестный формат переносимых секретов.' }
    $key = [Security.Cryptography.Rfc2898DeriveBytes]::Pbkdf2($Password,
        [Convert]::FromBase64String($Envelope.salt),600000,[Security.Cryptography.HashAlgorithmName]::SHA256,32)
    $cipher = [Convert]::FromBase64String($Envelope.ciphertext)
    $plain = [byte[]]::new($cipher.Length)
    $aes = [Security.Cryptography.AesGcm]::new($key,16)
    try {
        $aes.Decrypt([Convert]::FromBase64String($Envelope.nonce),$cipher,
            [Convert]::FromBase64String($Envelope.tag),$plain)
        [Text.Encoding]::UTF8.GetString($plain) | ConvertFrom-Json -AsHashtable
    } finally { $aes.Dispose(); [Array]::Clear($key); [Array]::Clear($plain) }
}

function Export-RecoverySecrets([string]$Directory, [bool]$FilesEnabled, [string]$Password) {
    if (-not $IsWindows) { throw 'Экспорт DPAPI выполняется текущим пользователем на исходном Windows.' }
    $settings = Read-PilotJson (Join-Path $Directory 'local-auth-unified.json')
    $fields = @('clientSecret','smtpSecret','databaseSecret')
    if ($settings.ContainsKey('libraryCursorSecret')) { $fields += 'libraryCursorSecret' }
    foreach ($name in $fields) { $settings[$name] = Unlock-PilotSecret $settings[$name] }
    $files = @{'pilot-database-password.txt' = Unlock-PilotSecret (Get-Content -LiteralPath (Join-Path $Directory 'pilot-database-password.txt') -Raw)}
    if ($FilesEnabled -or (Test-Path -LiteralPath (Join-Path $Directory 'pilot-library-cursor.txt'))) {
        $files['pilot-library-cursor.txt'] = Unlock-PilotSecret (Get-Content -LiteralPath (Join-Path $Directory 'pilot-library-cursor.txt') -Raw)
    }
    try { Protect-RecoverySecrets (@{settings=$settings; secretFields=$fields; files=$files} | ConvertTo-Json -Depth 12) $Password }
    finally { $settings.Clear(); $files.Clear() }
}

function Import-RecoverySecrets([hashtable]$Secrets, [string]$Directory) {
    if (-not $IsWindows) { throw 'Повторное шифрование DPAPI выполняется на новом Windows.' }
    New-PrivateRecoveryDirectory $Directory
    $settings = $Secrets.settings.Clone()
    foreach ($name in $Secrets.secretFields) {
        if ($name -notin @('clientSecret','smtpSecret','databaseSecret','libraryCursorSecret')) { throw 'Неизвестное поле секрета.' }
        $settings[$name] = ConvertTo-SecureString $settings[$name] -AsPlainText -Force | ConvertFrom-SecureString
    }
    Write-PilotJson (Join-Path $Directory 'local-auth-unified.json') $settings
    foreach ($name in $Secrets.files.Keys) {
        if ($name -notin @('pilot-database-password.txt','pilot-library-cursor.txt')) { throw 'Неизвестный файл секрета.' }
        ConvertTo-SecureString $Secrets.files[$name] -AsPlainText -Force | ConvertFrom-SecureString |
            Set-Content -LiteralPath (Join-Path $Directory $name) -Encoding utf8
    }
}

function Invoke-RecoveryRestic([hashtable]$Config, [string]$Password, [string[]]$Arguments, [string]$Directory) {
    if (-not (Test-Path -LiteralPath $Config.rcloneConfig)) { throw 'Подключите облако через rclone config; файл конфигурации не найден.' }
    Invoke-RecoveryNative $Config.resticPath (@('--repo',$Config.repository,'--no-cache') + $Arguments) $Directory @{
        RESTIC_PASSWORD=$Password; RCLONE_CONFIG=$Config.rcloneConfig
    }
}

function Get-RecoveryFileIndex([string]$Root) {
    $rootItem = Get-Item -LiteralPath $Root -Force -ErrorAction Stop
    if ($rootItem.Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw 'Корневой каталог комплекта является ссылкой (reparse point).'
    }
    $index = @{}
    $pending = [Collections.Generic.Stack[IO.FileSystemInfo]]::new()
    foreach ($item in Get-ChildItem -LiteralPath $Root -Force -ErrorAction Stop) { $pending.Push($item) }
    while ($pending.Count -gt 0) {
        $item = $pending.Pop()
        $relative = [IO.Path]::GetRelativePath($Root,$item.FullName).Replace('\','/')
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw "В комплекте обнаружена ссылка (reparse point): $relative"
        }
        if ($item.PSIsContainer) {
            try {
                foreach ($child in Get-ChildItem -LiteralPath $item.FullName -Force -ErrorAction Stop) { $pending.Push($child) }
            } catch {
                throw "Не удалось прочитать каталог комплекта: $relative"
            }
            continue
        }
        if ($relative -eq 'gm-recovery-set.json') { continue }
        try {
            $hash = (Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256 -ErrorAction Stop).Hash
            $length = (Get-Item -LiteralPath $item.FullName -Force -ErrorAction Stop).Length
            $index[$relative] = @{bytes=$length; sha256=$hash}
        } catch {
            throw "Не удалось прочитать/посчитать SHA-256 файла комплекта: $relative"
        }
    }
    return $index
}

function Test-RecoverySet([string]$Root) {
    $manifest = Read-PilotJson (Join-Path $Root 'gm-recovery-set.json')
    if ($manifest.version -ne 1 -or $manifest.kind -ne 'guitar-mentor-disaster-recovery') { throw 'Это не комплект восстановления Guitar Mentor.' }
    if ($manifest.sourceHead -notmatch '^[a-f0-9]{40}$') { throw 'Некорректный HEAD исходников.' }
    $actual = Get-RecoveryFileIndex $Root
    if ($actual.Count -ne $manifest.files.Count) { throw 'Набор файлов копии не совпадает с манифестом.' }
    foreach ($path in $manifest.files.Keys) {
        if (-not $actual.ContainsKey($path) -or $actual[$path].sha256 -ne $manifest.files[$path].sha256 -or $actual[$path].bytes -ne $manifest.files[$path].bytes) {
            throw "Не совпала контрольная сумма: $path"
        }
    }
    foreach ($required in 'databases/pilot.dump','secrets.enc.json','pilot/release-state.json','pilot/pilot-tools.json','pilot/application-pilot.yml','workspace.bundle','workspace.patch','recovery-config.json') {
        if (-not $actual.ContainsKey($required)) { throw "Неполная копия: нет $required" }
    }
    $state = Read-PilotJson (Join-Path $Root 'pilot/release-state.json')
    foreach ($id in @($state.current,$state.previous) | Where-Object { $_ } | Select-Object -Unique) {
        $null = Test-PilotRelease (Get-PilotRelease (Join-Path $Root 'pilot') $id)
    }
    Test-RecoveryLibrary (Join-Path $Root 'pilot/data/library') $manifest.databases.pilot.libraryReady
    if ($manifest.databases.ContainsKey('dev')) { Test-RecoveryLibrary (Join-Path $Root 'dev-library') $manifest.databases.dev.libraryReady }
    return $manifest
}

function Test-RecoveryLibrary([string]$Root, [object[]]$Assets) {
    foreach ($asset in $Assets) {
        if ($asset.object_key -notmatch '^[a-f0-9-]{36}$') { throw 'Некорректный storage key библиотеки.' }
        $file = Join-Path $Root $asset.object_key
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw 'Отсутствует оригинал READY-материала. Проверьте путь библиотеки.' }
        Assert-RecoveryTree $file
        if ((Get-Item -LiteralPath $file).Length -ne $asset.byte_size -or
            (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $asset.sha256) {
            throw 'Оригинал библиотеки не соответствует SHA/размеру из БД.'
        }
    }
}

function Get-RecoveryPgArguments([hashtable]$Database) {
    foreach ($key in 'name','user') {
        if ($Database[$key] -notmatch '^[a-z][a-z0-9_]{0,62}$') { throw "Недопустимое имя PostgreSQL $key" }
    }
    if ($Database.host -ne '127.0.0.1' -or $Database.port -lt 1 -or $Database.port -gt 65535) { throw 'Разрешён только явно заданный loopback PostgreSQL.' }
    @('--no-password',"--host=$($Database.host)","--port=$($Database.port)","--username=$($Database.user)")
}

function Invoke-RecoveryPg([string]$File, [string[]]$Arguments, [string]$Directory,
    [string]$Password, [AllowNull()][string]$InputText = $null) {
    # Windows native getenv may convert a Unicode PGPASSWORD through the system
    # ANSI code page. A UTF-8 pgpass file preserves the original credential bytes.
    if ($Password -match '[\r\n\x00]') { throw 'Пароль с переводом строки/NUL не поддерживается форматом pgpass.' }
    $temporary=Join-Path ([IO.Path]::GetTempPath()) ('gm-dr-auth-'+[guid]::NewGuid().ToString('N'))
    New-PrivateRecoveryDirectory $temporary
    try {
        $path=Join-Path $temporary 'pgpass'
        $escaped=$Password.Replace('\','\\').Replace(':','\:')
        [IO.File]::WriteAllText($path,('*:*:*:*:'+$escaped+"`n"),[Text.UTF8Encoding]::new($false))
        if (-not $IsWindows) { [IO.File]::SetUnixFileMode($path,([IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite)) }
        Invoke-RecoveryNative $File $Arguments $Directory @{PGPASSFILE=$path;PGCLIENTENCODING='UTF8'} $InputText
    } finally { Remove-Item -LiteralPath $temporary -Recurse -Force }
}

function Invoke-RecoverySql([string]$Bin, [hashtable]$Database, [string]$Password, [string]$Sql) {
    $arguments = @(Get-RecoveryPgArguments $Database) + @('--no-psqlrc','--tuples-only','--no-align','--set=ON_ERROR_STOP=1',"--dbname=$($Database.name)")
    Invoke-RecoveryPg (Join-Path $Bin 'psql.exe') $arguments $Bin $Password $Sql
}

function Get-RecoveryDatabaseSummary([string]$Bin, [hashtable]$Database, [string]$Password) {
    $names = (Invoke-RecoverySql $Bin $Database $Password "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename;").Trim() -split '\r?\n'
    $counts = @{}
    foreach ($name in $names) {
        if ($name -notmatch '^[a-z][a-z0-9_]*$') { throw 'Неожиданное имя таблицы.' }
        $counts[$name] = [long](Invoke-RecoverySql $Bin $Database $Password (('SELECT count(*) FROM public."{0}";' -f $name))).Trim()
    }
    $flyway = (Invoke-RecoverySql $Bin $Database $Password 'SELECT COALESCE(json_agg(t ORDER BY installed_rank), ''[]''::json)::text FROM (SELECT installed_rank,version,script,checksum,success FROM public.flyway_schema_history) t;').Trim()
    $library = @()
    if ($counts.ContainsKey('library_assets') -and $counts.ContainsKey('library_materials')) {
        $json = Invoke-RecoverySql $Bin $Database $Password @'
SELECT COALESCE(json_agg(t ORDER BY object_key), '[]'::json)::text FROM
(SELECT a.object_key,a.byte_size,a.sha256 FROM public.library_assets a
 JOIN public.library_materials m ON m.id=a.material_id WHERE m.state='READY') t;
'@
        $library = @($json | ConvertFrom-Json -AsHashtable)
    }
    @{counts=$counts; flyway=$flyway; libraryReady=$library}
}

function Backup-RecoveryDatabase([string]$Bin, [hashtable]$Database, [string]$Password, [string]$Path) {
    $connections = [int](Invoke-RecoverySql $Bin $Database $Password 'SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid();').Trim()
    if ($connections -ne 0) { throw "В $($Database.name) есть другие подключения. Закройте приложение и SQL-клиент." }
    $arguments = @(Get-RecoveryPgArguments $Database) + @("--dbname=$($Database.name)",'--format=custom',"--file=$Path")
    $null = Invoke-RecoveryPg (Join-Path $Bin 'pg_dump.exe') $arguments $Bin $Password
    if ((Get-Item -LiteralPath $Path).Length -eq 0) { throw 'Пустой dump.' }
    $null = Invoke-RecoveryNative (Join-Path $Bin 'pg_restore.exe') @('--list',$Path) $Bin
    Get-RecoveryDatabaseSummary $Bin $Database $Password
}

function Restore-RecoveryDatabase([string]$Bin, [string]$Dump, [hashtable]$SourceSummary,
    [hashtable]$Target, [string]$AppPassword, [string]$AdminUser, [string]$AdminPassword) {
    $null = Get-RecoveryPgArguments $Target
    $admin = @{name='postgres'; user=$AdminUser; host=$Target.host; port=$Target.port}
    # CREATE fails if anything already exists. Never DROP/--clean/--create or repair.
    $safePassword = $AppPassword.Replace("'","''")
    $exists = (Invoke-RecoverySql $Bin $admin $AdminPassword ("SELECT (EXISTS(SELECT 1 FROM pg_roles WHERE rolname='$($Target.user)') OR EXISTS(SELECT 1 FROM pg_database WHERE datname='$($Target.name)'))::int;")).Trim()
    if ($exists -ne '0') { throw 'Целевая роль или БД уже существует; никакие изменения не сделаны.' }
    $sql = ('SET standard_conforming_strings=on; CREATE ROLE "{0}" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD ''{1}'';' -f $Target.user,$safePassword)
    $null = Invoke-RecoverySql $Bin $admin $AdminPassword $sql
    $null = Invoke-RecoveryPg (Join-Path $Bin 'createdb.exe') (@(Get-RecoveryPgArguments $admin) + @(
        '--maintenance-db=postgres','--template=template0','--encoding=UTF8',"--owner=$($Target.user)",$Target.name)) $Bin $AdminPassword
    $null = Invoke-RecoveryPg (Join-Path $Bin 'pg_restore.exe') (@(Get-RecoveryPgArguments $Target) + @(
        "--dbname=$($Target.name)",'--no-owner','--no-acl','--exit-on-error','--single-transaction',$Dump)) $Bin $AppPassword
    $actual = Get-RecoveryDatabaseSummary $Bin $Target $AppPassword
    if (($actual.counts | ConvertTo-Json -Compress) -ne ($SourceSummary.counts | ConvertTo-Json -Compress)) {
        # Hashtable property order is not a data invariant.
        if ($actual.counts.Count -ne $SourceSummary.counts.Count) { throw 'Изменился набор таблиц.' }
        foreach ($name in $SourceSummary.counts.Keys) {
            if (-not $actual.counts.ContainsKey($name) -or $actual.counts[$name] -ne $SourceSummary.counts[$name]) { throw "Не совпало число строк $name" }
        }
    }
    if ($actual.flyway -ne $SourceSummary.flyway) { throw 'Не совпала история Flyway.' }
    return $actual
}

function Restore-RecoveryWorkspace([string]$RecoveryRoot, [string]$Destination, [string]$Head, [string]$Git) {
    if ($Head -notmatch '^[a-f0-9]{40}$' -or (Test-Path -LiteralPath $Destination)) { throw 'Нужны точный git SHA и новый путь рабочего дерева.' }
    $parent=Split-Path ([IO.Path]::GetFullPath($Destination))
    $null=New-Item -ItemType Directory -Force -Path $parent
    $null=Invoke-RecoveryNative $Git @('clone','--no-checkout',(Join-Path $RecoveryRoot 'workspace.bundle'),$Destination) $parent
    $null=Invoke-RecoveryNative $Git @('-c','core.autocrlf=false','checkout','--detach',$Head) $Destination
    if ((Get-Item -LiteralPath (Join-Path $RecoveryRoot 'workspace.patch')).Length -gt 0) {
        $null=Invoke-RecoveryNative $Git @('apply','--binary',(Join-Path $RecoveryRoot 'workspace.patch')) $Destination
    }
    Copy-RecoveryTree (Join-Path $RecoveryRoot 'workspace') $Destination
    $null=Invoke-RecoveryNative $Git @('remote','set-url','origin','https://github.com/roafo1989/guitar-mentor-ai.git') $Destination
}

function Write-RecoveryProgress([string]$Path, [hashtable]$State) {
    $directory = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $directory)) { $null = New-Item -ItemType Directory -Path $directory -Force }
    $temporary = Join-Path $directory ('.' + [IO.Path]::GetFileName($Path) + '.' + [guid]::NewGuid().ToString('N') + '.tmp')
    try {
        [IO.File]::WriteAllText($temporary,($State | ConvertTo-Json -Depth 24),[Text.UTF8Encoding]::new($false))
        if (Test-Path -LiteralPath $Path) {
            $backup = $temporary + '.previous'
            try { [IO.File]::Replace($temporary,$Path,$backup,$true) }
            finally { if (Test-Path -LiteralPath $backup) { Remove-Item -LiteralPath $backup -Force } }
        } else {
            [IO.File]::Move($temporary,$Path)
        }
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
    }
}

function Set-RecoveryProgressStep([hashtable]$State, [string]$Name, [ValidateSet('RUNNING','PASS','FAIL','NOT_RUN')][string]$Status,
    [string]$ProgressPath, [string]$Detail = '') {
    if (-not $State.steps) { $State.steps = @{} }
    $now = [DateTime]::UtcNow.ToString('o')
    $previous = $State.steps[$Name]
    $hasPreviousStart = ($null -ne $previous -and $previous.startedUtc)
    $started = if ($Status -eq 'RUNNING') { $now } elseif ($hasPreviousStart) { $previous.startedUtc } else { $now }
    $elapsed = if ($Status -in @('PASS','FAIL') -and $hasPreviousStart) {
        [math]::Round(([DateTime]::UtcNow - (ConvertTo-RecoveryUtcDateTime $previous.startedUtc)).TotalSeconds,1)
    } else { 0 }
    $State.steps[$Name] = @{status=$Status;startedUtc=$started;updatedUtc=$now;elapsedSeconds=$elapsed;detail=$Detail}
    Write-RecoveryProgress $ProgressPath $State
}

function Test-RecoveryProgressStep([hashtable]$State, [string]$Name) {
    return ($State.steps -and $State.steps.ContainsKey($Name) -and $State.steps[$Name].status -eq 'PASS')
}

function Assert-RecoveryStageCapacity([string]$Path, [long]$MinimumFreeBytes = 1073741824) {
    if ($MinimumFreeBytes -lt 268435456 -or $MinimumFreeBytes -gt 1099511627776) {
        throw 'Резерв свободного места должен быть от 256 MiB до 1 TiB.'
    }
    $root = [IO.Path]::GetPathRoot((Expand-RecoveryPath $Path))
    $drive = [IO.DriveInfo]::new($root)
    if (-not $drive.IsReady) { throw 'Диск для staging недоступен.' }
    if ($drive.AvailableFreeSpace -lt $MinimumFreeBytes) {
        $available = [math]::Round($drive.AvailableFreeSpace / 1GB,2)
        $required = [math]::Round($MinimumFreeBytes / 1GB,2)
        throw "Недостаточно свободного места на диске staging: доступно $available GiB, защитный резерв $required GiB. Перенесите stageRoot на диск со свободным местом."
    }
    return $drive.AvailableFreeSpace
}
