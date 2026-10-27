# #237 — Stabilization / candidate verification

| Version | Date | Change |
| --- | --- | --- |
| 1.2 | 2026-10-27 | Publication authorized by owner; recover expired local checkout and repeat CI on published SHA |
| 1.1 | 2026-10-27 | Review corrections and local test results; publication awaiting explicit owner approval |
| 1.0 | 2026-10-27 | Auth generations, initial screens, original media, RU/EN recorder regressions |

Branch: `fix/237-implemented-scenarios`; base main `0914a79b`.
Includes ready dependency #236 (`973ce741`) and QA #240 (`dc2ed870`).
No automatic acceptance/merge/closure of these PRs. Refs #237.

## Changes and review

- AUTH: context generation invalidates queued requests/CSRF on clear, logout or
  identity change. Requests check it before nativeFetch, token cache writes and
  handling responses/errors. Same-account refresh preserves pending writes.
  Latest /me sequence owns success/error/ready/expired/reset effects. Old logout
  cannot clear a new login. Stale work aborts with AbortError.
- EMPTY: existing own bare ACOUSTIC_GUITAR learner reads initial skills and empty
  lesson history without creating any instrument/background/session/signal rows.
  Foreign/missing learner, unknown/other instrument and malformed limit/cursor
  remain errors. UI initial action is clear; HTTP errors never claim empty history.
- MEDIA: original 0.8B archive recovered. PDF/MP3 included unchanged as Maven resources;
  provenance/rights/SHA-256 recorded beside them. No Flyway or published music edits.
- I18N: real recorder refusal mounted in RU/EN; no second hardware acquisition,
  interruption or release of another active recording.

Self-review corrections: stale CSRF network/JSON errors normalize to cancellation;
late 403 JSON cannot clear a newer context; same-account object refresh does not
hide real expiry; empty history never appears alongside 401/403/404/500. Each has
executable regression coverage. Existing #235 atomic first-write tests are retained.

## Verification evidence

The initial development session recorded: original auth repro 2 FAIL before and
2 PASS after; frontend 240 main + 57 intake + 40 tuner PASS, production build and
0-vulnerability audit PASS; four Chrome smoke suites PASS (mock backend), including
empty history/action and 401/403/404/500 error preservation. Java 21 package and
30 targeted backend tests PASS. Packaged PDF/MP3 hashes matched the original archive.

The original local checkout expired while publication was blocked. Recovery uses
this conversation's recorded changes plus unchanged source refs. The recovered
candidate repeats checks and GitHub CI; prior results are historical evidence,
not a claim of final-SHA success. Final run IDs/results are recorded in PR/issue.

Initial full local verify lacked PostgreSQL: 384 total, 218 passed, 13 Spring-context
errors due localhost:5432 unavailable, 153 skipped. This was BLOCKED, never green.
Required acceptance is full CI with PostgreSQL 17 and `GUITAR_MENTOR_TEST_POSTGRES=true`.
No tests/assertions/skips were removed or weakened to obtain success.

Physical phone microphone/guitar, real Google redirect and live SMTP are manual UAT.
Automated HTTP chain uses real PostgreSQL/security/MVC/storage, synthetic OIDC and
0.1-second generated WAV; Chrome smoke uses mocked HTTP. No production changes.

## DEV / UAT on Windows

Use isolated DEV/test databases and existing DEV configuration. Stop running DEV
backend and frontend before update. Preserve local edits if Git reports them.

```powershell
git fetch origin
git switch fix/237-implemented-scenarios
git pull --ff-only
git rev-parse HEAD
```

Compare the SHA with the latest PR head and include it in UAT feedback.
Backend terminal (Java 21, existing DEV PostgreSQL settings):

```powershell
Set-Location backend
.\mvnw.cmd --batch-mode verify
.\mvnw.cmd spring-boot:run
```

For real PostgreSQL integration set `GUITAR_MENTOR_TEST_POSTGRES=true` and
`SPRING_DATASOURCE_URL/USERNAME/PASSWORD` to a separate test DB. This candidate
adds no migrations. Never point destructive tests at production data.

Frontend terminal:

```powershell
Set-Location frontend
npm ci
npm test
npm run test:intake
npm run test:tuner
npm run build
npm run dev
```

Use existing authenticated DEV launcher/config, including
`VITE_GUITAR_MENTOR_AUTH_REQUIRED=true` and learning/uploads settings, to open
usual DEV HTTPS address. Bare Vite prototype mode is insufficient for account
switching. Restart both backend and frontend and refresh Ctrl+Shift+R.
Preview files need no separate ZIP installation.

Owner UAT:
1. New Google/local user, before preferences: open profile/skills/history without
   false errors; fill questionnaire, start diagnostics, reload retained answers.
2. Sign out A, sign in B: no A draft/history/success notification appears in B.
3. On phone record/replay, complete by learner choice, proceed to next lesson/history;
   recorder lease refusal is readable in RU/EN without interrupting first recording.
4. Play GM-EB01 and open PDF before/after assignment on the DEV build.

Issue stays open. No merge/deploy before owner acceptance. Project v2 fields are
unavailable through connector: do not claim board changes. After green CI and
available automated QA target is Testing / Waiting for owner UAT.
