#Requires -Version 7.4
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:PilotRepository = 'https://github.com/roafo1989/guitar-mentor-ai.git'

function Read-PilotJson([string]$Path) {
    Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -AsHashtable
}

function Write-PilotJson([string]$Path, $Value) {
    $temporary = "$Path.$([guid]::NewGuid().ToString('N')).tmp"
    try {
        [IO.File]::WriteAllText($temporary, ($Value | ConvertTo-Json -Depth 12))
        [IO.File]::Move($temporary, $Path, $true)
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
    }
}

function Enter-PilotLock([string]$Root) {
    try {
        [IO.File]::Open((Join-Path $Root 'operation.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
    } catch {
        throw 'Приложение или другая операция уже запущены. Сначала остановите их через Ctrl+C.'
    }
}

function Assert-PilotStopped {
    # No -ErrorAction SilentlyContinue: inability to inspect ports must fail closed.
    $listeners = @(Get-NetTCPConnection -State Listen)
    if ($listeners | Where-Object { $_.LocalPort -in 8081,8090 }) {
        throw 'Порт 8081 или 8090 занят. Остановите пилот через Ctrl+C. Чужие процессы скрипт не завершает.'
    }
}

function Assert-PilotReady([string]$Root) {
    if (Test-Path -LiteralPath (Join-Path $Root 'pending-update.json')) {
        throw 'Предыдущее обновление не завершено. Не запускайте старую версию: см. pending-update.json и инструкцию восстановления.'
    }
}

function Get-PilotRelease([string]$Root, [string]$Id) {
    if ($Id -notmatch '^[a-zA-Z0-9][a-zA-Z0-9._-]*$') { throw 'Недопустимый идентификатор версии.' }
    Join-Path (Join-Path $Root 'releases') $Id
}

function Get-PilotMigrationHash([string]$Jar) {
    # Read the *packaged* SQL, including the imported first release with unknown git SHA.
    $zip = [IO.Compression.ZipFile]::OpenRead($Jar)
    try {
        $lines = foreach ($entry in ($zip.Entries | Sort-Object FullName)) {
            if ($entry.FullName -like 'BOOT-INF/classes/db/migration/*' -and $entry.Name) {
                $stream = $entry.Open()
                try {
                    $hash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream))
                    "$($entry.FullName):$hash"
                } finally { $stream.Dispose() }
            }
        }
        if (-not $lines) { throw 'В JAR не найдены миграции: безопасный откат нельзя определить.' }
        [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData(
            [Text.Encoding]::UTF8.GetBytes(($lines -join "`n"))))
    } finally { $zip.Dispose() }
}

