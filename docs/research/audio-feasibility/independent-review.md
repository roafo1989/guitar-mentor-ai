# #97 — independent musical review and REAL-corpus acceptance gate (formerly #14 real-data scope)

This procedure is for an **offline, private** research package only. Do not put real recordings, photos of scores, performer names, listening decisions, measured individual timestamps, hashes of private source files, or completed annotation JSON in public GitHub.

## Two distinct evidence levels

1. **Signal-derived candidate (machine-produced):** onset strength, silence/energy, spectral bands, MIDI/chroma likeness, tempo grid or dynamic-time-warped correspondence. These may prioritize listening but cannot establish the source of a transient, a played note, a missed beat, or an intentional mistake. Mark as UNREVIEWED / AMBIGUOUS, never MATCH/WRONG_BASS without confirmation.
2. **Human-verified observation (ground truth):** identifiable note or rest in an approved versioned score, exact passage boundary, time window of the corresponding performed event, audible evidence and an independently reviewed label. Only this can set `manualConfirmed` for the annotation.

A recorded metronome is an **independent time reference**, not a guitar onset. Manual reviewers must separate metronome ticks from string attacks, and preserve MIXED/UNKNOWN where sounds overlap. Sheet tempo printing and actual rehearsal tempo are different fields; never silently substitute one for the other.

## Suggested workflow

1. Keep master recordings immutable outside Git and outside product storage. Verify original/decoded hashes and ffprobe stream properties before and after review. Decode only for local research and retain the transform/provenance, including source hash.
2. Obtain scope-limited rights and consent assertions before corpus acceptance; personal guitar recordings do **not** automatically grant rights to republish the composition or photographed arrangement. Keep all score derivatives private unless explicitly cleared.
3. Freeze score version/hash, tuning, section and exact bars played. Mark count-in and recording delay separately from musical bar 1. Transcribe the minimum reference events (bar, beat, duration, string/pitch, BASS/UPPER) with a second-pass check of the image/source. Do not assign ideal timestamps as observations.
4. Listen to each take **without algorithm-generated error labels** for a first pass; record raw time-coded observations and uncertainties. Then compare candidates and investigate conflicting events. Ask a separate independent reviewer to resolve ambiguous bass notes, missing notes, stops and tempo changes where feasible.
5. When an error was announced but its location is not known, record `expectedIntent=WRONG_BASS` as metadata outside the observed annotation; do not place a false `WRONG_BASS` event into the measurement denominator. Spectral divergence between two takes is only a search hint, even if DTW alignment is strong.
6. Distinguish musical silence/rest, decaying strings, background sound, and a true unexpected break in playing. An interval between detector onsets alone does not prove a PAUSE. Tempo change must be estimated from guitar beat events, not assumed from metronome clicks or printed sheet tempo.
7. Log uncertainty (UNKNOWN/AMBIGUOUS/abstain), reviewer conflicts and adjudication. Require each confirmed event's `eventId` to exist in the immutable reference and its time range to fit within the corresponding recording.
8. Confirm six independent **performances**: two per REAL-BASS / REAL-TIMING / REAL-POLY. Split CALIBRATION/HOLDOUT by independent session/performance lineage; recoded WAV/M4A variants and excerpts of one performance must remain in the same group. A matching file hash alone cannot prove independence.
9. Fill the real private schema with actual source/reference/annotation hashes and reviewed rights; invoke `AudioCorpusValidator` on the approved offline manifest, store a redacted report and repeat to confirm stable output and unchanged media hashes.
10. Verify [#97](https://github.com/roafo1989/guitar-mentor-ai/issues/97) AC-REAL-01…08 against the real corpus before its acceptance, separately from #14's technical T14-01…06 checks. `READY_FOR_POC` means structurally and manually ready to benchmark in #15; it is **not** a statement of model accuracy or musical competence.

## Acceptance matrix

| Check | Can be verified with code only? | Required proof |
|---|---|---|
| Integrity, format, duration, path safety, JSON structural validity | Yes | ffprobe + SHA-256 + offline validator reports |
| Distinct physical takes and holdout independence | No | recording/session provenance and reviewer attestation |
| Rights to use audio and score for the intended scope | No | scope-limited source/consent evidence |
| Count-in / selected bars / note-event references | No | visual score check and listening alignment |
| Actual bass errors and timing deviations | No | independent audio-by-score review with timestamps |
| TRUE / FALSE performance metrics | No, not in #97 or technical #14 | separate #15 blinded benchmark on approved holdout |

If any manual proof is absent, the **REAL corpus** remains `INCOMPLETE` and #97 stays OPEN. This must not block code review/testing of technical #14 after the [approved split](scope-decision-2026-10-20.md). Technical PR merging and #14 closure still require their own code review, tests and owner acceptance. Notation photos and musical examples are private references, not public test fixtures.

## Audit trail

Keep a private reviewer decision log with recording pseudonym, clip boundaries, reference revision, reviewer, initial/reconciled label, reason for uncertainty, consent scope and content hashes. The log must not use the automatic `manualConfirmed` shortcut, nor confuse an AI-assisted candidate with independent human confirmation.
