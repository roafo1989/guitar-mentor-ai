#!/bin/sh
set -eu
mkdir -p /scratch/tmp /scratch/out
# Independent of the Java parent: TERM at 59s; unconditional KILL by 60s.
exec timeout --signal=TERM --kill-after=1 59 node /opt/converter/worker.mjs