function Get-PilotWebHash([string]$Web) {
    if (-not (Test-Path -LiteralPath (Join-Path $Web 'index.html'))) { throw 'Нет собранного index.html.' }
    $lines = foreach ($file in (Get-ChildItem -LiteralPath $Web -File -Recurse | Sort-Object FullName)) {
        $relative = [IO.Path]::GetRelativePath($Web, $file.FullName).Replace('\','/')
        "$relative`:$((Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash)"
    }
    [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData(
        [Text.Encoding]::UTF8.GetBytes(($lines -join "`n"))))
}

function New-PilotManifest([string]$Release, [AllowNull()][string]$Commit) {
    $jar = Join-Path $Release 'app.jar'
    @{
        commit = $Commit
        createdUtc = [DateTime]::UtcNow.ToString('o')
        jarHash = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash
        webHash = Get-PilotWebHash (Join-Path $Release 'web')
        migrationHash = Get-PilotMigrationHash $jar
    }
}

function Test-PilotRelease([string]$Release) {
    $expected = Read-PilotJson (Join-Path $Release 'pilot-release.json')
    $actual = New-PilotManifest $Release $expected.commit
    foreach ($key in 'jarHash','webHash','migrationHash') {
        if ($actual[$key] -ne $expected[$key]) { throw "Повреждена версия $Release ($key). Переключение отменено." }
    }
    return $expected
}

function New-PilotProcessInfo([string]$File, [string[]]$Arguments, [string]$Directory, [hashtable]$Environment = @{}) {
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $File
    $info.WorkingDirectory = $Directory
    $info.UseShellExecute = $false
    foreach ($argument in $Arguments) { $info.ArgumentList.Add($argument) }
    # Never inherit dev/local-unified settings, test DB switches or credentials.
    foreach ($key in @($info.Environment.Keys)) {
        if ($key -match '^(SPRING_|GUITAR_MENTOR_|VITE_|SMTP_|MAIL_FROM$|PGPASSWORD$|PGSERVICE|PGOPTIONS)') {
            $null = $info.Environment.Remove($key)
        }
    }
    foreach ($key in $Environment.Keys) { $info.Environment[$key] = [string]$Environment[$key] }
    return $info
}

function Invoke-PilotTool([string]$File, [string[]]$Arguments, [string]$Directory, [hashtable]$Environment = @{}) {
    # Native .cmd wrappers require cmd.exe on Windows. Paths/arguments are fixed by
    # this implementation; ProcessStartInfo.ArgumentList handles ordinary executables.
    if ($IsWindows -and [IO.Path]::GetExtension($File) -eq '.cmd') {
        if (($File + ($Arguments -join '')) -match '["&|<>^%!\r\n]') { throw 'Недопустимые символы в пути команды.' }
        $commandLine = '""' + $File + '" ' + (($Arguments | ForEach-Object { '"' + $_ + '"' }) -join ' ') + '"'
        # cmd.exe has its own quoting rules, unlike CommandLineToArgvW. Do not
        # let .NET re-escape the command string with backslash-quoted quotes.
        $info = New-PilotProcessInfo $env:ComSpec @() $Directory $Environment
        $info.Arguments = '/d /s /c ' + $commandLine
    } else {
        $info = New-PilotProcessInfo $File $Arguments $Directory $Environment
    }
    $process = [Diagnostics.Process]::Start($info)
    try {
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) { throw "Команда $([IO.Path]::GetFileName($File)) завершилась с кодом $($process.ExitCode)." }
    } finally { $process.Dispose() }
}

function Get-PilotFunnelStatus([string]$Root, [hashtable]$Config) {
    $info = New-PilotProcessInfo $Config.tailscale @('funnel','status','--json') $Root
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $process = [Diagnostics.Process]::Start($info)
    try {
        $output = $process.StandardOutput.ReadToEndAsync()
        $errorOutput = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(15000)) { $process.Kill(); $process.WaitForExit(); throw 'Tailscale не ответил за 15 секунд.' }
        if ($process.ExitCode -ne 0) { throw 'Не удалось проверить состояние Funnel. Проверьте Tailscale.' }
        # Empty/no configuration is the only safe starting point. Refuse even a
        # private Serve mapping rather than replacing somebody else's listener.
        $text = $output.GetAwaiter().GetResult().Trim()
        if (-not $text) { throw 'Пустой ответ проверки Funnel.' }
        $value = $text | ConvertFrom-Json -AsHashtable
        if ($null -ne $value -and ($value -isnot [hashtable] -or $value.Count -gt 0)) {
            throw 'В Tailscale ещё активен Serve/Funnel. Остановите его через Ctrl+C в прежнем окне; скрипт не сбрасывает чужую конфигурацию.'
        }
        $null = $errorOutput.GetAwaiter().GetResult()
    } finally { $process.Dispose() }
}

function Unlock-PilotSecret([string]$Ciphertext) {
    if ([string]::IsNullOrWhiteSpace($Ciphertext)) { throw 'Отсутствует зашифрованный секрет.' }
    [Net.NetworkCredential]::new('', (ConvertTo-SecureString $Ciphertext.Trim())).Password
}

function Get-PilotEnvironment {
    $directory = Join-Path $env:LOCALAPPDATA 'GuitarMentorAI'
    $settings = Read-PilotJson (Join-Path $directory 'local-auth-unified.json')
    @{
        SPRING_DATASOURCE_PASSWORD = Unlock-PilotSecret (Get-Content -LiteralPath (Join-Path $directory 'pilot-database-password.txt') -Raw)
        GUITAR_MENTOR_OIDC_CLIENT_ID = $settings.clientId
        GUITAR_MENTOR_OIDC_CLIENT_SECRET = Unlock-PilotSecret $settings.clientSecret
        GUITAR_MENTOR_SMTP_HOST = $settings.smtpHost
        GUITAR_MENTOR_SMTP_PORT = $settings.smtpPort
        GUITAR_MENTOR_SMTP_USER = $settings.smtpUser
        GUITAR_MENTOR_SMTP_PASSWORD = Unlock-PilotSecret $settings.smtpSecret
        GUITAR_MENTOR_MAIL_FROM = $settings.mailFrom
        GUITAR_MENTOR_SMTP_AUTH = 'true'
        GUITAR_MENTOR_SMTP_STARTTLS = 'true'
    }
}

