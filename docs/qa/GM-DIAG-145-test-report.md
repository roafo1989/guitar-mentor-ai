# GM-DIAG-145 — Developer review and QA handoff

Date: **2026-10-24**. Scope: #145, PR #176. This report distinguishes *implemented, validated code* from *product acceptance not yet obtained*.

## Build and regression evidence

- **Vue frontend CI**: [GitHub Actions #40090776827](https://github.com/roafo1989/guitar-mentor-ai/actions/runs/40090776827) — SUCCESS, includes frontend unit/intake tests, typecheck/build and browser smoke.
- **Java backend CI**: [GitHub Actions #40090776778](https://github.com/roafo1989/guitar-mentor-ai/actions/runs/40090776778) — SUCCESS, includes PostgreSQL/Flyway migration and HTTP regression.
- Earlier red CI #40090283016: mocked assignment lacked publication metadata and an old upgrade test assumed the final Flyway rank would always be 19. Both were repaired; passing CI is the subsequent result.
- A real PostgreSQL test `DiagnosticProbePostgresTest` verifies existing GM-EB01 remains learning repertoire and draft 3/8 material is unavailable in both public catalog and exact-version asset routes.
- The candidate score source contains 12 bars × 3 eighth notes = 36 events, original technical pattern. The browser-test review PDF and MP3 were generated outside the repository using the same event definition. PDF was rendered for visual inspection; samples are **not** server-hosted production assets.

## Development code review

- Backward compatibility: V20 adds columns with defaults, does not rewrite old exercise UUIDs or task associations; existing exact task snapshots are preserved.
- Scope discipline: V21 creates one review-only `is_current=false` row. Rights and pedagogical review remain pending; no invented AI score, music approval, or publication.
- Fail-closed: `ExercisePublicationPolicy` blocks drafts (including direct exact resource links) and requires approved license/status plus audio/PDF metadata before publishing a new diagnostic probe.
- Adaptive suggestion: `ProbeSelectionPolicy` selects at most one approved short probe (5–60s) for an *actually unassessed* primary skill. Submitted audio with no validated assessment is not treated as demonstrated mastery.
- UI: separates short probes from optional full repertoire; existing legacy DTOs without `contentKind` remain repertoire, never covertly turned into diagnostic tests. Explicit tempo unit is passed to metronome.
- No DB enum migration for already-assigned learning sessions and no duplication of the flexible metronome engine.

## Acceptance tests (D01–D09)

| ID | Status | Evidence or blocker |
|----|--------|---------------------|
| D01 familiar recording | PARTIAL | Existing intake upload remains available; no automatic analysis of arbitrary audio to prove skills yet |
| D02 targeted short probe | PARTIAL | Pure short-probe selection and UI contracts pass; the only new prototype remains unpublished pending review/media |
| D03 3/8 meter | TECHNICAL PASS | Correct meter and explicit eighth tempo unit represented; live human auditory check from a deployed task pending |
| D04 6/8 compound group | TECHNICAL PASS | Existing metronome supports groups 3+3 and explicit tempo unit; end-to-end listening pending |
| D05 alternatives for style preferences | PARTIAL | Full pieces are optional in UI; broad licensed multi-style library not yet present |
| D06 playable audio and score+TAB | REVIEW PREVIEW ONLY | User-facing PDF and MP3 supplied in conversation; no approved backend media URLs or final notation acceptance |
| D07 prevent draft publication | PASS | Java unit + actual PostgreSQL test for catalog and exact routes |
| D08 student completion choice | REGRESSION PASS | Existing accepted user-override learning model unchanged; tests pass |
| D09 old fixed version survives | PASS | Existing PostgreSQL upgrade and exact-version tests pass after additive migrations |

## Final owner acceptance still required

1. Review the 21.6-second 3/8 technical pulse probe: educational usefulness, difficulty, count-in, accent, right-hand fingering and sheet/TAB readability.
2. Confirm whether 15–30s remains only an initial UX target or should become an explicit accepted range.
3. Authorize production placement of a **version-matched MP3+PDF** after independent visual/sonic QA and rights review.
4. Evaluate end-to-end deployed student flow, including uploading a familiar recording, single targeted suggestion, change of meter, and voluntary exit.
5. Do not mark #145 completed or merge PR #176 as a full product acceptance until these explicit remaining tasks are satisfied.

## Scope not falsely claimed as complete

- Automatic audio skill assessment and interpretation of user-submitted familiar pieces;
- Published/tested MP3 and standards-compliant PDF via backend's versioned media URLs;
- Final owner musical/pedagogical acceptance;
- Rich multi-style repertoire with sufficient alternatives for every learner.

**Recommendation:** move this PR to independent testing, keep #145 OPEN, do not publish unreviewed content. Implement remaining gaps in the same workflow or explicitly decompose after review; never mark a dependency complete by assumption.
