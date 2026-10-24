#Requires -Version 7.0
<#
.SYNOPSIS
  One-time setup: .\scripts\local-auth-unified.ps1 -Setup
  Subsequent launches: .\scripts\local-auth-unified.ps1
.DESCRIPTION
  Runs ONLY on this Windows user workstation. Credentials are encrypted with
  Windows DPAPI via ConvertFrom-SecureString, outside the git checkout.
  Two dedicated PowerShell windows are launched for backend and frontend.
#>
param([switch]$Setup)
$ErrorActionPreference = 'Stop'
if (-not $IsWindows) { throw 'Windows/DPAPI is required for this local launcher.' }

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$settingsDirectory = Join-Path $env:LOCALAPPDATA 'GuitarMentorAI'
$settingsPath = Join-Path $settingsDirectory 'local-auth-unified.json'
function UnwrapSecret([string]$ciphertext) {
    if ([string]::IsNullOrWhiteSpace($ciphertext)) { throw 'Missing encrypted credential.' }
    $value = ConvertTo-SecureString $ciphertext
    return [System.Net.NetworkCredential]::new('', $value).Password
}
function AskSecret([string]$prompt) {
    $value = Read-Host $prompt -AsSecureString
    if ($value.Length -eq 0) { throw "Missing required secret: $prompt" }
    return ConvertFrom-SecureString $value
}
if ($Setup) {
    New-Item -ItemType Directory -Force -Path $settingsDirectory | Out-Null
    $clientId = Read-Host 'Google OAuth Web Client ID'
    $smtpHost = Read-Host 'SMTP hostname (provider-specific)'
    $smtpPort = Read-Host 'SMTP STARTTLS port [587]'
    if ([string]::IsNullOrWhiteSpace($smtpPort)) { $smtpPort = '587' }
    $smtpUser = Read-Host 'SMTP username'
    $mailFrom = Read-Host 'Verified sender email address'
    if ([string]::IsNullOrWhiteSpace($clientId) -or
        [string]::IsNullOrWhiteSpace($smtpHost) -or
        [string]::IsNullOrWhiteSpace($smtpUser) -or
        [string]::IsNullOrWhiteSpace($mailFrom) -or
        $smtpPort -notmatch '^\d{2,5}$') {
        throw 'Client ID, SMTP host/user/from and valid port are required.'
    }
    $config = @{
        clientId = $clientId.Trim()
        clientSecret = AskSecret 'Google OAuth Client Secret'
        smtpHost = $smtpHost.Trim()
        smtpPort = [int]$smtpPort
        smtpUser = $smtpUser.Trim()
        smtpSecret = AskSecret 'SMTP credential / app password'
        mailFrom = $mailFrom.Trim()
        databaseSecret = AskSecret 'PostgreSQL guitar_mentor_app password'
    }
    $config | ConvertTo-Json | Set-Content -Path $settingsPath -Encoding utf8
    Write-Host 'Encrypted credentials stored for this Windows account in LOCALAPPDATA.'
    Write-Host 'The same Windows user can decrypt them. Do not share your Windows account.'
    exit 0
}
if (-not (Test-Path -LiteralPath $settingsPath)) {
    throw "Run .\scripts\local-auth-unified.ps1 -Setup first."
}
# Start the existing local HTTPS reverse proxy BEFORE decrypting any secrets.
# A Caddy process must never inherit Google/SMTP/database credentials.
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$caddyConfig = Join-Path $projectRoot 'infra\local\Caddyfile'
$caddyExe = 'C:\Tools\Caddy\caddy.exe'
$caddyListening = Get-NetTCPConnection -LocalAddress '127.0.0.1' -LocalPort 443 -State Listen -ErrorAction SilentlyContinue
if (-not $caddyListening) {
    if (-not (Test-Path -LiteralPath $caddyExe)) {
        throw 'Caddy HTTPS server not running. Install Caddy in C:\Tools\Caddy or start it manually.'
    }
    if (-not (Test-Path -LiteralPath $caddyConfig)) { throw 'Missing local Caddyfile.' }
    # Explicitly mask any credentials inherited from a parent shell before
    # spawning Caddy; restore the original environment immediately afterward.
    $hiddenVariables = @('GUITAR_MENTOR_OIDC_CLIENT_SECRET', 'GUITAR_MENTOR_SMTP_PASSWORD',
        'SPRING_DATASOURCE_PASSWORD', 'GUITAR_MENTOR_REVIEW_TOKEN')
    $saved = @{}
    try {
        foreach ($name in $hiddenVariables) {
            $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
            [Environment]::SetEnvironmentVariable($name, $null, 'Process')
        }
        Start-Process -FilePath $caddyExe -WorkingDirectory $projectRoot -ArgumentList @(
            'run', '--config', ('"' + $caddyConfig + '"'), '--adapter', 'caddyfile'
        )
    } finally {
        foreach ($name in $hiddenVariables) {
            [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process')
        }
    }
    Write-Host 'Started local Caddy HTTPS proxy for https://guitar-mentor.'
}
$conf = Get-Content -LiteralPath $settingsPath -Raw | ConvertFrom-Json
$env:GUITAR_MENTOR_OIDC_CLIENT_ID = [string]$conf.clientId
$env:GUITAR_MENTOR_OIDC_CLIENT_SECRET = UnwrapSecret $conf.clientSecret
$env:GUITAR_MENTOR_SMTP_HOST = [string]$conf.smtpHost
$env:GUITAR_MENTOR_SMTP_PORT = [string]$conf.smtpPort
$env:GUITAR_MENTOR_SMTP_USER = [string]$conf.smtpUser
$env:GUITAR_MENTOR_SMTP_PASSWORD = UnwrapSecret $conf.smtpSecret
$env:GUITAR_MENTOR_MAIL_FROM = [string]$conf.mailFrom
$env:SPRING_DATASOURCE_PASSWORD = UnwrapSecret $conf.databaseSecret
$env:GUITAR_MENTOR_SMTP_AUTH = 'true'
$env:GUITAR_MENTOR_SMTP_STARTTLS = 'true'
$env:GUITAR_MENTOR_SECURITY_ENABLED = 'true'
$env:GUITAR_MENTOR_BIND_ADDRESS = '127.0.0.1'
$env:GUITAR_MENTOR_PUBLIC_ORIGIN = 'https://guitar-mentor'
# The accepted Light Minimal shell (#148) and Home (#149) are the default UI.
# Developers can explicitly opt out with $env:VITE_GUITAR_MENTOR_LIGHT_MINIMAL = 'false'.
# Vite reads the flag when its process starts; restarting Vite is necessary.
if ([string]::IsNullOrWhiteSpace($env:VITE_GUITAR_MENTOR_LIGHT_MINIMAL)) {
    $env:VITE_GUITAR_MENTOR_LIGHT_MINIMAL = 'true'
}
$env:VITE_GUITAR_MENTOR_AUTH_REQUIRED = 'true'
$env:VITE_GUITAR_MENTOR_OAUTH_BRIDGE = 'true'
$env:SPRING_PROFILES_ACTIVE = 'secure,local-auth,local-unified'

$backend = Join-Path $root 'backend'
$frontend = Join-Path $root 'frontend'
if (-not (Test-Path (Join-Path $backend 'mvnw.cmd')) -or
    -not (Test-Path (Join-Path $frontend 'package.json'))) {
    throw 'Launcher must run from Guitar Mentor AI checkout.'
}
# Fail early rather than silently using a stale process from a different profile.
foreach ($port in 8080,5173) {
    if (Get-NetTCPConnection -LocalAddress '127.0.0.1' -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
        throw "Port $port is already in use. Stop old backend/frontend before restarting."
    }
}
# VS Code and Store-installed PowerShell may inherit a stale/partial PATH.
# Refresh executable search paths from Windows user/machine configuration without
# printing or persisting the secrets held in this launcher process.
$machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
$extraPaths = @(
    (Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0'),
    (Join-Path $env:ProgramFiles 'nodejs')
)
$segments = @($env:Path, $machinePath, $userPath) + $extraPaths
$env:Path = ($segments | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join ';'

$missing = @()
foreach ($tool in @('java.exe', 'node.exe', 'npm.cmd')) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { $missing += $tool }
}
if ($missing.Count -gt 0) {
    throw "Missing command(s): $($missing -join ', '). Check installation and Windows PATH. Reopen VS Code after installing Java/Node.js."
}

# Store-packaged pwsh.exe can start as an App Execution Alias with a different
# environment. Spawn the built-in Windows PowerShell directly: it inherits this
# process's corrected PATH and all SMTP/OAuth variables.
$ps = Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe'
if (-not (Test-Path -LiteralPath $ps)) {
    throw "Windows PowerShell executable not found: $ps"
}
$npm = (Get-Command npm.cmd -ErrorAction Stop).Source
$node = (Get-Command node.exe -ErrorAction Stop).Source
if (-not (Test-Path -LiteralPath $node)) {
    throw "Node.js executable not found: $node"
}
# Native .cmd wrappers rely on PATH: Maven Wrapper invokes powershell.exe,
# npm.cmd invokes node.exe. Fail in the child before running the app when PATH
# is broken. Do not include secret values in commands or logs.
$backendCommand = "Set-Location -LiteralPath '$backend'; if (-not (Get-Command powershell.exe -ErrorAction SilentlyContinue)) { throw 'Child PATH missing powershell.exe' }; & '.\mvnw.cmd' spring-boot:run"
$frontendCommand = "Set-Location -LiteralPath '$frontend'; if (-not (Get-Command node.exe -ErrorAction SilentlyContinue)) { throw 'Child PATH missing node.exe' }; & '$npm' run dev"
Start-Process -FilePath $ps -ArgumentList @('-NoExit', '-Command', $backendCommand)
Start-Process -FilePath $ps -ArgumentList @('-NoExit', '-Command', $frontendCommand)
Write-Host 'Started private backend and Vite terminals. App address: https://guitar-mentor/'
Write-Host 'Keep existing Caddy running with infra/local/Caddyfile for HTTPS and its local certificate.'
Write-Host 'OAuth callback must match http://localhost:5173/login/oauth2/code/guitar-mentor.'
Write-Host 'No SMTP test-capture process is needed. Email delivery depends on your provider.'
