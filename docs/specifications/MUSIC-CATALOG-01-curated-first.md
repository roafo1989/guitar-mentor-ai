# MUSIC-CATALOG-01 — Curated-first educational repertoire policy

**Decision:** Approved by product owner on 2026-10-24.
**Scope:** FT-002-03, US-002 #56, issues #77–#84, catalog authoring and publishing.
**Status:** Product decision; implementation requirements must be reconciled separately.

## 1. Principle
The Guitar Mentor AI catalog prioritizes **existing artistically credible repertoire** that is *lawfully reusable* and appropriate to the student's technique and ability. The application's mission is musical learning, not achieving arbitrary quotas of AI-generated exercises. Original miniatures are allowed only if a specific pedagogical skill gap cannot be filled by suitable material or if they add clear educational value. Original material must undergo the same human musical approval as curated material.

Do not equate public web availability or downloadable tabs with permission to copy, rearrange, redistribute, or publish PDF/audio. A public-domain **composition** may still have copyrighted editions, engravings, arrangements, recordings, and performances; verify rights to each asset separately and for the intended jurisdiction/use. A URL-only external reference may be permissible when hosting is not, subject to source terms.

## 1a. Flexible time signatures (owner decision 2026-10-24)

Learning repertoire is **not restricted to 4/4**. Preserve each composition's authentic notated meter (for example 2/4, 3/4, 3/8, 4/4, 6/8). Meter is an explicit, versioned learning attribute, not a disqualifying repertoire filter. Do **not** convert an existing composition to 4/4 merely to satisfy an outdated exercise template. Difficulty is evaluated independently of meter, BPM, rhythm subdivisions and fingering; 3/8 is not intrinsically more advanced than 4/4.

Use meter diversity intentionally in diagnostics to distinguish:
- maintaining steady beat within one meter;
- recognizing and accenting the opening beat and grouping of beats;
- adapting to simple duple versus triple meter;
- understanding compound subdivisions where suitable to the student's stage (6/8: two dotted-quarter beats, each divided into three eighths, not necessarily six equal accented beats).

**Assessment policy:** diagnosis must use *actual performance evidence*, not infer a deficit from a student's preference or a single unfamiliar meter. For novices, choose an appropriate progression and permit guidance / retries; record skill outcomes by meter context, do not automatically mark a general pulse skill failed because a more complex meter is new. No automatic AI PASS without applicable audio-assessment functionality.

**Metronome / playback dependency:** UI and Web Audio must show and accent the actual exercise meter. An implementation that only counts 1–4 must not be shown as a correct 3/8 or 6/8 accompaniment. Define pulse unit and accent pattern in content metadata; stop audio or show a clear limitation when unsupported. Expand the metronome tasks and tests before exposing non-4/4 pieces with misleading count-in. No silent fallback to 4/4.

**Acceptance:** at least examples and checks for 2/4, 3/4, 3/8, 4/4, and 6/8 where supported; verify bar totals, onset grids, count-in and audible accents, score/TAB alignment and version integrity. The initial rollout may support a subset if honestly indicated and non-supported material is not falsely advertised as compatible.

## 2. Content-selection funnel
1. Specify the learning skill, target level, style, melodic interest, range and expected duration.
2. Search existing approved internal catalog and lawful external repertoires.
3. Evaluate musical quality and student motivation before authoring or producing notation.
4. Confirm provenance and asset-specific rights for composition, arrangement, score, TAB, PDF, recording, and generated adaptations.
5. Choose: (a) publish a licensed/public-domain self-contained educational arrangement with documentation, (b) link to a legitimate external source without republishing content, or (c) create an original teaching miniature only with a documented unmet pedagogical need.
6. Audition a short audio/music prototype and get *explicit human music approval* before expensive production and integration.
7. Produce MusicXML, synchronized score+six-line TAB PDF per MUS-NOTATION-01 (reference ex2(1).pdf, four measures per system), JSON/events and sound preview from one versioned source. Validate all assets.
8. Only approved versions with adequate rights can be marked eligible for catalog publication/automatic assignment.

## 3. Mandatory review flags
- DRAFT: content proposal, not reviewed.
- MUSIC_REJECTED: the musical idea failed owner review; never publish.
- RIGHTS_PENDING: permissions/attributions are not sufficient for distribution; keep hidden from publishable catalog.
- TECHNICAL_REVIEW_PENDING: music accepted but format/versions or assets not fully verified.
- APPROVED_FOR_CATALOG: explicitly accepted music, rights sufficient, notation and audio version verified.

These are conceptual publication states, not a demand to replace existing database enums without migration planning. Retain history of reviews and version IDs. Do not relabel rejected music as accepted after green CI.

## 4. Acceptance criteria
- **AC-MUS-01:** identifiable melodic idea, phrasing, form and ending; an actual human listening evaluation and owner decision recorded.
- **AC-MUS-02:** appropriateness to learning skill, playability without pick or percussion when tagged melodic fingerstyle.
- **AC-MUS-03:** rights/provenance evidence for each published component and restriction on redistribution of unlicensed editions/recordings.
- **AC-MUS-04:** MusicXML, events JSON, notation, TAB, preview audio originate from the same immutable version and pass documented technical checks.
- **AC-MUS-05:** rejected or rights-pending versions excluded from automatic assignments and public catalog.
- **AC-MUS-06:** original composition requested only after documenting the insufficiency of suitable existing repertoire; no mechanical generation to meet a quota.

## 5. Existing tasks and migration notes
- #77 GM-EB02: three revisions musically rejected. PR #135 must not be merged as approved music; reconsider as curated beginner pulse selection, or justify original composition before continuing. Preserve rejected versions/history.
- #78 GM-EB03, #79 GM-EB04, #80 GM-EB05: revisit their original-only wording. Technique outcomes stay, content source may be curated existing music with verified permissions. Their implementation/PRs must be independently reconciled; no claim that prior musical content is already accepted or rejected.
- #81/#82 PDF+MP3 production must begin only after musical source and rights gates pass; audio synthesis may require a distinct rights review for arrangements.
- #84 backend registration: only publishable, version-matched content; enforce approved vs review-only status, respecting current schema/compatibility and historical assignments.
- #56 US-002 and #17 specification: revise success definition away from a quota of newly generated originals. Preserve a useful range of practice goals, but source type is no longer mandated.
- New user-uploaded compositions/arrangements from the shared catalog require provenance, moderation and copyright-safe visibility; that upload feature is a separate future scope.

## 6. Decision record
Three GM-EB02 variants passed structural checks but were explicitly musically rejected by the product owner. The owner then chose to prefer existing, appealing compositions when legally usable; generation of new etudes becomes a fallback, not the default. This supersedes earlier conflicting original-only mandates for content selection but does not itself implement changes in code, contracts, catalog entries or project board fields.
