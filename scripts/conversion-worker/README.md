# Isolated Guitar Pro conversion worker (170-B)

This image is the only supported execution boundary for the alphaTab conversion process. It accepts only the approved initial subset:

- GP3 `FICHIER GUITAR PRO v3.00`
- GP4 `FICHIER GUITAR PRO v4.06`
- GP5 `FICHIER GUITAR PRO v5.10`
- One selected guitar staff, standard EADGBE, 4/4, quarter-note monophonic material, no rests, repeats, tempo changes, capo, alternate tuning, or supported-effect deviations.

The format is checked from the embedded version header, not inferred from a filename or caller-supplied extension. alphaTab then parses the binary content and the worker applies the supported-subset checks before rendering.

## Build

From repository root, after installing the locked CATALOG-169 Node dependencies:

```powershell
npm ci --prefix scripts/research/catalog-169 --omit=dev --ignore-scripts --no-audit --no-fund
docker build -f scripts/conversion-worker/Dockerfile -t gm-conversion-worker:170b .
```

The Dockerfile uses the existing reviewed lockfile and pinned Node 22.16.0 base image. Do not use floating package versions or `docker pull` at job execution time. Java starts the already-installed image with `--pull=never`.

## Container contract

- Input: `/input/source.gp`, mounted read-only.
- Output: `/out/score.pdf`, `/out/preview.wav`, `/out/result.json`.
- Environment: `GM_CONVERSION_FORMAT=GP3|GP4|GP5`; optional `GM_CONVERSION_TRACK_INDEX`.
- Exit 0: both PDF and WAV rendered from the same imported score and selected track.
- Exit 10: `TRACK_SELECTION_REQUIRED`; result JSON contains safe guitar-track metadata.
- Exit 20: deterministic unsupported/invalid/render error code in result JSON.
- No owner data, secrets, source paths, or raw stack traces are returned by the worker.

The Java adapter supplies a read-only root, no network, unprivileged UID 10001, dropped Linux capabilities, no-new-privileges, 512 MiB RAM, 1 CPU, 64 PIDs, a 100 MiB noexec/nosuid tmpfs and a 60-second watchdog. The output bind mount is the only writable persistent mount; the container's own writable layer is not used. The Java adapter verifies source SHA-256 before launch and validates PDF/WAV signatures and output size after exit.

## Operational boundary

The Java runner is an execution adapter, not yet the durable queue consumer. It returns a private temporary workspace to its caller; 170-C must atomically persist the output pair as private derived assets and delete that workspace on every outcome. Until 170-C wires this result to the lease lifecycle and 170-E validates deployment/security gates, do not enable the conversion feature flag or expose conversion to users.

## Verification

`.github/workflows/conversion-worker-170b.yml` generates original synthetic GP3/4/5 files, builds the image and runs each format inside the actual resource-limited container. It also verifies the track-selection handoff and Java ProcessBuilder adapter contract. No user or third-party scores are used.

Keep the alphaTab MPL-2.0, Bravura SIL OFL 1.1 and SONiVOX SoundFont Apache-2.0 notices supplied by the pinned dependency distribution when redistributing the image.
