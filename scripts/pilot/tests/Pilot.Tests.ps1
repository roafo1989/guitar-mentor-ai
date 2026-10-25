#Requires -Version 7.4
# Self-contained executable tests: no Pester/gallery download and no real DB.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../Pilot.Common.ps1')
$script:count = 0
function Assert($Condition, [string]$Message) {
    if (-not $Condition) { throw "FAIL: $Message" }
    $script:count++
}
function Must-Throw([scriptblock]$Action, [string]$Message) {
    $thrown = $false
    try { & $Action } catch { $thrown = $true }
    Assert $thrown $Message
}
$root = Join-Path ([IO.Path]::GetTempPath()) ('pilot-test-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $root
function Fixture([string]$Id, [string]$Sql) {
    $release = Get-PilotRelease $root $Id
    $null = New-Item -ItemType Directory -Force -Path (Join-Path $release 'web/assets')
    '<html><script type="module" src="/assets/app.js"></script></html>' | Set-Content (Join-Path $release 'web/index.html')
    'console.log("fixture");' | Set-Content (Join-Path $release 'web/assets/app.js')
    $jar = [IO.Compression.ZipFile]::Open((Join-Path $release 'app.jar'), 'Create')
    try {
        $entry = $jar.CreateEntry('BOOT-INF/classes/db/migration/V1__fixture.sql')
        $writer = [IO.StreamWriter]::new($entry.Open())
        try { $writer.Write($Sql) } finally { $writer.Dispose() }
    } finally { $jar.Dispose() }
    Write-PilotJson (Join-Path $release 'pilot-release.json') (New-PilotManifest $release ('a' * 40))
    return $release
}
try {
    # Parse every shipped file, including Windows-only entry points.
    foreach ($file in (Get-ChildItem (Join-Path $PSScriptRoot '..') -Filter '*.ps1' -Recurse)) {
        $tokens = $null; $errors = $null
        $null = [Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$tokens, [ref]$errors)
        Assert ($errors.Count -eq 0) "Parse $($file.Name): $errors"
    }
    $lock = Enter-PilotLock $root
    try { Must-Throw { $other = Enter-PilotLock $root; $other.Dispose() } 'concurrent operation blocked' }
    finally { $lock.Dispose() }
    $lock = Enter-PilotLock $root
    $lock.Dispose()
    Assert (Test-Path (Join-Path $root 'operation.lock')) 'stale lock file is harmless after handle close'
    Must-Throw { Get-PilotRelease $root '../escape' } 'path traversal blocked'

    $oldPath = Fixture 'old' 'create table example(id int);'
    $newPath = Fixture 'new' 'create table example(id int);'
    $changedPath = Fixture 'changed' 'create table example(id bigint);'
    $oldManifest = Test-PilotRelease $oldPath
    $newManifest = Test-PilotRelease $newPath
    $changedManifest = Test-PilotRelease $changedPath
    Assert ($oldManifest.migrationHash -eq $newManifest.migrationHash) 'same packaged SQL permits code rollback'
    Assert ($oldManifest.migrationHash -ne $changedManifest.migrationHash) 'modified packaged SQL detected'
    'tampered' | Set-Content (Join-Path $newPath 'web/assets/app.js')
    Must-Throw { Test-PilotRelease $newPath } 'web corruption rejected'
    'console.log("fixture");' | Set-Content (Join-Path $newPath 'web/assets/app.js')
    $null = Test-PilotRelease $newPath

    # Native exit-code and environment behavior is exercised with a real child.
    $pwsh = [Environment]::ProcessPath
    Must-Throw { Invoke-PilotTool $pwsh @('-NoProfile','-Command','exit 17') $root } 'nonzero native exit stops update'
    $env:SPRING_PROFILES_ACTIVE = 'local-unified'
    $env:GUITAR_MENTOR_ENABLE_UPLOADS = 'true'
    $env:PGPASSWORD = 'test-only-do-not-inherit'
    $env:VITE_GUITAR_MENTOR_OAUTH_BRIDGE = 'true'
    $check = @'
if ($env:SPRING_PROFILES_ACTIVE -or $env:GUITAR_MENTOR_ENABLE_UPLOADS -or $env:PGPASSWORD) { exit 21 }
if ($env:VITE_GUITAR_MENTOR_OAUTH_BRIDGE -ne 'false') { exit 22 }
'@
    Invoke-PilotTool $pwsh @('-NoProfile','-Command',$check) $root @{ VITE_GUITAR_MENTOR_OAUTH_BRIDGE='false' }
    Assert ($env:SPRING_PROFILES_ACTIVE -eq 'local-unified') 'parent environment untouched'
    $info = New-PilotProcessInfo $pwsh @() $root @{ SPRING_DATASOURCE_PASSWORD='child-only-test' }
    Assert ($info.Environment['SPRING_DATASOURCE_PASSWORD'] -eq 'child-only-test') 'credentials passed only in child environment'
    Assert (-not $info.Environment.ContainsKey('PGPASSWORD')) 'database credential removed from unrelated child'

    # Regression from the real Windows pilot: inherited autocrlf=true broke
    # score-reader-contract's LF-sensitive source assertion before deployment.
    # Use actual Git repositories, not a mock of the checkout command.
    $git = (Get-Command git -ErrorAction Stop).Source
    $seed = Join-Path $root 'git seed'
    $null = New-Item -ItemType Directory -Path $seed
    Invoke-PilotTool $git @('init','--quiet') $seed
    [IO.File]::WriteAllText((Join-Path $seed 'source.vue'), "await restoreSession();`n    return;`n")
    Invoke-PilotTool $git @('-c','core.autocrlf=false','add','source.vue') $seed
    Invoke-PilotTool $git @('-c','user.name=Pilot Test','-c','user.email=pilot-test@example.invalid',
        'commit','--quiet','-m','LF fixture') $seed
    foreach ($kind in 'control','fixed') {
        $checkout = Join-Path $root "git $kind"
        $null = New-Item -ItemType Directory -Path $checkout
        Invoke-PilotTool $git @('init','--quiet') $checkout
        Invoke-PilotTool $git @('config','--local','core.autocrlf','true') $checkout
        Invoke-PilotTool $git @('fetch','--quiet','--depth=1',$seed,'HEAD') $checkout
        if ($kind -eq 'control') {
            Invoke-PilotTool $git @('checkout','--quiet','--detach','FETCH_HEAD') $checkout
        } else { Checkout-PilotSource $git $checkout }
        $content = [IO.File]::ReadAllText((Join-Path $checkout 'source.vue'))
        if ($kind -eq 'control') {
            Assert ($content.Contains("`r`n")) 'control reproduces inherited Windows CRLF checkout'
            Assert ($content -notmatch 'await restoreSession\(\);\n    return;') 'control reproduces reported contract failure'
        } else {
            Assert (-not $content.Contains("`r")) 'release checkout preserves LF despite autocrlf=true'
            Assert ($content -match 'await restoreSession\(\);\n    return;') 'reported source contract passes with release checkout'
            Assert (([IO.File]::ReadAllText((Join-Path $checkout '.git/config'))) -match 'autocrlf = true') 'local user Git setting not rewritten'
        }
    }
    if ($IsWindows) {
        $cmd = Join-Path $root 'native wrapper.cmd'
        '@exit /b 19' | Set-Content $cmd
        Must-Throw { Invoke-PilotTool $cmd @() $root } 'Windows cmd wrapper exit checked'
        '@echo off' + "`r`n" + '@if "%~1"=="two words" (exit /b 0) else (exit /b 23)' | Set-Content $cmd
        Invoke-PilotTool $cmd @('two words') $root
        Assert $true 'Windows cmd wrapper quotes spaces'
        $child = Start-PilotChild $pwsh @('-NoProfile','-Command','if ($env:PGPASSWORD) { exit 24 }; exit 0') $root @{} (Join-Path $root 'child')
        try { $child.WaitForExit(); Assert ($child.ExitCode -eq 0) 'Start-Process child cannot inherit dev secrets' }
        finally { $child.Dispose() }
    }

    # Workflow fault injection: DB/runtime side effects replaced; real manifests,
    # file locking and atomic state writes are kept.
    function Assert-PilotStopped { if ($script:busy) { throw 'busy fixture' } }
    function Get-PilotFunnelStatus($Root,$Config) { if ($script:funnelActive) { throw 'active Funnel fixture' } }
    function Backup-PilotDatabase($Root,$Config) {
        $script:backups++
        if ($script:backupFails) { throw 'backup fixture failure' }
        'fixture-backup.dump'
    }
    function Invoke-PilotRuntime($Root,$Id,$Config) {
        $script:smokes++
        Assert (Test-Path (Join-Path $Root 'pending-update.json')) 'recovery marker precedes app/migration start'
        if ($script:smokeFails) { throw 'runtime fixture failure' }
    }
    $script:busy = $false; $script:funnelActive = $false; $script:backups = 0; $script:smokes = 0
    $script:backupFails = $false; $script:smokeFails = $false
    $state = @{ current='old'; previous=$null; lastBackup=$null }
    $statePath = Join-Path $root 'release-state.json'
    Write-PilotJson $statePath $state
    $config = @{}

    $script:busy = $true
    Must-Throw { Switch-PilotRelease $root $config $state 'new' } 'occupied ports reject switch'
    Assert ($script:backups -eq 0) 'no backup or mutation while app running'
    $script:busy = $false
    $script:funnelActive = $true
    Must-Throw { Switch-PilotRelease $root $config $state 'new' } 'existing Funnel blocks private smoke'
    Assert ($script:backups -eq 0) 'existing Funnel refused before database work'
    $script:funnelActive = $false
    $script:backupFails = $true
    Must-Throw { Switch-PilotRelease $root $config $state 'new' } 'backup failure rejects switch'
    Assert ($script:smokes -eq 0) 'failed backup never starts candidate'
    Assert ((Read-PilotJson $statePath).current -eq 'old') 'backup failure preserves current'
    $script:backupFails = $false
    $script:smokeFails = $true
    Must-Throw { Switch-PilotRelease $root $config $state 'new' } 'same-schema smoke failure rejects switch'
    Assert ((Read-PilotJson $statePath).current -eq 'old') 'smoke failure preserves current'
    Assert (-not (Test-Path (Join-Path $root 'pending-update.json'))) 'same-schema failure allows restarting old binary'

    Must-Throw { Switch-PilotRelease $root $config $state 'changed' } 'migration-changing smoke failure rejects switch'
    $pending = Read-PilotJson (Join-Path $root 'pending-update.json')
    Assert ($pending.schemaChanged -and $pending.backup -eq 'fixture-backup.dump') 'schema failure records exact recovery backup'
    Must-Throw { Assert-PilotReady $root } 'incomplete migration blocks normal launch'
    $before = $script:backups
    Must-Throw { Switch-PilotRelease $root $config $state 'new' } 'pending recovery blocks another update'
    Assert ($before -eq $script:backups) 'pending recovery is not overwritten'
    Remove-Item (Join-Path $root 'pending-update.json') # fixture reset, NOT recovery instructions

    $script:smokeFails = $false
    Switch-PilotRelease $root $config $state 'new'
    $state = Read-PilotJson $statePath
    Assert ($state.current -eq 'new' -and $state.previous -eq 'old') 'successful switch stores current and previous atomically'
    Assert (-not (Test-Path (Join-Path $root 'pending-update.json'))) 'successful switch clears recovery marker'
    Switch-PilotRelease $root $config $state 'old' -Rollback
    $state = Read-PilotJson $statePath
    Assert ($state.current -eq 'old' -and $state.previous -eq 'new') 'same-schema rollback retains DB and supports return'
    $before = $script:backups
    Must-Throw { Switch-PilotRelease $root $config $state 'changed' -Rollback } 'schema-changing rollback refused before DB work'
    Assert ($before -eq $script:backups) 'refused rollback does not touch DB'

    # Exercise the temporary cluster lifecycle and failure cleanup without
    # touching any real database. A separate Windows CI step runs real initdb,
    # pg_ctl and Maven against the actual project, using the same function.
    & {
        $bin = Join-Path $root 'fake-postgres-bin'
        $null = New-Item -ItemType Directory -Path $bin
        foreach ($name in 'initdb.exe','pg_ctl.exe') { $null = New-Item -ItemType File -Path (Join-Path $bin $name) }
        function Invoke-PilotTool($File,$Arguments,$Directory,$Environment=@{}) {
            switch ([IO.Path]::GetFileName($File)) {
                'initdb.exe' {
                    $script:testData = $Arguments[1]
                    $null = New-Item -ItemType Directory -Path $script:testData
                    Assert ($Arguments -contains '--auth-host=scram-sha-256') 'test cluster requires password on loopback'
                }
                'pg_ctl.exe' {
                    if ($Arguments[-1] -eq 'start') {
                        if ($script:backendFailure -eq 'start') { throw 'start fixture failure' }
                        $null = New-Item -ItemType File -Path (Join-Path $script:testData 'postmaster.pid')
                    } else {
                        $script:testStops++
                        if ($script:backendFailure -eq 'stop') { throw 'stop fixture failure' }
                        Remove-Item -LiteralPath (Join-Path $script:testData 'postmaster.pid')
                    }
                }
                'mvnw.cmd' {
                    $script:testMaven++
                    Assert ($Environment.SPRING_DATASOURCE_URL -match '^jdbc:postgresql://127\.0\.0\.1:\d+/postgres$') 'Maven receives only temporary loopback database'
                    Assert ($Environment.SPRING_DATASOURCE_USERNAME -eq 'gm_build_test') 'Maven never uses pilot/dev role'
                    Assert ($Environment.SPRING_DATASOURCE_PASSWORD.Length -eq 64) 'test credential generated separately'
                    Assert ($Environment.GUITAR_MENTOR_TEST_POSTGRES -eq 'true') 'PostgreSQL integration tests enabled'
                    Assert ($Arguments -contains 'verify' -and $Arguments -notcontains '-DskipTests') 'Maven tests not bypassed'
                    if ($script:backendFailure -eq 'maven') { throw 'Maven fixture failure' }
                }
                default { throw "Unexpected test command: $File" }
            }
        }
        foreach ($failure in 'none','maven','start','stop') {
            $script:backendFailure=$failure; $script:testStops=0; $script:testMaven=0
            if ($failure -eq 'none') { Invoke-PilotBackendBuild $root $bin }
            else { Must-Throw { Invoke-PilotBackendBuild $root $bin } "temporary cluster propagates $failure failure" }
            $clusterRoot = Split-Path $script:testData -Parent
            if ($failure -eq 'stop') {
                Assert (Test-Path -LiteralPath $script:testData) 'never delete files below a running PostgreSQL'
                Remove-Item -LiteralPath $clusterRoot -Recurse -Force # fake process, fixture cleanup only
            } else { Assert (-not (Test-Path -LiteralPath $clusterRoot)) 'temporary test database and password file removed'
            }
            if ($failure -eq 'start') { Assert ($script:testMaven -eq 0) 'Maven never falls back to default DB if cluster startup fails' }
            else { Assert ($script:testStops -eq 1) 'temporary server stopped after Maven, including failed tests' }
        }
    }
    Write-Host "PASS: $script:count assertions. Real Windows/DPAPI/PostgreSQL/Funnel acceptance is separate."
} finally {
    foreach ($name in 'SPRING_PROFILES_ACTIVE','GUITAR_MENTOR_ENABLE_UPLOADS','PGPASSWORD','VITE_GUITAR_MENTOR_OAUTH_BRIDGE') {
        [Environment]::SetEnvironmentVariable($name,$null,'Process')
    }
    Remove-Item -LiteralPath $root -Recurse -Force
}
