# FT-016-05 / #152 — real learning results and acceptance

Implementation target: `feature/issue-152-learning-results-acceptance` → `main`; PR #203. Dependency candidates #151 (PR #201, merge `6c705ac3`) and #198 (merged before #151) are part of the base branch. Business contract: UX-02-05 v1.0 (analysis ref `7be8d405`), AC-152-01…07.

## Implemented

- Preserves the existing one-owner `LearningResultsPanel` and relay through `SubmissionPanel` to a typed `DiagnosticView` banner. Freshness and `(learnerId,sessionId,taskId)` guard the displayed task status; unknown/loading/error is neutral instead of permanent “In progress”.
- Differentiates technical attempt states `UPLOADED/PROCESSING/FAILED/PROCESSED` from a real musical assessment; a failed selected assessment GET is not an empty authoritative list. Assessments shown only for selected attempt, never borrowed from latest/off-page summary.
- Provenance: HUMAN_REVIEW, VALIDATED_MEASUREMENT or unknown are explicitly labeled, without suggesting AI by default; method/version/date/limitations and criterion assessed/uncertain/unassessed/outcome/nullable level remain visible. RU/EN and invalid-date neutral fallback.
- Safeguards task-state terminal recognition against unknown DTO state. Existing backend permission `availableActions`, selected detail freshness, reason, frozen requestId, 409 refresh and manual retry are retained. Submission of a student-choice COMPLETE is not skill auto-PASS. Old decision response cannot unset a new context's sending state.
- No new routes, schema migrations, AI provider, predicted next lesson, manufactured scores/percentages or extra polling owner.
- Adds pure state presentation tests and mounted result-state regressions.

## Developer verification (current candidate head only)

Run `cd frontend && npm ci && npm test && npm run test:intake && npm run test:tuner && npm run build`, `VITE_GUITAR_MENTOR_LIGHT_MINIMAL=true npm run build`, and browser score/keyboard smoke. The GitHub Actions Vue frontend CI is the recorded source of executed checks; use the final PR head workflow, not a prior revision. Functional front-end component tests use fixtures and do **not** prove the live authenticated backend or an independent music assessment.

## Risk-based owner UAT checklist

1. Open a current assigned exercise; before learning load see a neutral unavailable/loading label; after read banner shows real active state.
2. With a valid processed attempt **without** assessment, verify technical success and explicit “no musical assessment”; backend-authorized completion should remain available even with no PASS.
3. If a human or validated assessment is available, inspect method, version, date, limitations, individual criterion status and confirmed level. A measured level of 0 is **not** an absent result.
4. Choose an older attempt while a newer assessed attempt exists; older result must never inherit the newer grade. Force a failed read and confirm error rather than false “no assessment”.
5. Complete by learner choice despite outstanding feedback. Banner changes to “Completed by your choice”, history preserved, upload blocked; no skill is automatically verified and automatic next lesson is unavailable.
6. Revisit retry/409 conflicts; use same-command manual retry, no auto-complete. Check RU/EN, Light Minimal and default mode, 360px and 200% browser zoom, keyboard, metronome/tuner and PDF fullscreen.
7. Cross-check integrated auth from #198 (email/Google where configured), #151 microphone (physical browser), and browsing Home/profile/onboarding/diagnostics. Do not claim provider/microphone tests passed solely on mocks.

## Acceptance/report boundary

- Developer CI and mounted fixture tests: to be tied to PR head SHA and completed workflow.
- Browser visuals, production-like HTTP/PostgreSQL, physical microphone, Google SMTP, Windows Edge, accessibility manual/zoom, independent QA, and owner review: **NOT_RUN until explicitly evidenced**.
- No merge or issue closure until user acceptance.
- Rollback: previous frontend build/commit. Backend decision history is authoritative and persists across frontend rollback.
