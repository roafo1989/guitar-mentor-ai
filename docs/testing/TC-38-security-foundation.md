# Issue #38 — Test inventory

The unit tests in `ActorFilterTest` check anonymous 401, foreign learner 404, own learner pass-through, unmapped/disabled identity 403, secure enrollment 403, and non-API passthrough. They do not constitute complete OIDC integration coverage.

| Test | Expected | Result |
| --- | --- | --- |
| SEC38-U01 anonymous scoped API | 401 | Automated unit test |
| SEC38-U02 account A reads B's learner UUID | 404 | Automated unit test |
| SEC38-U03 account A reads own learner UUID | Passes ownership filter | Automated unit test |
| SEC38-U04 unadmitted/disabled identity | 403 | Automated unit test |
| SEC38-U05 direct learner creation | 403 | Automated unit test |
| SEC38-E01 verified OIDC issuer/state/nonce | Invalid rejected | NOT_RUN — IdP needed |
| SEC38-E02 session fixation, idle and absolute expiry | Session revoked | NOT_RUN |
| SEC38-E03 CSRF no token / invalid token / multipart / logout | 403 | NOT_RUN |
| SEC38-E04 two known accounts, every learner route and media ID | A/B isolation | NOT_RUN |
| SEC38-E05 unknown/disabled with preexisting session | 403 next request | NOT_RUN |
| SEC38-E06 Vue logout/switch, 401/403 and locale | No stale data/write retry | NOT_RUN |
| SEC38-E07 actuator, HEAD resources and reverse proxy | Restricted | NOT_RUN |
| SEC38-E08 production deployment | Closed until #42/#39/#40 | NOT_RUN |

All NOT_RUN rows are release blockers, not implicit passes. No staging IdP, public domain or production credentials were configured in this PR.

## Review follow-up — 2026-10-24

The review found an insecure-by-default switch, stale cross-account UI state, non-revoking 403 handling, and insufficient logout/CSRF regression. Fixes are committed on the feature branch, including fail-fast rejection of insecure non-loopback binding before the server begins listening, authenticated-profile-only ownership, account-switch state clearing, and RU/EN secure UI.

Additional committed automated checks:
- `frontend/tests/auth-security.test.mjs` — multipart CSRF, logout and account-disabled 403 vs ordinary forbidden 403.
- `backend/src/test/java/ai/guitarmentor/infrastructure/security/SecurityStartupTest.java` — explicit localhost-only development bypass.
- `ActorRepositoryPostgresTest` — two distinct identities and immediate disable.
- `ActorFilterTest` — nested path GET/HEAD/POST/PUT denial.

**Verification discipline:** historical PASS results from SHA `6871260800f90e5deb1b29c44ac94ec44c1b10d4` apply only to that SHA. After review changes, no Actions workflow run has been confirmed for the current head. No claims of green CI, full security QA, independent human review or production acceptance apply to the new commit. Full OIDC/HTTPS/browser tests remain NOT_RUN and block external rollout.
