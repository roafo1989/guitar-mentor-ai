#Requires -Version 7.4
param([string]$PostgresBin='C:\Program Files\PostgreSQL\17\bin')
. (Join-Path $PSScriptRoot '../Recovery.Common.ps1')
$root=Join-Path ([IO.Path]::GetTempPath()) ('gm-dr-pg-test-'+[guid]::NewGuid().ToString('N'))
New-PrivateRecoveryDirectory $root
$data=Join-Path $root 'pgdata'
$pwFile=Join-Path $root 'init-password'
$password='Synthetic test password only 123'
[IO.File]::WriteAllText($pwFile,$password+"`n")
$listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
$listener.Start();$port=([Net.IPEndPoint]$listener.LocalEndpoint).Port;$listener.Stop()
$started=$false
try {
    Write-Host 'Initializing a separate synthetic PostgreSQL cluster.'
    $null=Invoke-RecoveryNative (Join-Path $PostgresBin 'initdb.exe') @(
        '-D',$data,'--username=gm_dr_admin','--encoding=UTF8','--locale=C',
        '--auth-host=scram-sha-256',"--pwfile=$pwFile") $root
    # pg_ctl starts a daemon which can inherit redirected pipe handles on Windows.
    # Use the existing non-capturing process runner for daemon lifecycle commands.
    Write-Host 'Starting the separate PostgreSQL cluster.'
    Invoke-PilotTool (Join-Path $PostgresBin 'pg_ctl.exe') @('-D',$data,'-l',(Join-Path $root 'pg.log'),'-o',"-h 127.0.0.1 -p $port",'-w','start') $root
    $started=$true
    $admin=@{host='127.0.0.1';port=$port;name='postgres';user='gm_dr_admin'}
    $null=Invoke-RecoverySql $PostgresBin $admin $password @'
CREATE ROLE gm_dr_source LOGIN PASSWORD 'synthetic source credential';
CREATE DATABASE gm_dr_source OWNER gm_dr_source TEMPLATE template0;
'@
    $source=@{host='127.0.0.1';port=$port;name='gm_dr_source';user='gm_dr_source'}
    $null=Invoke-RecoverySql $PostgresBin $source 'synthetic source credential' @'
CREATE TABLE flyway_schema_history(installed_rank int PRIMARY KEY,version text,script text,checksum int,success boolean);
INSERT INTO flyway_schema_history VALUES (1,'1','V1__fixture.sql',42,true);
CREATE TABLE library_fixture(id int PRIMARY KEY, title text, content bytea);
INSERT INTO library_fixture VALUES (1,'Unicode: 音楽',decode('00ff0a0d','hex')),(2,'second',decode('abcd','hex'));
'@
    $dump=Join-Path $root 'source.dump'
    Write-Host 'Dumping the synthetic source database.'
    $summary=Backup-RecoveryDatabase $PostgresBin $source 'synthetic source credential' $dump
    if ($summary.counts.library_fixture -ne 2) { throw 'FAIL source count' }
    $target=@{host='127.0.0.1';port=$port;name='gm_dr_restored';user='gm_dr_restored'}
    $targetPassword="Synthetic quote ' Unicode пароль"
    Write-Host 'Restoring into a new role/database and checking counts and Flyway.'
    $restored=Restore-RecoveryDatabase $PostgresBin $dump $summary $target $targetPassword 'gm_dr_admin' $password
    if ($restored.counts.library_fixture -ne 2 -or $restored.flyway -ne $summary.flyway) { throw 'FAIL restored summary' }
    $bytes=(Invoke-RecoverySql $PostgresBin $target $targetPassword 'SELECT encode(content,''hex'') FROM library_fixture WHERE id=1;').Trim()
    if ($bytes -ne '00ff0a0d') { throw 'FAIL binary content' }
    $failed=$false
    try { $null=Restore-RecoveryDatabase $PostgresBin $dump $summary $target $targetPassword 'gm_dr_admin' $password } catch { $failed=$true }
    if (-not $failed) { throw 'FAIL overwrite existing role/database must fail' }
    $stillThere=(Invoke-RecoverySql $PostgresBin $target $targetPassword 'SELECT count(*) FROM library_fixture;').Trim()
    if ($stillThere -ne '2') { throw 'FAIL existing data changed' }
    $bad=$summary.Clone();$bad.counts=$summary.counts.Clone();$bad.counts.library_fixture=99
    $badTarget=@{host='127.0.0.1';port=$port;name='gm_dr_mismatch';user='gm_dr_mismatch'}
    $failed=$false
    try { $null=Restore-RecoveryDatabase $PostgresBin $dump $bad $badTarget $targetPassword 'gm_dr_admin' $password } catch { $failed=$true }
    if (-not $failed) { throw 'FAIL count mismatch must block successful drill' }
    Write-Host 'PASS: real isolated PostgreSQL dump/restore, all-table counts/Flyway, binary bytes, quoted password, overwrite refusal, mismatch refusal.'
} finally {
    $stopped=$true
    if ($started) {
        try { Invoke-PilotTool (Join-Path $PostgresBin 'pg_ctl.exe') @('-D',$data,'-m','fast','-w','stop') $root } catch { $stopped=$false }
    }
    if ($stopped) { Remove-Item -LiteralPath $root -Recurse -Force }
    else { throw "Test PostgreSQL still running; preserved: $root" }
}
