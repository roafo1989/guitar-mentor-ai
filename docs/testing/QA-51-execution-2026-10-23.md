# QA report — Issue #51, PR #124

Date: 2026-10-23. Branch: `feature/51-student-workspace`. Scope: H1 contextual home; H2 lesson workspace. Reference: `docs/testing/TC-51-ux-handoff.md` from the analysis branch.

## Execution and evidence

CI #515 on commit `bb865f202953897a44a2657e8c7b50e8120ccd29`: SUCCESS. The decoded GitHub Actions log confirms `npm run test:intake` (15/15), `npm test` (67/67 including 8 HomeSummary cases), `npm run test:tuner` (40/40), and `npm run build` (vue-tsc and Vite). Total: 122/122 automated tests, 0 failures. CI #524 on commit `36831c8835b02870b81321eb728a329b14d34bbc`: **SUCCESS**, validating the QA heading/localization correction with the same frontend CI pipeline.

GitHub connector/source inspection additionally confirms the original API methods remain GET-only in `home-summary.ts`, that the lesson contains the existing `ExercisePreview` and `SubmissionPanel`, and that the existing `LearningResultsPanel` and its paging/actions are still rendered. These are static inspections, **not** browser passes.

## Acceptance test matrix

| Case | Result | Evidence / remaining action |
| --- | --- | --- |
| TC-UX51-01: no duplicate domain implementation | PASS-STATIC | Existing record, media, evidence, decisions, metronome reused |
| TC-UX51-02: route/action count | PASS-STATIC | Home primary assigned state routes directly to `/diagnostics`; Record/Stop/Send and Complete/Confirm remain separate |
| TC-UX51-03: prototype walkthrough | NOT_RUN | Requires interactive local browser/owner; static prototype audit on analysis branch is not browser execution |
| TC-UX51-04: context, 204, 404, late response | PARTIAL-PASS | 8 HomeSummary unit cases including 404 and stale selection passed; network GET 5xx, unexpected response, 204, active learner-switch and visual retry remain browser/mock integration checks |
| TC-UX51-05: record, upload, retry, dirty/busy guard | PARTIAL-PASS | Existing navigation/component regression CI passed; microphone/device, upload failure and persistence need real backend/browser |
| TC-UX51-06: decision/revisit after F5 | PARTIAL-PASS | Existing learning regression and new COMPLETED_BY_LEARNER state test passed; real API decision lifecycle and F5 still need browser/backend |
| TC-UX51-07: lists, cursors, media, audio tools | PARTIAL-PASS | Existing unit regression passed; end-to-end paging, PDF fallback, mic/audio tool ownership require browser/backend |
| TC-UX51-08: RU/EN, 360px, 200% zoom, keyboard | PASS-STATIC / NOT_RUN-VISUAL | New keys are present; responsive CSS breakpoint exists; no actual viewport/accessibility/peripheral test |
| TC-UX51-09: scope and dependencies | PASS-STATIC | No new API or database changes; #85/#120 remain separate |
| TC-UX51-10: owner UX acceptance | BLOCKED-OWNER | Explicit owner decision needed; not substituted by code review |

## QA defect

- UX51-QA-01 (P2), fixed in PR branch: assigned-task page still displayed the heading and subtitle of the *initial assessment*, contrary to the promised “Lesson” workspace. Updated `DiagnosticView.vue` with state-sensitive heading and added RU/EN copy. CI #524 passed. Visual UAT still required.

## Residual risk and release gate

No verified failing automated case after QA source changes, but this is **not full UAT**. End-to-end tests with real backend, microphone, upload, completion, keyboard and mobile viewport were not run: the accessible execution environment cannot check out the public repo through DNS and has no live application or browser connector. Avoid stating PASS for these.

Before release: launch the app with the backend; test a saved assignment, a failed session GET, actual upload and retry, Complete cancel/confirm, F5, RU/EN, PDF, metronome/tuner and mobile 360px/200% zoom. Owner approves UX and manual tests. Keep PR open and Issue #51 open until acceptance.
