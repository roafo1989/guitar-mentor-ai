#Requires -Version 7.0
[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$mvn=Join-Path $root 'backend/mvnw.cmd'
function PgTool($name) {
  $found=Get-Command ($name+'.exe') -ErrorAction SilentlyContinue
  if($found){return $found.Source}
  $base=Join-Path $env:ProgramFiles 'PostgreSQL'
  if(Test-Path $base) {
    foreach($d in (Get-ChildItem $base -Directory | Sort-Object Name -Descending)) {
      $p=Join-Path $d.FullName ('bin/'+$name+'.exe')
      if(Test-Path $p){return $p}
    }
  }
  return $null
}
$psql=PgTool 'psql'
if(-not $psql){Write-Host 'SKIP: PostgreSQL client psql.exe unavailable';exit 2}
if(-not(Test-Path $mvn)){throw 'Maven wrapper missing'}
$user=if($env:QA216_PGUSER){$env:QA216_PGUSER}else{'postgres'}
$port=if($env:QA216_PGPORT){$env:QA216_PGPORT}else{'5432'}
if($user -notmatch '^[a-zA-Z_][a-zA-Z0-9_]*$' -or $port -notmatch '^[0-9]{2,5}$' -or [int]$port -gt 65535) {throw 'Invalid QA PostgreSQL connection setting'}
$db='qa216_'+[guid]::NewGuid().ToString('N').Substring(0,16)
$marker=[guid]::NewGuid().ToString('N')
$keys=@('PGHOST','PGPORT','PGUSER','PGPASSWORD','PGCONNECT_TIMEOUT','SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD','GUITAR_MENTOR_TEST_POSTGRES')
$old=@{}
foreach($key in $keys){$old[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
$created=$false
$marked=$false
function SQL($statement,$database='postgres') {
  $value=& $psql -X -w -v ON_ERROR_STOP=1 -A -t -d $database -c $statement 2>&1
  if($LASTEXITCODE -ne 0){throw 'PostgreSQL operation failed; check local server and credentials'}
  return ($value | Out-String).Trim()
}
try{
  $env:PGHOST='127.0.0.1';$env:PGPORT=$port;$env:PGUSER=$user;$env:PGCONNECT_TIMEOUT='4'
  if($env:QA216_PGPASSWORD){$env:PGPASSWORD=$env:QA216_PGPASSWORD}
  elseif(-not $env:PGPASSWORD){
    $secret=Read-Host "Local PostgreSQL password for $user (not stored)" -AsSecureString
    $env:PGPASSWORD=ConvertFrom-SecureString -SecureString $secret -AsPlainText
  }
  $identity=SQL 'SELECT current_database() || chr(58) || current_user'
  if($identity -ne ('postgres:'+ $user)){throw 'Expected loopback maintenance database and admin user; refused'}
  SQL "CREATE DATABASE $db" | Out-Null
  $created=$true
  if((SQL 'SELECT current_database()' $db) -ne $db){throw 'New QA database identity mismatch'}
  SQL "COMMENT ON DATABASE $db IS '$marker'" | Out-Null
  $marked=$true
  $env:SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:$port/$db"
  $env:SPRING_DATASOURCE_USERNAME=$user
  $env:SPRING_DATASOURCE_PASSWORD=$env:PGPASSWORD
  $env:GUITAR_MENTOR_TEST_POSTGRES='true'
  Write-Host "QA: disposable PostgreSQL database $db on loopback"
  Push-Location (Join-Path $root 'backend')
  try{
    & $mvn -B '-Dtest=NextLessonPostgresIntegrationTest,SecurityHttpIntegrationTest' test
    $code=$LASTEXITCODE
  }finally{Pop-Location}
  if($code -ne 0){throw "Integration tests failed with exit code $code"}
  Write-Host 'PASS: isolated PostgreSQL integration suite'
  exit 0
}finally{
  if($created){
    try{
      if($marked){
        $actual=SQL "SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname='$db'"
        if($actual -ne $marker){throw 'Cleanup marker mismatch'}
        SQL "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='$db' AND pid<>pg_backend_pid()" | Out-Null
        SQL "DROP DATABASE $db" | Out-Null
        Write-Host "Removed temporary QA database $db"
      }else{Write-Warning "Temporary database $db created but not verified; manual cleanup needed"}
    }catch{Write-Warning "Could not safely remove temporary database $db; no other database touched"}
  }
  foreach($key in $keys){[Environment]::SetEnvironmentVariable($key,$old[$key],'Process')}
}
