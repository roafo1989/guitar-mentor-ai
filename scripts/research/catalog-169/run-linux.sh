#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$ROOT"
IMAGE=gm169-research:local
OUT="$ROOT/artifacts/catalog-169"
mkdir -p "$OUT"
chmod 777 "$OUT"
docker build -f scripts/research/catalog-169/Dockerfile -t "$IMAGE" .
NAME="gm169-$$"
docker run --name "$NAME" --user 1000:1000 --network none --memory 512m --memory-swap 512m --cpus 1 --pids-limit 64 --read-only --tmpfs /tmp:rw,size=100m --mount "type=bind,src=$OUT,dst=/out" "$IMAGE" &
PID=$!
(sleep 60; docker kill "$NAME" >/dev/null 2>&1 || true) &
WATCHDOG=$!
: > "$OUT/container-samples.txt"
while kill -0 "$PID" 2>/dev/null; do
  docker stats --no-stream --format '{{.MemUsage}}|{{.CPUPerc}}' "$NAME" >> "$OUT/container-samples.txt" 2>/dev/null || true
  sleep 0.25
done
set +e
wait "$PID"
STATUS=$?
set -e
kill "$WATCHDOG" >/dev/null 2>&1 || true
docker inspect "$NAME" --format 'exit={{.State.ExitCode}} oomKilled={{.State.OOMKilled}} memoryBytes={{.HostConfig.Memory}} nanoCpus={{.HostConfig.NanoCpus}} network={{.HostConfig.NetworkMode}}' > "$OUT/container-limits.txt"
docker rm "$NAME" >/dev/null
exit "$STATUS"
