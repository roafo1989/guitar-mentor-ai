# GM-EB02 — «Тихая дорога» (2.0.0-draft)

**Origin:** ORIGINAL (AI-assisted draft). **Musical review:** PENDING_HUMAN_MUSICAL_REVIEW. **License:** ALL_RIGHTS_RESERVED_PENDING_OWNER_DECISION.

### Rationale
The owner rejected editions 1.0 and 1.1 as mechanical, unattractive exercises. This is a **new original composition proposal**, not another embellishment of the rejected sequence. It contains an independent upper melodic voice and thumb bass on beats 1 and 3, two four-measure phrases, a gentle folk-inspired contour, sparse dotted/eighth rhythms and a closing return to C. Harmony: C — G — Am — C | Fmaj7 — Am — G — C.

### Teaching notes
- **Level:** Beginner. No pick, no percussion. Standard tuning E2 A2 D3 G3 B3 E4. Frets 0–3 only.
- **Skills:** PULSE and RH_HAND. 4/4 at 76 BPM. Count 1–2–3–4, play thumb bass on beats 1 and 3 and a distinct melody with i/m/a on upper strings.
- **First sing the melody** on its own, then practise bass, then combine. Begin at 56 BPM and increase slowly. Playing goal: sustain steady tempo and both voices throughout eight measures, with deliberate phrasing and the resolving cadence.
- **Form:** bars 1–4 statement/question; bars 5–8 developed answer and tonal resolution.
- **Rights:** no external copyrighted song source used intentionally; owner must review rights/publication and decide license.

### Files and checks
- `score.musicxml`: primary pitched score, two distinct voices (melody 1, bass 2), with MusicXML backup event.
- `score_events.json`: pitch, onset beat, voice, string/fret, duration and finger per event.
- `test_score.py`: built-in Python unittest checks per-voice totals, every pitch vs fret/string, JSON/MusicXML event equality, first-position range, final double bar, and deliberately corrupted cases. Run `python music/etudes/GM-EB02/test_score.py`.
- Reference audio/notation artifacts are **unapproved review previews**, not production media; final PDF/TAB/MP3 are tracked in #81 and MUST conform to MUS-NOTATION-01 (reference `ex2(1).pdf`).
- Database/catalog inclusion is #84, only **after** explicit musical approval. A green CI result is not musical acceptance.

**Important:** Neither prior editions nor this draft may be marked musically accepted on the basis of automated checks. Owner acceptance remains pending.
