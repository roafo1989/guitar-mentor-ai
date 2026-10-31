#Requires -Version 7.4
param(
    [Parameter(Mandatory)][string]$RecoveryRoot,
    [string]$DestinationRoot
)
. (Join-Path $PSScriptRoot 'Recovery.Common.ps1')
$RecoveryRoot=Expand-RecoveryPath $RecoveryRoot
$null=Test-RecoverySet $RecoveryRoot
$config=Read-PilotJson (Join-Path $RecoveryRoot 'recovery-config.json')
$targets=@()
foreach ($folder in $config.extraFolders) {
    $path=if ($DestinationRoot) { Join-Path (Expand-RecoveryPath $DestinationRoot) $folder.id } else { Expand-RecoveryPath $folder.path }
    if (Test-Path -LiteralPath $path) { throw "Не перезаписываю существующий каталог: $path" }
    $targets+=@{id=$folder.id;path=$path}
}
foreach ($target in $targets) {
    New-PrivateRecoveryDirectory $target.path
    Copy-RecoveryTree (Join-Path $RecoveryRoot "extra/$($target.id)") $target.path
    Write-Host "Восстановлен дополнительный каталог: $($target.path)"
}
