# GM-EB02 — «Шаги к мелодии»

**Version:** 1.0.0-draft · **Status:** PENDING_HUMAN_MUSICAL_REVIEW · **Origin:** ORIGINAL

Original AI-assisted 8-measure beginner fingerstyle etude, standard tuning, 4/4, 72 BPM, first position only (frets 0–3). No pick, percussion or external song material. Tonal center C, with simple C–G–Am–Em–C–Fmaj7–G–C movement and a closing melodic resolution to C4. Quarter-note pulse is intentional to teach metronome synchronization; this is not meant to test advanced rhythmic variation.

## Learning and playing

- Goal: maintain four equal beats per bar while alternating the thumb on the bass (beat 1) with fingers i/m/a on upper strings (beats 2–4). Final bar uses p/i/a/m to resolve on C4.
- Practice: count 1–2–3–4 aloud and start at 56 BPM. Increase to 64, then 72 BPM without skipping beats or speeding up. 10-minute suggested practice.
- Success guideline: two consecutive complete performances without stopping or missing beats. This is a student-facing recommendation, not an automatic assessment.
- Guitar: standard E2 A2 D3 G3 B3 E4; read guitar tablature/string annotations along with pitched MusicXML.

## Source files and validation

- `score.musicxml` — canonical score, MusicXML 4.0-compatible partwise, 8 measures and 32 notes; pluck tags mean right-hand fingers.
- `score_events.json` — structured educational metadata and one event per score note, with exact measure/beat, voice, pitch, string, fret, and duration.
- `test_score.py` — offline Python standard-library regression check, runnable with `python music/etudes/GM-EB02/test_score.py` from repository root. Checks XML/JSON consistency, pitch vs standard-tuned string+fret, first position, bar durations and ending; includes mutation tests for fret/duration mismatches.
- PDFs and synthesized MP3s are delivered by #81; catalog DB/API registration by #84. No new Flyway migration or application UI is part of #77.

## Rights / review

Author credit: **Guitar Mentor AI — original AI-assisted draft**. Origin: `ORIGINAL`. License field: `ALL_RIGHTS_RESERVED_PENDING_OWNER_DECISION` (release license not selected; no statement of copyright enforceability is implied). No melody has knowingly been copied from a third-party composition. Rights review, originality screening and any public license decision remain the owner's responsibility.

Musical approval: **PENDING_HUMAN_MUSICAL_REVIEW**. Automatic consistency checks do not imply human guitar playability or artistic approval. Before inclusion in an automatically assigned lesson, review score import, TAB visibility, finger choice, guitar playability, tonal/melodic quality and explicit owner acceptance. No AI PASS and no human acceptance are claimed.