function Backup-PilotDatabase([string]$Root, [hashtable]$Config) {
    $directory = Join-Path $Root 'backups'
    $null = New-Item -ItemType Directory -Force -Path $directory
    $path = Join-Path $directory ("pilot-$([DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))-$([guid]::NewGuid().ToString('N').Substring(0,8)).dump")
    $settingsDirectory = Join-Path $env:LOCALAPPDATA 'GuitarMentorAI'
    $credentials = @{ PGPASSWORD = Unlock-PilotSecret (Get-Content -LiteralPath (Join-Path $settingsDirectory 'pilot-database-password.txt') -Raw) }
    try {
        Invoke-PilotTool (Join-Path $Config.postgresBin 'pg_dump.exe') @(
            '--no-password','--host=127.0.0.1','--port=5432','--username=guitar_mentor_pilot',
            '--dbname=guitar_mentor_pilot','--format=custom',"--file=$path") $Root $credentials
        if ((Get-Item -LiteralPath $path).Length -eq 0) { throw 'Пустая резервная копия.' }
        # TOC readability is checked; a real restore is a separate acceptance test (#40).
        Invoke-PilotTool (Join-Path $Config.postgresBin 'pg_restore.exe') @('--list',"--file=$path.toc",$path) $Root
        Write-Host "Резервная копия: $path"
        return $path
    } finally { $credentials.Clear() }
}

function Get-PilotAppArguments([string]$Root, [string]$Release, [hashtable]$Config) {
    @('-jar', (Join-Path $Release 'app.jar'),
        '--spring.profiles.active=secure,local-auth',
        "--spring.config.additional-location=file:$($Root.Replace('\','/'))/application-pilot.yml",
        '--server.address=127.0.0.1','--server.port=8081','--server.forward-headers-strategy=framework',
        '--spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/guitar_mentor_pilot',
        '--spring.datasource.username=guitar_mentor_pilot',
        '--guitar-mentor.security.enabled=true','--guitar-mentor.local-auth.unified-local=false',
        "--guitar-mentor.local-auth.public-origin=$($Config.origin)",
        '--guitar-mentor.submissions.enabled=false','--guitar-mentor.library.enabled=false')
}

