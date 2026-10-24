# GM-DIAG-PULSE-3-8-01 · technical diagnostic probe (draft)

**Status:** NON-PUBLISHABLE — version `0.1.0`, original procedural finger exercise.
**Learning purpose:** observe whether the student maintains an even three-eighth beat and consistently accentuates beat one. Not a musical composition or a mandatory test for every learner.

## Exact source and performing instructions
- Standard guitar tuning, open G3 / B3 / E4 on strings 3, 2 and 1.
- 3/8, 12 measures, 36 eighth notes, 100 **eighth-notes** per minute.
- Playing time 21.6 seconds excluding count-in (three eighth notes = 1.8 s).
- Right hand: i / m / a. **No pick or percussion**.
- Repeat an identical three-note technical pattern; focus on grouping, timing, and hand independence, not artistic interest.
- `score.musicxml` uses standard guitar transposition: written one octave higher, sounding pitches G3/B3/E4.
- `score_events.json` records observed pitches in sounding octave, strings, frets and beat durations.

## Acceptance gate
- Flyway V21 deliberately sets `is_current=false`, `RIGHTS_PENDING`, `PENDING_PEDAGOGICAL_REVIEW`; no audio or PDF resources are registered. It must not be assigned, recommended or published.
- Prototype PDF/MP3 and a local generator were prepared for the owner in the conversation but **are not production repository media**, so the app does not advertise preview URLs that it cannot serve.
- Before publishing, verify PDF against MUS-NOTATION-01 reference (five-line staff + six-line TAB, four bars per row), reconcile audible count-in and onset timing, review guitar playability and correct `tempoUnit`, record suitable rights/attribution, and test browser playback.
- Even if approved, a diagnostic probe is **optional and gap-driven**; never automatically infer a skill failure from an untried time signature or a student's musical taste.
- For the complete adaptive-selection engine, see #145 and GM-DIAG-01. Green CI on the data layer alone does not demonstrate audio performance assessment.
