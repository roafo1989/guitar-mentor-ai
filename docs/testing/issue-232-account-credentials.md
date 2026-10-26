# #232 — Review and validation

## Local checks (26 October 2026)

- Frontend `npm test`: **203 passed**, including shared policy boundaries and mounted Vue account form tests (failed read, conflict draft retention, duplicate submit, stale actor response, six-character password).
- Frontend production build: **passed**. Existing large-bundle warning remains.
- Java 21: targeted `CredentialPolicyTest,LocalMailOriginTest`: **5 passed**; backend package and test compilation passed.
- Real headless Chromium `npm run test:profile-browser`: **passed**. Real Vue/router/AuthGate, mocked backend. Added Cyrillic rename (conflict then success), unchanged learner display name, RU/EN at 360 px, desktop save confirmation, registration rejecting five characters and accepting six. Existing #229 profile/navigation/error/reflow checks also passed.
- Visual review: mobile account form fits the viewport; explicit Save and Cancel; password policy is visible. Screenshots contain synthetic fixture accounts only.

## PostgreSQL / secured HTTP checks

New tests cover V30 over populated ASCII tables, preservation of identity/email/history links, old/new/email authentication, six-character registration/reset/change/linking, occupied active/pending names, expired reservations, simultaneous claims in independent transactions, authenticated ownership, CSRF and rate limiting. They require `GUITAR_MENTOR_TEST_POSTGRES=true` and a real PostgreSQL server. These checks run in Java backend CI (PostgreSQL 17); the local environment cannot run native PostgreSQL as a non-root user. First CI run `40378980893` passed all new PostgreSQL checks (11 credential-service, 1 concurrent claim, 3 secured HTTP and 1 migration tests); its overall result failed because the new route was missing from the explicit API authorization inventory. The route is now classified with actor/password/CSRF/throttle requirements; the complete rerun passed as recorded below.

## Review notes

Reviewed all username claim paths for lock ordering and reservation conflicts, actor-derived ownership, password confirmation/hash handling, unchanged session identity, stale Vue responses and Java/TypeScript normalization agreement. Fixed NFC after lowercasing for characters whose case conversion changes composition. Browser fields use a coarse UTF-16 maximum; shared helpers enforce exact Unicode code-point boundaries.

Live SMTP and Google-provider behavior are unchanged and were not tested with real providers in this task. Browser API responses and SMTP in the PostgreSQL HTTP tests are fixtures.

## Screenshots

- [Desktop edit](qa232/username-edit-1440-ru.png)
- [Desktop saved](qa232/username-saved-1440-ru.png)
- [Mobile Russian](qa232/username-edit-360-ru.png)
- [Mobile English](qa232/username-edit-360-en.png)
- [Cyrillic registration](qa232/register-cyrillic-360-ru.png)

## Final CI result

Validated implementation commit: `d80e17dba15075e546ffd3840e9278c7c8ec3781`.

- [Java backend CI 40380932082](https://github.com/roafo1989/guitar-mentor-ai/actions/runs/40380932082): **success**. 360 tests discovered, 356 passed, 4 skipped, no failures/errors. Real PostgreSQL 17 migration/service/concurrency/secured HTTP checks, package, application startup and existing live HTTP regression smoke all passed.
- [Vue frontend CI 40380932235](https://github.com/roafo1989/guitar-mentor-ai/actions/runs/40380932235): **success**. 203 main + 55 intake + 40 tuner = 298 tests passed. Both production build modes, dependency/provenance check and all four Chromium suites passed. Browser APIs remain mocked.
- The route inventory omission from the first run is corrected. No unresolved review findings in the scope of #232. The subsequent documentation-only commit records this evidence; application and test sources are identical to the validated commit.
- [PR #233](https://github.com/roafo1989/guitar-mentor-ai/pull/233) remains open for owner acceptance, based on the unmerged #231 branch.

Manual acceptance: switch to `feature/232-account-login-policy`, restart backend (V30) and frontend, open Account settings → Login → Edit. Save a Cyrillic login using the current password, sign out/in with the new login; also confirm email sign-in. A five-character new password should be rejected and a six-character one accepted. Reusing another active login must show a conflict without losing the typed value. Profile display name and learning history should stay unchanged.