function Start-PilotChild([string]$File, [string[]]$Arguments, [string]$Directory,
    [hashtable]$Environment, [string]$Log) {
    # Start-Process redirection avoids async event-handler races in PowerShell.
    # Build a clean environment first, including removal of inherited dev settings.
    $clean = New-PilotProcessInfo $File $Arguments $Directory $Environment
    $overrides = @{}
    foreach ($key in [Environment]::GetEnvironmentVariables('Process').Keys) {
        if (-not $clean.Environment.ContainsKey([string]$key)) { $overrides[[string]$key] = $null }
    }
    foreach ($key in $Environment.Keys) { $overrides[$key] = [string]$Environment[$key] }
    # Windows Start-Process flattens ArgumentList: quote each argument explicitly.
    $quoted = $Arguments | ForEach-Object {
        if ($_ -match '["\r\n]') { throw 'Недопустимый аргумент запуска.' }
        '"' + $_ + '"'
    }
    Start-Process -FilePath $File -ArgumentList $quoted -WorkingDirectory $Directory -Environment $overrides `
        -RedirectStandardOutput "$Log.log" -RedirectStandardError "$Log-error.log" -PassThru
}

function Test-PilotHttp {
    $handler = [Net.Http.HttpClientHandler]::new()
    $handler.UseProxy = $false
    $handler.AllowAutoRedirect = $false
    $client = [Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(5)
    try {
        foreach ($path in '/', '/diagnostics') {
            $response = $client.GetAsync("http://127.0.0.1:8090$path").GetAwaiter().GetResult()
            try {
                $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                if ([int]$response.StatusCode -ne 200 -or $body -notmatch '<script[^>]+src=') { throw "Не загрузился интерфейс $path" }
                $asset = [regex]::Match($body, '<script[^>]+src="([^"]+)"').Groups[1].Value
                if ($asset -notmatch '^/assets/[^?#]+\.js$') { throw 'Не найден локальный JS bundle.' }
                $assetResponse = $client.GetAsync("http://127.0.0.1:8090$asset").GetAwaiter().GetResult()
                try {
                    if ([int]$assetResponse.StatusCode -ne 200 -or $assetResponse.Content.Headers.ContentType.MediaType -notmatch 'javascript') {
                        throw 'Не загрузился JavaScript интерфейса.'
                    }
                } finally { $assetResponse.Dispose() }
            } finally { $response.Dispose() }
        }
        $response = $client.GetAsync('http://127.0.0.1:8090/api/v1/csrf').GetAwaiter().GetResult()
        try {
            $csrf = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
            if ([int]$response.StatusCode -ne 200 -or -not $csrf.token) { throw 'Не прошла проверка API/CSRF.' }
        } finally { $response.Dispose() }
    } finally { $client.Dispose(); $handler.Dispose() }
}

function Invoke-PilotRuntime([string]$Root, [string]$Id, [hashtable]$Config, [switch]$Publish) {
    Assert-PilotStopped
    Get-PilotFunnelStatus $Root $Config
    $release = Get-PilotRelease $Root $Id
    $null = Test-PilotRelease $release
    $null = New-Item -ItemType Directory -Force -Path (Join-Path $Root 'logs')
    $run = Join-Path $Root "logs/$([DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))-$Id"
    $hostname = ([uri]$Config.origin).DnsSafeHost
    $web = (Join-Path $release 'web').Replace('\','/')
    $gateway = Join-Path $Root 'gateway-runtime.caddyfile'
    @"
{
    admin off
    auto_https off
}
http://:8090 {
    bind 127.0.0.1
    @backend path /api/* /oauth2/* /login/* /logout
    handle @backend {
        reverse_proxy 127.0.0.1:8081 {
            header_up Host $hostname
            header_up X-Forwarded-Host $hostname
            header_up X-Forwarded-Proto https
        }
    }
    handle {
        root * "$web"
        try_files {path} /index.html
        file_server
    }
}
"@ | Set-Content -LiteralPath $gateway -Encoding utf8
    Invoke-PilotTool $Config.caddy @('validate','--config',$gateway,'--adapter','caddyfile') $Root
    $app = $null; $proxy = $null; $credentials = @{}
    try {
        $credentials = Get-PilotEnvironment
        $app = Start-PilotChild (Get-Command java.exe -ErrorAction Stop).Source `
            (Get-PilotAppArguments $Root $release $Config) $Root $credentials "$run-app"
        $credentials.Clear()
        $ready = $false
        for ($i = 0; $i -lt 90; $i++) {
            if ($app.HasExited) { throw "Сервер завершился. Логи: $run-app*.log" }
            if (@(Get-NetTCPConnection -State Listen | Where-Object { $_.LocalPort -eq 8081 }).Count) { $ready = $true; break }
            Start-Sleep -Seconds 1
        }
        if (-not $ready) { throw "Сервер не запустился за 90 секунд. Логи: $run-app*.log" }
        $proxy = Start-PilotChild $Config.caddy @('run','--config',$gateway,'--adapter','caddyfile') $Root @{} "$run-caddy"
        $healthy = $false
        for ($i = 0; $i -lt 15; $i++) {
            if ($app.HasExited -or $proxy.HasExited) { throw "Процесс завершился. Логи: $run*.log" }
            try { Test-PilotHttp; $healthy = $true; break } catch { Start-Sleep -Seconds 1 }
        }
        if (-not $healthy) { throw "Не прошла проверка сайта/API. Логи: $run*.log" }
        if ($Publish) {
            Write-Host "Версия: $Id"
            Write-Host "Ссылка для друзей: $($Config.origin)/"
            Write-Host 'Оставьте это окно открытым. Для остановки нажмите Ctrl+C.'
            Invoke-PilotTool $Config.tailscale @('funnel','http://127.0.0.1:8090') $Root
        } else { Write-Host 'Локальная проверка новой версии пройдена. Funnel не запускался.' }
    } finally {
        $credentials.Clear()
        foreach ($process in @($proxy,$app)) {
            if ($null -ne $process) {
                if (-not $process.HasExited) { $process.Kill(); $process.WaitForExit() }
                $process.Dispose()
            }
        }
    }
}

