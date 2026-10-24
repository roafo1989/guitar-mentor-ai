# FT-016-04 / Issue #151 — media capture coordination

Implementation branch: `feature/issue-151-media-coordination`. Parent #147, specification `docs/specifications/UX-02-04-media-tools.md` (analysis SHA `7be8d405`). Based on latest main including completed #198. No API, database, authorization or new audio engine introduced.

## Changes

- The existing assigned-version sample and local draft preview pause each other. Capture state `{contextKey, active}` pauses the sample before microphone permission and prevents sample playback throughout capture. No sample auto-resume. Unavailable audio has a localized message and manual retry.
- Starting capture retains the previous File/comment/requestId until a valid `recorded(File)` replaces the attachment. Denied, cancelled and invalid recordings leave the draft intact; Stop does not submit.
- POST uses a snapshot `{file, comment, requestId}`. An unchanged manual retry retains its key; subsequent user editing rotates the key. The old context's `finally` cannot clear new upload busy.
- Inline BPM reads the same singleton engine after metronome notifications; rejected BPM writes do not pretend success. Reopening the same exercise, even after Stop, retains adjusted settings.
- Existing audio-session lease gives recorder priority over tuner, and the metronome remains available during capture.

## Verification and QA

CI workflow `Vue frontend CI` is authoritative for current head; previous failed runs are not evidence of a passing revision. Regression coverage includes mounted draft preservation and idempotency-key tests in `frontend/tests/attempt-pagination.test.mjs`, plus existing metronome, audio-session, intake, playback and score contracts.

Manual UAT is still required on Chrome/Edge with a physical microphone, real session/attempt API, permission-denied and permission-granted flows, browser back/logout, dark-room monitoring, keyboard and 200% zoom. These cases cannot be declared PASS based on fake streams or Node tests.

### Owner checklist

1. Open current assigned exercise in Light Minimal, play example, start recording; example stops, permitted metronome continues, example cannot restart while recording.
2. With an existing draft, deny mic permission or cancel: file/comment remain; then make a valid recording and confirm only then the file is replaced.
3. Preview recording then play example (and reverse); only one sounds. Save comment, upload; inspect single attempt in history.
4. Reproduce timeout and retry without edits (single idempotent intent), then edit comment and resend (new intent).
5. Set 3/8 eighth-note tempo, edit inline BPM, open header dialog, stop/reopen, confirm settings and accents remain; run 6/8 separately.
6. Start tuner then recorder, and attempt to start tuner during recorder; no second mic stream or interruption. Test Back, cancel discard, logout and actor change.
7. Run legacy/Light, RU/EN, viewport 360px and 200% zoom. Confirm Stop remains visible and tab/keyboard interactions work.

No owner acceptance, merge or issue closure is implied by this document. Rollback: revert PR or deploy previous frontend build; a local draft is not durable across reload.
