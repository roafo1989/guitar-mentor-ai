# GM-EB05 — Midnight Train Blues (Call and Response)

Current **musical revision v1.1.0**. The older 1.0.1 is retained in Git for traceability and is *not* the version proposed for musical acceptance.

- Meter **4/4**, **96 BPM**, swung eighth notes (long-short, approximately 2:1).
- Acoustic guitar EADGBE; fingerstyle **p-i-m-a**, no pick or percussion.
- 12-bar harmonic form: `E7 E7 A7 E7 | A7 A7 E7 C#7 | F#m7 B7 E7 E7`. C#7 functions as V/ii.
- Blues inflection, chromatic passing color, rhythmic space, call and response, sustained E ending.
- Source **ORIGINAL**, license **CC BY 4.0**.

## Current source of truth

- `midnight_train_blues_v1_1.musicxml`: pitches, durations, independent voices and chords.
- `score_events_v1_1.json`: exact zero-based beat onset/length events.
- `metadata_v1_1.json`: goals, credits, license and manual acceptance status.
- `python3 scripts/validate_gm_eb05_v11.py`: deterministic XML-to-JSON consistency check.

The PDF with standard notation above tablature, a MIDI file and synthesized WAV preview were generated as chat deliverables for this revision; they are not checked into this source-only branch. Their timbre is not a real guitar performance. Actual musical appeal and physical playability remain **PENDING_OWNER**. Existing v1 files remain historical and should not be assigned in place of v1.1 without explicit version selection. Database/API integration belongs to #84.

Refs #80.
