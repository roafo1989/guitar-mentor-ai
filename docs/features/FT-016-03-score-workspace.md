# FT-016-03 · #150 — Light Minimal Lesson / Score Reader

## What ships

This change extends the accepted #148–149 Light Minimal opt-in configuration. It is guarded by `VITE_GUITAR_MENTOR_LIGHT_MINIMAL=true` (default **off**). No new backend endpoints, database migrations, authorization changes or music content are introduced.

- `DiagnosticView.vue`: 1560px max content, 24px gutters, desktop grid `minmax(0,1fr) 320px` at viewport ≥1100px, a single column below this width and 16px phone gutters. The left score region receives >60% of workspace at the 1100px breakpoint. All existing COLLECTING_EVIDENCE / READY_FOR_TASK / TASK_ASSIGNED flows and recommendation/probe/repertoire handling are retained.
- `ExercisePreview.vue`: a single existing audio preview, a collapsible score and an accessible disclosure of learning metadata/criteria in assigned Light Minimal mode. The PDF starts **closed**; the old native viewer remains behind the disabled feature flag or for unassigned preview cards.
- `ScoreReader.vue`: typed open/state contract and a local PDF.js canvas renderer. Prior/next, directly validated page 1..N, zoom 50–250% in steps of 25, fit-to-width, full-screen focus (native when allowed, fixed CSS fallback), Esc/focus handling, original/new-tab and MusicXML links, bilingual labels, error/retry.
- `score-transport.ts`: exact version-pinned same-origin PDF path validation, 20MiB streamed byte limit, 30-second fetch timeout, MIME and `%PDF-` checks. 100-page PDF cap, 8MP canvas cap, 10-second rendering cap.
- Renderer is lazily fetched from an **exact local pdfjs-dist 5.4.394** dependency with matching worker; no CDN or external service. The iframe has `sandbox="allow-scripts"` without `allow-same-origin`, CSP with `connect-src 'none'`, no annotation layer/HTML/XFA or PDF actions. The host alone fetches bytes with existing same-origin auth; renderer/worker source and PDF bytes are passed through a nonce-bound MessageChannel to the opaque iframe. Stale open/version/generation responses do not update the host; close/change/unmount aborts fetches and ports.
- New tests: `score-reader-contract.test.mjs` for URL/bytes/limits/state/i18n; `score-reader-browser-runner.mjs` in real Chrome for actual PDF page rendering, 2-page navigation, zoom, range errors, native/CSS focus, Esc, responsive widths, no prefetch while closed. Legacy suites remain.

### Dependency provenance

- `pdfjs-dist@5.4.394` exact version in `package.json` and lockfile.
- npm registry `dist.integrity` SHA-512: `sha512-9ariAYGqUJzx+V/1W4jHyiyCep6IZALmDzoaTLZ6VNu8q9LWi1/ukhzHgE2Xsx96AZi0mbZuK4/ttIbqSbLypg==`.
- SPDX license: Apache-2.0 (PDF.js upstream; retain vendor copyright/license). Runtime dependency audit in frontend CI: `npm audit --omit=dev --audit-level=high`. This is an audit snapshot, not a guarantee against undiscovered vulnerabilities.

## Test matrix

| Case | Developer automated verification | Owner validation |
|---|---|---|
| No session / evidence / ready-to-choose | Existing regression suites; no duplicate business state | Review real account and data |
| Assigned valid PDF | Chrome fixture actually parses/renders two PDF pages | Review real score as-is, verify true notation and TAB |
| Initially closed, independent of audio HEAD | Chrome test no GET before opening; existing media tests | Verify page and audio when audio HEAD is slow |
| Page controls, zoom, focus, Escape | Chrome test | Test 1440/1100/768/360 and 200% browser zoom |
| Bad URL/MIME/signature/size | Unit contract tests | Network/missing PDF and fallback |
| Credential isolation and sandbox | Source checks and Chrome test | Security review required before release |
| Recording draft / metronome / route guard | Existing regression suite | Practice with real microphone and file before merge |
| Locale and focus | RU/EN resource checks | Keyboard navigation and visual contrast |

Build and test:

```powershell
git fetch origin
git switch feature/issue-150-score-workspace
cd frontend
npm ci
$env:VITE_GUITAR_MENTOR_LIGHT_MINIMAL="true"
npm run dev
# In a second terminal, from frontend:
npm run build
npm test
npm run test:intake
npm run test:tuner
npm run test:score-browser
```

Owner can open an assigned task at `http://guitar-mentor/diagnostics`, expand notes, compare original PDF/score and exercise with a real backend, then check focus and recording. The browser harness intentionally uses synthetic PDF bytes, **not** the owner's musical material. It proves rendering functionality but not the musical correctness of actual source files.

### Rollback

Remove `VITE_GUITAR_MENTOR_LIGHT_MINIMAL` (or set to `false`), restart/rebuild Vite; legacy ExercisePreview and layout return. The original PDF, learner data, recording draft, and server APIs are unchanged. Do not merge the PR or close #150 until the owner accepts the page. A qualified independent security/code review remains distinct from development self-checks.
