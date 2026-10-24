# GM-DIAG-01 — Adaptive short-form guitar diagnostics

Status: **ANALYSIS DRAFT**, 2026-10-24. Parent: #56 (US-002); implementation task: #145.
Supersedes neither historical session state nor owner acceptance requirements.

## 1. Purpose and distinction

*Diagnostic probe* is a minimal, deliberately limited task collecting evidence about **one specific capability**. *Learning repertoire* is a complete musical composition for engagement and practice. A finished piece is not a prerequisite for diagnosis. A familiar performance supplied by a learner may be evidence **without** a new diagnostic probe.

The owner liked the music of Carulli's Waltz, Op. 241 no. 1, but noted a roughly 1:11 performance is too long for mandatory entry testing. The piece remains an optional repertoire candidate; it is not approved as a compulsory test or already-ingested catalog entry.

## 2. Initial journey

1. Let the learner submit an already-known piece, with score/TAB where available, or explicitly skip this path.
2. Map available evidence to skills; identify **unassessed** gaps without treating them as failures.
3. Recommend only a short probe that reduces the highest-priority uncertainty.
4. Allow preview of playable audio and score+TAB **before** assignment; identify skill, expected time, tempo, meter and how a good attempt is recognized.
5. Accept recording and assess (when a real assessment pipeline exists). Display uncertainty and scope of evidence; if a second probe is needed, explain why.
6. Learner can retry, supply other evidence, or accept current work and continue without falsely marking the skill proven.

A preliminary usability target of **15–30 seconds per probe** is not a strict requirement. Target actual on-instrument duration, excluding a count-in; large compound/rare meters need appropriate adjustment. **Do not** require completion of a minute-long composition for diagnosis.

## 3. Candidate skill contexts

| Probe context | Example meter | Observable evidence | What not to infer |
|---|---|---|---|
| Pulse in a simple meter | 4/4 or 2/4 | recurring note attacks and accent locations stay steady | student's preference for genre or melodic creativity |
| Grouping and three-beat pulse | 3/4 or 3/8 | regular grouping, accent cycle and downbeat | unfamiliar 3/8 automatically means inability in all meters |
| Compound grouping, only if evidence warrants | 6/8 (two groups of three eighths) | stable duple compound feel, grouped accents | that six eighth notes are six equivalent strong beats |
| Fingerstyle hand coordination | meter chosen to suit difficulty | thumb on bass with selected upper-string attacks, clean onset relationship | overall fingerstyle level from a single novice pattern |

These are **contexts**, not four universally mandatory probes. Do not ask for all if existing evidence is sufficient.

## 4. Proposed content contract — for design, not current backend schema

```json
{
  "contentKind": "DIAGNOSTIC_PROBE",
  "code": "GM-DIAG-PULSE-TRIPLE",
  "version": "0.1.0-draft",
  "meter": "3/8",
  "tempoBpm": 72,
  "tempoUnit": "eighth",
  "durationTargetSeconds": 20,
  "skills": ["PULSE", "METER_GROUPING"],
  "optional": true,
  "rightsStatus": "PENDING",
  "musicReviewStatus": "PENDING",
  "scoreTabReviewStatus": "PENDING",
  "audioUrl": null,
  "scorePdfUrl": null
}
```

**Important:** example only, no assets exist at these links, and tempo unit is intentionally explicit. Repository metronome supports beat-unit selections and groups; backend currently exposes `bpm` and `meter` separately. Before implementing, specify migration and compatibility for the `tempoUnit` field and preserve previously assigned code+version. The diagnostic should not silently interpret dotted quarter BPM as quarter BPM.

## 5. Content / rights gate

- Use brief, purpose-built **non-melodic technical patterns** only when the aim is a specific narrow skill, and label them *diagnostic probes*, not compositions. Musical enjoyment remains paramount for full learning repertoire.
- For a fragment extracted from an existing piece, document rights for composition, arrangement, excerpt, newly typeset score/TAB and recording separately; an arbitrary trim is not automatically licensed.
- Short preview audio must be directly playable in browser (e.g. authorized MP3), not solely MIDI that requires an SF2 installation.
- Score must satisfy MUS-NOTATION-01 (five-line score above six-line TAB, matched events, preferably four measures per row), accurate guitar sounding/notation octave and real playability.
- Explicit user musical acceptance still required for a piece marketed as learning repertoire; short diagnostic technical patterns need functional/pedagogical review and rights gate.

## 6. Acceptance test scenarios

- **D01**: A student provides a familiar recording; no repeated compulsory new piece is assigned when sufficient evidence exists.
- **D02**: Evidence is insufficient for one named skill; only a targeted short probe is offered and the reason stated.
- **D03**: Three-eighth meter probe opens the **3/8** metronome with correct accent cycle; no stale 4/4 settings leak from header.
- **D04**: A 6/8 item passes compound grouping and recommended BPM unit consistently to the metronome; don't assume note value.
- **D05**: A student dislikes a style; alternative material is offered without negative skill assessment.
- **D06**: Audio works in browser, PDF combines notes/TAB, source version matches audio and assigned criteria.
- **D07**: Rights pending or music rejected materials remain hidden from assignments.
- **D08**: User voluntarily finishes despite requested redo; history and unproven skill status remain distinct.
- **D09**: Legacy fixed-version GM-EB01 assignments and attempts remain available after any schema adaptation.

## 7. Delivery sequencing / responsibilities

Analyst: finalize contracts and source rights. Engineer: extend metadata and selection API minimally after review, UI gives user choice, auto-assignment gates; do not repeat existing metronome engine. Reviewer: verify beat unit / 3/8 / 6/8 and regression. Tester: cases D01–D09 + device playback. Owner: approves full music works, not invented acceptance. Project manager: align #56, #77–84, #145 and schedule accordingly.

This spec is **not** a release, content publication, passing recording assessment, or accepted catalog artifact.
