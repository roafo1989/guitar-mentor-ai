$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$image = 'gm169-research:local'
$out = Join-Path $root 'artifacts/catalog-169'
New-Item -ItemType Directory -Force -Path $out | Out-Null
Push-Location $root
try {
  docker build -f scripts/research/catalog-169/Dockerfile -t $image .
  $name = 'gm169-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
  $outMount = $out.Replace('\', '/')
  $process = Start-Process -FilePath docker -ArgumentList @(
    'run', '--name', $name, '--user', '1000:1000', '--network', 'none',
    '--memory', '512m', '--memory-swap', '512m', '--cpus', '1',
    '--pids-limit', '64', '--read-only', '--tmpfs', '/tmp:rw,size=100m',
    '--mount', "type=bind,src=$outMount,dst=/out", $image
  ) -PassThru -NoNewWindow
  if (-not $process.WaitForExit(60000)) {
    docker kill $name | Out-Null
    throw 'CATALOG_169_TIMEOUT_60_SECONDS'
  }
  docker inspect $name --format 'exit={{.State.ExitCode}} oomKilled={{.State.OOMKilled}} memoryBytes={{.HostConfig.Memory}} nanoCpus={{.HostConfig.NanoCpus}} network={{.HostConfig.NetworkMode}}' | Set-Content -Encoding utf8 (Join-Path $out 'container-limits.txt')
  docker rm $name | Out-Null
  if ($process.ExitCode -ne 0) { throw "CATALOG_169_RUN_FAILED_EXIT_$($process.ExitCode)" }
}
finally { Pop-Location }
