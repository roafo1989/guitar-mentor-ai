# #232 — Review and validation

## Local checks (26 October 2026)

- Frontend `npm test`: **203 passed**, including shared policy boundaries and mounted Vue account form tests (failed read, conflict draft retention, duplicate submit, stale actor response, six-character password).
- Frontend production build: **passed**. Existing large-bundle warning remains.
- Java 21: targeted `CredentialPolicyTest,LocalMailOriginTest`: **5 passed**; backend package and test compilation passed.
- Real headless Chromium `npm run test:profile-browser`: **passed**. Real Vue/router/AuthGate, mocked backend. Added Cyrillic rename (conflict then success), unchanged learner display name, RU/EN at 360 px, desktop save confirmation, registration rejecting five characters and accepting six. Existing #229 profile/navigation/error/reflow checks also passed.
- Visual review: mobile account form fits the viewport; explicit Save and Cancel; password policy is visible. Screenshots contain synthetic fixture accounts only.

## PostgreSQL / secured HTTP checks

New tests cover V30 over populated ASCII tables, preservation of identity/email/history links, old/new/email authentication, six-character registration/reset/change/linking, occupied active/pending names, expired reservations, simultaneous claims in independent transactions, authenticated ownership, CSRF and rate limiting. They require `GUITAR_MENTOR_TEST_POSTGRES=true` and a real PostgreSQL server. These checks run in Java backend CI (PostgreSQL 17); the local environment cannot run native PostgreSQL as a non-root user. First CI run `40378980893` passed all new PostgreSQL checks (11 credential-service, 1 concurrent claim, 3 secured HTTP and 1 migration tests); its overall result failed because the new route was missing from the explicit API authorization inventory. The route is now classified with actor/password/CSRF/throttle requirements; the complete rerun remains the final gate.

## Review notes

Reviewed all username claim paths for lock ordering and reservation conflicts, actor-derived ownership, password confirmation/hash handling, unchanged session identity, stale Vue responses and Java/TypeScript normalization agreement. Fixed NFC after lowercasing for characters whose case conversion changes composition. Browser fields use a coarse UTF-16 maximum; shared helpers enforce exact Unicode code-point boundaries.

Live SMTP and Google-provider behavior are unchanged and were not tested with real providers in this task. Browser API responses and SMTP in the PostgreSQL HTTP tests are fixtures.

## Screenshots

- [Desktop edit](qa232/username-edit-1440-ru.png)
- [Desktop saved](qa232/username-saved-1440-ru.png)
- [Mobile Russian](qa232/username-edit-360-ru.png)
- [Mobile English](qa232/username-edit-360-en.png)
- [Cyrillic registration](qa232/register-cyrillic-360-ru.png)
