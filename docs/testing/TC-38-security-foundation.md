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
