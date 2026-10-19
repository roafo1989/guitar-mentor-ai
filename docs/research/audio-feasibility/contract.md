# Benchmark contract v1

All JSON is UTF-8; unknown or invalid enum values are rejected. Identifiers are opaque, non-identifying ASCII tokens; no actual names or original filenames. UTC timestamps are ISO-8601. SHA-256 hashes are lowercase 64-digit hex. The authoritative specification is [#14](../../specifications/MVP-R2-01-audio-feasibility.md).

## Dataset manifest
`schemaVersion: 1`, `datasetId`, immutable `datasetVersion`, `createdAt`, `sourceCommit`, `recordings[]`. Each recording:
- `recordingId` unique; `kind`: REAL | SYNTHETIC | QUALITY; `group`: REAL-BASS | REAL-TIMING | REAL-POLY | SYNTHETIC-CONTROL | QUALITY-CONTROL.
- `recordingGroupId`, `performerId`, `sessionId`, `deviceId`: pseudonyms for REAL. Derivative files and edits share `recordingGroupId`.
- `fileRef` relative to explicit offline root; `sourceSha256`, `bytes`, `durationMs`, `sampleRateHz`, `channels` describe actual bytes, not declared file extensions.
- `referenceId`, `referenceVersion`, `referenceSha256`, `barFrom`, `barTo` identify an immutable score/reference; `annotationRef`, `annotationVersion`, `annotationSha256` identify manual observation for ready records.
- `rightsBasis`: OWNED | LICENSED | EXPLICIT_PERMISSION; `consentRef` is an opaque local consent record ID (not a URL); `useScope`: LOCAL_RESEARCH | EXTERNAL_APPROVED; no inference of publication permission.
- `split`: CALIBRATION | HOLDOUT | DIAGNOSTIC_ONLY; `availability`: AVAILABLE | MISSING | WITHDRAWN; `transform` required for SYNTHETIC and converted derivatives (source reference/hash, operation, parameters, seed where relevant, tool version).

## Reference
`referenceId`, `referenceVersion`, `origin`, `rights`, `instrument`, `tuning`, `meterNumerator`, `meterDenominator`, `bpm`, `barCount`, `events[]`. Event: `eventId` unique, `bar` 1-based, `beat` 0-based, `durationBeats` > 0, `voice` BASS | UPPER | MIXED and `notes[]`. A simultaneous chord is **one attack** with multiple notes; no artificial retrigger from string decay.

A notated event at bar b and beat q has ideal time `((b-1)*meterNumerator + q)*60000/bpm` ms for a quarter-note beat in 4/4. This is **not** a manually observed recording timestamp. Explicit pickup, count-in, rests, and initial silence are described separately.

## Annotation
`annotationId`, `annotationVersion`, `recordingId`, `sourceSha256`, `annotatorId`, nullable `reviewedBy`, `observations[]`. Observation: `eventId` (nullable only for EXTRA), `onsetMs`, `fromMs`, `toMs`, `voice` BASS | UPPER | MIXED | UNKNOWN, `label` MATCH | OMISSION | EXTRA | WRONG_BASS | PAUSE | TEMPO_CHANGE | AMBIGUOUS, `actualNotes` (or UNKNOWN where pitch cannot be established), optional interval `endMs` for pause/tempo change. `0 <= fromMs <= onsetMs <= toMs <= durationMs`; interval end must not precede start.

A notated rest is not a PAUSE. An intended mistake is not a measured mistake until listening confirms it. UNKNOWN and AMBIGUOUS remain explicit instead of inventing a pitch.

## Security rules
Reject URI/scheme strings, absolute paths (POSIX, Windows, UNC), any `..` segment, symbolic links (including parent components), paths outside the root, hash mismatch, duplicate IDs, dangling event references and annotation timestamps outside duration. Check source bytes before and after processing. Do not follow arbitrary remote content. Limit media to 20 MiB and 120 seconds; ffprobe metadata alone does not prove a note is correct.

## Readiness status
`INVALID`: unsafe reference, malformed schema, damaged/hash-mismatched source, inconsistent event/time fields. `INCOMPLETE`: missing/withdrawn source, absent/unconfirmed rights or annotations, insufficient independent REAL coverage. `READY_FOR_POC`: all structural checks and manual attestation flags exist, six+ independent REAL takes are present, and independent CALIBRATION/HOLDOUT coverage holds. This means **ready to measure**, not “accurate”.
