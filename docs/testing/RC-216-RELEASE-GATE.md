# #216 — release gate and isolated rehearsal

**Status: NOT COMPLETE.** This procedure is a controlled runbook, not evidence of successful execution.
Scope: PR #217, migration V29, two-account authorization and archived-task invariants.
Never run destructive commands against the live PILOT database or modify the running PILOT service.

## Baseline evidence already collected

- Java CI #851 and Vue CI #1327 passed at `47262b45` (the following commits changed only documentation).
- PostgreSQL `NextLessonPostgresIntegrationTest` covers transactional rollback, duplicate requests, concurrency, history keyset ordering and invalid ownership at service level.
- Real DEV user confirmed F5, Google re-login, old 9 attempts in read-only history, and new ACTIVE session.
- Static security review: `ActorFilter` binds path `learnerId` to authenticated actor; Spring Security requires CSRF for state-changing `/api/v1/learners/**` endpoints.

These facts do **not** establish the full remaining TC-09/10/11/12/18/24 or a successful PILOT migration.

## Gate A: restore rehearsal on an isolated copy (TC-24)

Preconditions: explicit authorization for database backup, an **isolated** disposable PostgreSQL database, sufficient disk capacity, and credentials with minimum permissions. Verify live PILOT connection configuration separately; do not copy credentials into tickets/logs.

1. Record V28 baseline DB version, migration checksum, and counts of `diagnostic_sessions`, `diagnostic_tasks`, `attempts` (or exact current attempt table), and `task_learning_state`. Discover actual table names before querying.
2. Create a consistent `pg_dump -Fc` backup of the source. Protect the backup as sensitive user data. Never commit it.
3. Create a **new, uniquely named** database (e.g. `guitar_mentor_qa216_copy`) on a separate PostgreSQL instance, ideally with network isolation; ensure its JDBC URL is not the live PILOT URL.
4. Restore with `pg_restore`. Confirm counts match baseline **before** migration.
5. Start the candidate backend against the isolated DB with outbound email/OAuth/media/network integrations disabled and no public listener, so no production side effects. Let Flyway apply V29. Check `flyway_schema_history` for success.
6. Validate `ARCHIVED` is allowed, table `lesson_transitions` and unique constraints/indexes exist, prior attempts/results remain byte-for-byte or logically identical, and new next-lesson transitions work only on **synthetic** learner rows inserted into the copy.
7. Rollback rehearsal: stop candidate process, destroy **only the isolated copy**, create another fresh isolated DB and restore the original dump. Validate baseline counts and ability to start the previously deployed compatible backend version. **Do not run old binaries on a database already containing ARCHIVED state.**
8. Record command outputs after removing credentials and personal data, exact commit, tool versions, timestamps, source/target DB identities and a PASS/FAIL decision. Destroy test copy and dispose of backup per data-retention policy.

**STOP** if the target database identity is uncertain, counts differ, migration fails, or the application unexpectedly reaches production dependencies. TC-24 remains PARTIAL until rehearsal evidence is attached.

## Gate B: authorized two-account HTTP integration (TC-18)

Preconditions: two independent, admitted disposable DEV accounts A and B, each with owned learner/session IDs; CSRF enabled. Never log session cookies, bearer tokens, OAuth codes, or CSRF values.

1. As A, GET own lessons and GET own archived lesson: expected 200.
2. As B, GET A's `/api/v1/learners/{learnerA}/lessons?instrumentCode=ACOUSTIC_GUITAR`: expected 404 without disclosing A data. Attempt A's session/task and successor endpoint with B credentials: expected 404.
3. As authenticated A, POST own next-lesson endpoint **without CSRF token**: expected 403, and verify DB transition count unchanged. Do not use a terminal task unless prepared to legitimately create a successor.
4. With valid CSRF, send malformed payload and foreign IDs: expected documented 400/404, and no state mutation. Verify reused idempotency key with different payload yields 409.
5. Capture only status codes, redacted JSON error codes and counts. Never attach cookies or PII.

**STOP** if the target is PILOT/production, any token appears in logs, or a foreign learner returns data. This is not executed merely by reading `ActorFilter`.

## Gate C: recovery and media lifecycle

- TC-09: archived session retains attempt metadata but media expired via TTL cannot be opened; upload attempts to terminal/archived task rejected server-side.
- TC-10/11: create successor in disposable DEV fixture, restart backend, resend exact request ID/payload: 200 replay with same IDs; simulate lost response only in an isolated test harness.
- TC-12: concurrent POST versus completion/upload on disposable rows yields at most one successor and no orphan session/task.
- TC-22/23: keyboard navigation, focus, 200% zoom, score viewer, recording and audio tooling on supported browsers.

## Release decision

- **Current:** core functional acceptance passed; CI green; release gate OPEN.
- **Never** treat a runbook as an executed test.
- Attach outputs/evidence to PR #217 and update `docs/testing/QA-216-2026-10-25.md` only after each gate actually runs.
- Merge #217 and update PILOT only after the release owner explicitly accepts all remaining risks.