function Checkout-PilotSource([string]$Git, [string]$Source) {
    # Windows Git commonly inherits core.autocrlf=true. Preserve the repository
    # bytes for source-contract tests and Flyway checksums. Only this checkout
    # is affected; do not change the user's global Git settings or dev worktree.
    Invoke-PilotTool $Git @('-c','core.autocrlf=false','-c','core.eol=lf',
        'checkout','--detach','FETCH_HEAD') $Source
}

function Build-PilotRelease([string]$Root) {
    $id = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8)
    $source = Join-Path $Root "builds/$id"
    $null = New-Item -ItemType Directory -Force -Path $source
    $git = (Get-Command git.exe -ErrorAction Stop).Source
    Invoke-PilotTool $git @('init','--quiet') $source
    Invoke-PilotTool $git @('fetch','--depth=1',$script:PilotRepository,'refs/heads/main') $source
    Checkout-PilotSource $git $source
    # Read the fetched object locally, without another network race against main.
    $commit = (Get-Content -LiteralPath (Join-Path $source '.git/FETCH_HEAD') -Raw).Split("`t")[0].Trim()
    if ($commit -notmatch '^[a-f0-9]{40}$') { throw 'Не определён точный коммит main.' }
    Write-Host "Сборка main: $commit"
    $frontend = Join-Path $source 'frontend'
    $npm = (Get-Command npm.cmd -ErrorAction Stop).Source
    $flags = @{ VITE_GUITAR_MENTOR_LIGHT_MINIMAL='true'; VITE_GUITAR_MENTOR_AUTH_REQUIRED='true'; VITE_GUITAR_MENTOR_OAUTH_BRIDGE='false' }
    Invoke-PilotTool $npm @('ci','--no-audit','--no-fund') $frontend $flags
    foreach ($suite in 'test','test:intake','test:tuner','build') {
        Invoke-PilotTool $npm @('run',$suite) $frontend $flags
    }
    $backend = Join-Path $source 'backend'
    Invoke-PilotTool (Join-Path $backend 'mvnw.cmd') @('--batch-mode','verify') $backend
    $jars = @(Get-ChildItem -LiteralPath (Join-Path $backend 'target') -Filter '*.jar' -File)
    if ($jars.Count -ne 1) { throw 'Ожидался один собранный backend JAR.' }
    $release = Get-PilotRelease $Root $id
    $null = New-Item -ItemType Directory -Path $release
    Copy-Item -LiteralPath $jars[0].FullName -Destination (Join-Path $release 'app.jar')
    Copy-Item -LiteralPath (Join-Path $frontend 'dist') -Destination (Join-Path $release 'web') -Recurse
    Write-PilotJson (Join-Path $release 'pilot-release.json') (New-PilotManifest $release $commit)
    return $id
}

function Switch-PilotRelease([string]$Root, [hashtable]$Config, [hashtable]$State, [string]$Target, [switch]$Rollback) {
    Assert-PilotReady $Root
    Assert-PilotStopped
    Get-PilotFunnelStatus $Root $Config
    $old = Test-PilotRelease (Get-PilotRelease $Root $State.current)
    $candidate = Test-PilotRelease (Get-PilotRelease $Root $Target)
    if ($Rollback -and $old.migrationHash -ne $candidate.migrationHash) {
        throw 'Версии содержат разные миграции БД. Автоматический откат запрещён; нужна отдельная процедура восстановления. Данные не изменены.'
    }
    $backup = Backup-PilotDatabase $Root $Config
    $pending = Join-Path $Root 'pending-update.json'
    Write-PilotJson $pending @{
        from=$State.current; to=$Target; backup=$backup
        schemaChanged=($old.migrationHash -ne $candidate.migrationHash)
        startedUtc=[DateTime]::UtcNow.ToString('o')
    }
    try {
        # May apply forward migrations. Public tunnel stays off during this check.
        Invoke-PilotRuntime $Root $Target $Config
        $next = @{ current=$Target; previous=$State.current; lastBackup=$backup }
        Write-PilotJson (Join-Path $Root 'release-state.json') $next
        Remove-Item -LiteralPath $pending
    } catch {
        # Even failed startup may have committed a migration. Never silently boot
        # the old binary after such a failure. Preserve backup and recovery marker.
        if ($old.migrationHash -eq $candidate.migrationHash) { Remove-Item -LiteralPath $pending -ErrorAction SilentlyContinue }
        throw
    }
}
