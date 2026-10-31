#Requires -Version 7.4
param([string]$ResticPath)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '../Recovery.Common.ps1')
$script:assertions=0
function Assert($Value,[string]$Message) { if (-not $Value) { throw "FAIL: $Message" }; $script:assertions++ }
function MustThrow([scriptblock]$Action,[string]$Message) {
    $failed=$false;try { & $Action } catch { $failed=$true };Assert $failed $Message
}
$root=Join-Path ([IO.Path]::GetTempPath()) ('gm-recovery-test-'+[guid]::NewGuid().ToString('N'))
New-PrivateRecoveryDirectory $root
try {
    $savedCulture=[Globalization.CultureInfo]::CurrentCulture
    try {
        [Globalization.CultureInfo]::CurrentCulture=[Globalization.CultureInfo]::GetCultureInfo('ru-RU')
        $jsonDate=ConvertTo-RecoveryUtcDateTime '2026-10-31T16:57:26Z'
        Assert ($jsonDate.ToString('yyyy-MM-ddTHH:mm:ssZ',[Globalization.CultureInfo]::InvariantCulture) -eq '2026-10-31T16:57:26Z') 'ISO snapshot timestamp parses under ru-RU'
        $jsonDateObject=ConvertTo-RecoveryUtcDateTime ([DateTime]::new(2026,10,31,16,57,26,[DateTimeKind]::Utc))
        Assert ($jsonDateObject -eq $jsonDate) 'JSON-converted DateTime snapshot timestamp is culture independent'
    } finally { [Globalization.CultureInfo]::CurrentCulture=$savedCulture }
    $freeBytes=Assert-RecoveryStageCapacity $root 268435456
    Assert ($freeBytes -ge 268435456) 'staging disk reserve accepts available free space'
    MustThrow { Assert-RecoveryStageCapacity $root 1099511627776 } 'staging disk reserve blocks insufficient free space'
    Assert ((Get-RecoveryFailureHint 'Недостаточно свободного места на диске staging') -like '*Недостаточно места*') 'disk reserve failure receives actionable safe diagnostic'
    Assert ((Get-RecoveryFailureHint 'restic.exe: код 1. Облако или локальный ресурс отказал в доступе. Проверьте вход, MFA, права remote и доступ к файлам.') -like '*отказал в доступе*') 'nested native access diagnostic preserves actionable classification'
    Assert ((Get-RecoveryFailureHint 'rclone: код 1. Сетевой сбой или тайм-аут. Проверьте соединение и повторите этот шаг; существующий snapshot не удаляется.') -like '*Сетевой сбой*') 'nested native network diagnostic preserves actionable classification'
    Assert ((Get-RecoveryFailureHint 'restic.exe: код 1. Обнаружена ошибка целостности. Не используйте эту копию для восстановления; сохраните диагностический отчёт.') -like '*ошибка целостности*') 'nested native integrity diagnostic preserves actionable classification'
    $progressPath=Join-Path $root 'resume-progress.json'
    $progress=@{steps=@{};fixture='synthetic'}
    Set-RecoveryProgressStep $progress 'SyntheticStep' 'RUNNING' $progressPath
    Assert (-not (Test-RecoveryProgressStep $progress 'SyntheticStep')) 'running step is not falsely marked complete'
    Set-RecoveryProgressStep $progress 'SyntheticStep' 'PASS' $progressPath
    $reloaded=Read-PilotJson $progressPath
    Assert (Test-RecoveryProgressStep $reloaded 'SyntheticStep') 'completed step survives atomic progress reload'
    $savedProgressCulture=[Globalization.CultureInfo]::CurrentCulture
    try {
        [Globalization.CultureInfo]::CurrentCulture=[Globalization.CultureInfo]::GetCultureInfo('ru-RU')
        Set-RecoveryProgressStep $reloaded 'SyntheticStep' 'PASS' $progressPath
        Assert ($reloaded.steps.SyntheticStep.elapsedSeconds -ge 0) 'resumed progress elapsed time is culture independent under ru-RU'
    } finally { [Globalization.CultureInfo]::CurrentCulture=$savedProgressCulture }
    Set-RecoveryProgressStep $reloaded 'SyntheticStep' 'FAIL' $progressPath 'synthetic failure'
    Assert (-not (Test-RecoveryProgressStep (Read-PilotJson $progressPath) 'SyntheticStep')) 'failed step is eligible for resume'
    Set-RecoveryProgressStep (Read-PilotJson $progressPath) 'SyntheticStep' 'RUNNING' $progressPath
    Set-RecoveryProgressStep (Read-PilotJson $progressPath) 'SyntheticStep' 'PASS' $progressPath
    Assert (Test-RecoveryProgressStep (Read-PilotJson $progressPath) 'SyntheticStep') 'resumed step can complete without resetting other progress'
    foreach ($file in Get-ChildItem (Join-Path $PSScriptRoot '..') -Filter '*.ps1' -Recurse) {
        $tokens=$null;$errors=$null
        $null=[Management.Automation.Language.Parser]::ParseFile($file.FullName,[ref]$tokens,[ref]$errors)
        Assert ($errors.Count -eq 0) "parse $($file.Name): $errors"
    }
    $password='only synthetic fixture: café / пароль 123'
    $fixture='{"settings":{"databaseSecret":"test quote '' and Unicode 音楽"},"files":{}}'
    $envelope=Protect-RecoverySecrets $fixture $password
    Assert (($envelope | ConvertTo-Json) -notlike '*test quote*') 'ciphertext contains no secret text'
    $plain=Unprotect-RecoverySecrets $envelope $password
    Assert ($plain.settings.databaseSecret -eq "test quote ' and Unicode 音楽") 'portable secret roundtrip'
    MustThrow { Unprotect-RecoverySecrets $envelope 'wrong-password' } 'wrong master password blocked'
    $bad=$envelope.Clone();$bytes=[Convert]::FromBase64String($bad.ciphertext);$bytes[0]=$bytes[0] -bxor 1
    $bad.ciphertext=[Convert]::ToBase64String($bytes)
    MustThrow { Unprotect-RecoverySecrets $bad $password } 'tampered secret authentication fails'
    $bad=$envelope.Clone();$bad.rounds=1
    MustThrow { Unprotect-RecoverySecrets $bad $password } 'unrecognised KDF settings blocked'
    MustThrow { New-PrivateRecoveryDirectory $root } 'existing destination never overwritten'
    $copy=Join-Path $root 'copy';$source=Join-Path $root 'source'
    $null=New-Item -ItemType Directory -Path (Join-Path $source 'node_modules') -Force
    [IO.File]::WriteAllBytes((Join-Path $source 'binary.pdf'),[byte[]](0,255,10,13,200))
    [IO.File]::WriteAllText((Join-Path $source '.env'),'synthetic ignored credential')
    [IO.File]::WriteAllText((Join-Path $source 'node_modules/cache'),'excluded')
    Copy-RecoveryTree $source $copy @('node_modules')
    Assert ((Get-FileHash (Join-Path $source 'binary.pdf')).Hash -eq (Get-FileHash (Join-Path $copy 'binary.pdf')).Hash) 'binary bytes preserved'
    Assert (Test-Path (Join-Path $copy '.env')) 'hidden ignored settings preserved'
    Assert (-not (Test-Path (Join-Path $copy 'node_modules'))) 'caches excluded'
    $assetKey='00000000-0000-0000-0000-000000000001'
    Copy-Item -LiteralPath (Join-Path $copy 'binary.pdf') -Destination (Join-Path $copy $assetKey)
    $asset=@{object_key=$assetKey;byte_size=5;sha256=(Get-FileHash (Join-Path $copy $assetKey)).Hash}
    Test-RecoveryLibrary $copy @($asset)
    Assert $true 'READY library object matches DB size and SHA'
    $badAsset=$asset.Clone();$badAsset.byte_size=6
    MustThrow { Test-RecoveryLibrary $copy @($badAsset) } 'DB/file size mismatch blocked'
    $badAsset=$asset.Clone();$badAsset.object_key='../escape'
    MustThrow { Test-RecoveryLibrary $copy @($badAsset) } 'DB storage-key traversal blocked'
    $badAsset=$asset.Clone();$badAsset.sha256='f'*64
    MustThrow { Test-RecoveryLibrary $copy @($badAsset) } 'DB/file digest mismatch blocked'
    MustThrow { Test-RecoveryLibrary (Join-Path $root 'missing') @($asset) } 'missing READY original blocked'
    $link=Join-Path $source 'outside-link'
    if (-not $IsWindows) {
        $null=New-Item -ItemType SymbolicLink -Path $link -Target $root
        MustThrow { Copy-RecoveryTree $source (Join-Path $root 'bad-copy') } 'symlink escape/cycle refused'
        Remove-Item -LiteralPath $link
    }
    $config=Read-PilotJson (Join-Path $PSScriptRoot '../recovery.example.json')
    $config.pilotRoot=Join-Path $root 'pilot';$config.sourceRoot=Join-Path $root 'repo'
    $config.settingsRoot=Join-Path $root 'settings';$config.stageRoot=Join-Path $root 'stage'
    Assert-RecoveryConfig $config
    $bad=$config.Clone();$bad.repository='D:\same-ssd'
    MustThrow { Assert-RecoveryConfig $bad } 'same-SSD repository refused'
    $bad=$config.Clone();$bad.stageRoot=Join-Path $bad.pilotRoot 'inside'
    MustThrow { Assert-RecoveryConfig $bad } 'recursive staging refused'
    $bad=$config.Clone();$bad.extraFolders=@(@{id='../escape';path=$root})
    MustThrow { Assert-RecoveryConfig $bad } 'unsafe folder identifiers refused'
    MustThrow { Get-RecoveryPgArguments @{host='remote.example';port=5432;name='test';user='test'} } 'remote DB target refused'
    MustThrow { Get-RecoveryPgArguments @{host='127.0.0.1';port=5432;name='x dbname=prod';user='test'} } 'libpq injection refused'
    $native=[Environment]::ProcessPath
    $env:PGSERVICE='unexpected';$env:SPRING_DATASOURCE_PASSWORD='parent-only';$env:RESTIC_PASSWORD_COMMAND='unexpected'
    $output=Invoke-RecoveryNative $native @('-NoProfile','-Command',
        'if($env:PGSERVICE -or $env:SPRING_DATASOURCE_PASSWORD -or $env:RESTIC_PASSWORD_COMMAND){exit 4}; [Console]::Out.Write([Console]::In.ReadToEnd())') $root @{} 'stdin secret fixture'
    Assert ($output -eq 'stdin secret fixture') 'secret input through pipe; inherited credentials stripped'
    MustThrow { Invoke-RecoveryNative $native @('-NoProfile','-Command','[Console]::Error.Write("sensitive-output");exit 3') $root } 'native nonzero fails backup'
    Assert ((Get-RecoveryFailureHint 'ERROR: no space left on device') -like '*Недостаточно места*') 'disk/quota failures receive actionable safe diagnostics'
    Assert ((Get-RecoveryFailureHint 'HTTP 403 forbidden') -like '*отказал в доступе*') 'cloud permission failures receive actionable safe diagnostics'
    Assert ((Get-RecoveryFailureHint 'connection reset by peer') -like '*Сетевой сбой*') 'network failures receive actionable safe diagnostics'
    Assert ((Get-RecoveryFailureHint 'checksum mismatch') -like '*целостности*') 'integrity failures receive actionable safe diagnostics'
    Assert ((Get-RecoveryFailureHint 'Не удалось прочитать/посчитать SHA-256 файла комплекта: pilot/data/library/asset.bin') -like '*pilot/data/library/asset.bin*') 'local unreadable file diagnostic preserves safe relative path'
    Assert ((Get-RecoveryFailureHint 'В комплекте обнаружена ссылка (reparse point): pilot/tools/current') -like '*pilot/tools/current*') 'local reparse point diagnostic preserves safe relative path'
    Assert ((Get-RecoveryFailureHint 'Не удалось прочитать каталог комплекта: pilot/data/library') -like '*pilot/data/library*') 'local directory access diagnostic preserves safe relative path'
    Assert ((Get-RecoveryFailureHint 'Корневой каталог комплекта является ссылкой (reparse point).') -like '*корневой каталог*') 'local root reparse point diagnostic is actionable'
    Assert ((Get-RecoveryFailureHint 'Не совпала контрольная сумма: pilot/application-pilot.yml') -like '*pilot/application-pilot.yml*') 'manifest checksum diagnostic preserves safe relative path'
    Assert ((Get-RecoveryFailureHint 'Оригинал библиотеки не соответствует SHA/размеру из БД.') -like '*не совпадает с SHA-256*') 'library DB/file mismatch gets precise safe diagnostic'
    Assert ((Get-RecoveryFailureHint 'Отсутствует оригинал READY-материала. Проверьте путь библиотеки.') -like '*оригинал отсутствует*') 'missing library original gets precise safe diagnostic'
    try { Invoke-RecoveryNative $native @('-NoProfile','-Command','[Console]::Error.Write("sensitive-output");exit 1') $root } catch {
        Assert ($_.Exception.Message -notlike '*sensitive-output*') 'tool errors do not leak output'
    }
    # Exercise actual Git bundle and binary diff: preserve local deletion, ignored
    # config, untracked files and unpushed history without touching a remote.
    $git=(Get-Command git -ErrorAction Stop).Source
    $repoSource=Join-Path $root 'git-source';$null=New-Item -ItemType Directory -Path $repoSource
    $null=Invoke-RecoveryNative $git @('init','--quiet') $repoSource
    foreach ($setting in @(@('user.name','Fixture'),@('user.email','fixture@example.invalid'),@('core.autocrlf','false'))) {
        $null=Invoke-RecoveryNative $git @('config',$setting[0],$setting[1]) $repoSource
    }
    [IO.File]::WriteAllText((Join-Path $repoSource '.gitignore'),'.env')
    [IO.File]::WriteAllText((Join-Path $repoSource 'deleted.txt'),'tracked deletion')
    [IO.File]::WriteAllBytes((Join-Path $repoSource 'binary.dat'),[byte[]](0,1,2,3))
    $null=Invoke-RecoveryNative $git @('add','.') $repoSource
    $null=Invoke-RecoveryNative $git @('commit','--quiet','-m','unpublished fixture') $repoSource
    $head=(Invoke-RecoveryNative $git @('rev-parse','HEAD') $repoSource).Trim()
    Remove-Item (Join-Path $repoSource 'deleted.txt')
    [IO.File]::WriteAllText((Join-Path $repoSource '.env'),'ignored fixture config')
    [IO.File]::WriteAllText((Join-Path $repoSource 'untracked.txt'),'untracked fixture')
    [IO.File]::WriteAllBytes((Join-Path $repoSource 'binary.dat'),[byte[]](0,255,1,10,13))
    $set=Join-Path $root 'git-set';$null=New-Item -ItemType Directory -Path $set
    $null=Invoke-RecoveryNative $git @('bundle','create',(Join-Path $set 'workspace.bundle'),'--all') $repoSource
    $patch=Invoke-RecoveryNative $git @('diff','--binary','HEAD','--') $repoSource
    [IO.File]::WriteAllText((Join-Path $set 'workspace.patch'),$patch,[Text.UTF8Encoding]::new($false))
    Copy-RecoveryTree $repoSource (Join-Path $set 'workspace') @('.git')
    $repoRestored=Join-Path $root 'git-restored'
    Restore-RecoveryWorkspace $set $repoRestored $head $git
    Assert (-not (Test-Path (Join-Path $repoRestored 'deleted.txt'))) 'tracked deletion survives restore'
    Assert ((Get-FileHash (Join-Path $repoRestored 'binary.dat')).Hash -eq (Get-FileHash (Join-Path $repoSource 'binary.dat')).Hash) 'uncommitted binary edits survive'
    Assert ((Get-Content (Join-Path $repoRestored '.env') -Raw) -eq 'ignored fixture config') 'ignored local config survives'
    Assert (Test-Path (Join-Path $repoRestored 'untracked.txt')) 'untracked file survives'
    Assert ((Invoke-RecoveryNative $git @('rev-parse','HEAD') $repoRestored).Trim() -eq $head) 'unpublished exact HEAD survives'
    MustThrow { Restore-RecoveryWorkspace $set $repoRestored $head $git } 'existing source workspace not overwritten'
    $verifiedSet=Join-Path $root 'verified-set'
    $release=Join-Path $verifiedSet 'pilot/releases/fixture'
    $null=New-Item -ItemType Directory -Force -Path (Join-Path $release 'web/assets')
    '<html>fixture</html>' | Set-Content (Join-Path $release 'web/index.html')
    $jar=[IO.Compression.ZipFile]::Open((Join-Path $release 'app.jar'),'Create')
    try {
        $entry=$jar.CreateEntry('BOOT-INF/classes/db/migration/V1__fixture.sql')
        $writer=[IO.StreamWriter]::new($entry.Open())
        try {$writer.Write('create table fixture(id int);')}finally{$writer.Dispose()}
    }finally{$jar.Dispose()}
    Write-PilotJson (Join-Path $release 'pilot-release.json') (New-PilotManifest $release $head)
    Write-PilotJson (Join-Path $verifiedSet 'pilot/release-state.json') @{current='fixture';previous=$null}
    Write-PilotJson (Join-Path $verifiedSet 'pilot/pilot-tools.json') @{filesEnabled=$false}
    [IO.File]::WriteAllText((Join-Path $verifiedSet 'pilot/application-pilot.yml'),'synthetic')
    $null=New-Item -ItemType Directory -Path (Join-Path $verifiedSet 'databases')
    [IO.File]::WriteAllText((Join-Path $verifiedSet 'databases/pilot.dump'),'fixture; not a database dump')
    Copy-Item (Join-Path $set 'workspace.bundle') (Join-Path $verifiedSet 'workspace.bundle')
    Copy-Item (Join-Path $set 'workspace.patch') (Join-Path $verifiedSet 'workspace.patch')
    Write-PilotJson (Join-Path $verifiedSet 'secrets.enc.json') $envelope
    Write-PilotJson (Join-Path $verifiedSet 'recovery-config.json') $config
    $verifiedManifest=@{version=1;kind='guitar-mentor-disaster-recovery';sourceHead=$head
        databases=@{pilot=@{libraryReady=@()}};files=(Get-RecoveryFileIndex $verifiedSet)}
    Write-PilotJson (Join-Path $verifiedSet 'gm-recovery-set.json') $verifiedManifest
    $null=Test-RecoverySet $verifiedSet
    Assert $true 'complete set/release manifest accepted'
    [IO.File]::WriteAllText((Join-Path $verifiedSet 'pilot/application-pilot.yml'),'corrupt')
    MustThrow { Test-RecoverySet $verifiedSet } 'corrupted config blocks activation'
    [IO.File]::WriteAllText((Join-Path $verifiedSet 'pilot/application-pilot.yml'),'synthetic')
    [IO.File]::WriteAllText((Join-Path $verifiedSet 'unexpected-file'),'unindexed')
    MustThrow { Test-RecoverySet $verifiedSet } 'unindexed files block activation'
    Remove-Item (Join-Path $verifiedSet 'unexpected-file')
    $verifiedManifest.files.Remove('workspace.patch')
    Remove-Item (Join-Path $verifiedSet 'workspace.patch')
    Write-PilotJson (Join-Path $verifiedSet 'gm-recovery-set.json') $verifiedManifest
    MustThrow { Test-RecoverySet $verifiedSet } 'incomplete payload blocked even with self-consistent hashes'
    if ($IsWindows) {
        $settings=Join-Path $root 'settings-original';New-PrivateRecoveryDirectory $settings
        $settingsFixture=@{clientId='synthetic';smtpHost='mail.invalid';smtpPort=587;smtpUser='synthetic';mailFrom='test@example.invalid'}
        foreach ($name in 'clientSecret','smtpSecret','databaseSecret','libraryCursorSecret') {
            $settingsFixture[$name]=ConvertTo-SecureString "fixture-$name" -AsPlainText -Force | ConvertFrom-SecureString
        }
        Write-PilotJson (Join-Path $settings 'local-auth-unified.json') $settingsFixture
        foreach ($name in 'pilot-database-password.txt','pilot-library-cursor.txt') {
            ConvertTo-SecureString "fixture-$name" -AsPlainText -Force | ConvertFrom-SecureString |
                Set-Content -LiteralPath (Join-Path $settings $name)
        }
        $portable=Export-RecoverySecrets $settings $true $password
        $unlocked=Unprotect-RecoverySecrets $portable $password
        $import=Join-Path $root 'settings-imported';Import-RecoverySecrets $unlocked $import
        $imported=Read-PilotJson (Join-Path $import 'local-auth-unified.json')
        foreach ($name in 'clientSecret','smtpSecret','databaseSecret','libraryCursorSecret') {
            Assert ((Unlock-PilotSecret $imported[$name]) -eq "fixture-$name") "DPAPI rewrap $name"
        }
        Assert ((Unlock-PilotSecret (Get-Content (Join-Path $import 'pilot-library-cursor.txt') -Raw)) -eq 'fixture-pilot-library-cursor.txt') 'pilot key survives portable export'
        Assert (-not (Get-Acl -LiteralPath $import).AreAccessRulesProtected -eq $false) 'recovered secrets have private ACL'
    }
    if ($ResticPath) {
        # Real encrypted repository and native restore. Local transport ONLY in tests.
        $repo=Join-Path $root 'restic-repo';$backup=Join-Path $root 'payload'
        Copy-RecoveryTree $source $backup @('node_modules')
        Write-PilotJson (Join-Path $backup 'gm-recovery-set.json') @{fixture=$true}
        $envVars=@{RESTIC_PASSWORD=$password}
        $null=Invoke-RecoveryNative $ResticPath @('--repo',$repo,'--no-cache','init') $root $envVars
        $out=Invoke-RecoveryNative $ResticPath @('--repo',$repo,'--no-cache','backup','--json','--tag','gm-dr-v1','.') $backup $envVars
        $summary=@($out -split '\r?\n' | Where-Object {$_} | ForEach-Object {$_ | ConvertFrom-Json -AsHashtable} | Where-Object {$_.message_type -eq 'summary'})[0]
        Assert ($summary.snapshot_id -match '^[a-f0-9]{64}$') 'real restic snapshot ID parsed'
        $lines=Invoke-RecoveryNative $ResticPath @('--repo',$repo,'--no-cache','ls','--json',$summary.snapshot_id) $root $envVars
        $marker=@($lines -split '\r?\n' | Where-Object {$_} | ForEach-Object {$_ | ConvertFrom-Json -AsHashtable} | Where-Object {$_.ContainsKey('name') -and $_.name -eq 'gm-recovery-set.json'})[0]
        $prefix=$marker.path.Substring(0,$marker.path.LastIndexOf('/'))
        $restored=Join-Path $root 'restored'
        $null=Invoke-RecoveryNative $ResticPath @('--repo',$repo,'--no-cache','restore',($summary.snapshot_id+':'+$prefix),'--target',$restored,'--verify') $root $envVars
        Assert ((Get-FileHash (Join-Path $restored 'binary.pdf')).Hash -eq (Get-FileHash (Join-Path $backup 'binary.pdf')).Hash) 'real restic exact subtree restore'
        $null=Invoke-RecoveryNative $ResticPath @('--repo',$repo,'--no-cache','check','--read-data') $root $envVars
        Assert $true 'real restic full encrypted data read'
    }
    Write-Host "PASS: $script:assertions recovery assertions. Real user cloud/DB/app drill NOT_RUN."
} finally {
    Remove-Item Env:PGSERVICE,Env:SPRING_DATASOURCE_PASSWORD,Env:RESTIC_PASSWORD_COMMAND -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $root -Recurse -Force
}
