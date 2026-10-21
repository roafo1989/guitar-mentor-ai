# #18 — Learning states, history and learner decisions

## Scope and safety boundary

Issue #18 introduces **pedagogical** state; an upload's technical `PROCESSED` is not a PASS.
It does not generate or assess audio, advance to a new lesson, or confirm skill levels.
The internal `LearningAssessmentWriter` has no browser-facing route and accepts only
trusted human/internal measurement sources. #22 must validate real measurement methods,
#23 will add explanatory feedback, #19 the learner UI, #24 next lessons.

Migration **V15** only adds tables, constraints, a read-only skill-evidence view,
and an `ACTIVE`/criteria-snapshot backfill. Keep previous Flyway checksums and
existing file retention intact. New assignments create state/snapshot in the same
transaction; the criteria belong to the exact assigned exercise version.
Previously assigned tasks remain active, never retroactively passed.

## Local opt-in

1. Pull this branch, back up the development database and run the Spring Boot backend.
   Flyway upgrades the DB to V15; inspect `flyway_schema_history`.
2. By default `GUITAR_MENTOR_ENABLE_LEARNING=false`: new learning routes
   respond `503 {"code":"LEARNING_DISABLED"}`, existing upload/list/profile/catalog work.
3. For local QA **only**, restart with `GUITAR_MENTOR_ENABLE_LEARNING=true`.
   It enables the learning routes, not any external grading model.
4. Current learner/client can list and decide upon its own attempts after upload.
   No student-facing assessment submission endpoint exists. Test assessments
   are created only by the integration fixture or internal trusted service.

The server is still local-only and has no login. Do not expose it to the internet
until the separate authentication/deployment work (#38).

## HTTP contract

Base: `/api/v1/learners/{learnerId}/diagnostic-sessions/{sessionId}/tasks/{taskId}`.

| Method | Subpath | Meaning |
| --- | --- | --- |
| GET | `/learning` | `ACTIVE` or terminal, version, available actions, latest attempt and assessment state |
| GET | `/attempts/{attemptId}/assessments?limit=20&cursor=...` | Immutable assessment revisions, newest first |
| GET | `/decisions?limit=20&cursor=...` | Immutable decision history |
| GET | `/learning/evidence` | Read-only criterion provenance, `confirmedSkillLevel=null` |
| POST | `/decisions` | Independent choice, server-computed resulting state |

POST example:

```json
{
  "requestId": "06a8f8f4-827f-4eaa-bdb0-545b17820fa1",
  "attemptId": "743f59b2-43bb-4907-a9e4-e45d615d9f3b",
  "action": "COMPLETE",
  "expectedVersion": 0,
  "baseAssessmentId": null,
  "reason": "Продолжу заниматься дальше"
}
```

Use real IDs from an assigned task and uploaded attempt. `reason` is optional,
trimmed and capped at 2000 chars. `baseAssessmentId=null` is correct when the
chosen attempt has no assessment. A stale version/assessment gives 409; reread
`GET /learning` and selected attempt's history before resubmitting.

Possible codes: `INVALID_DECISION`, `REASON_TOO_LONG`, `INVALID_PAGINATION` (400);
`RESOURCE_NOT_FOUND` (404, including cross-learner IDs);
`NO_ATTEMPT`, `VERSION_CONFLICT`, `ASSESSMENT_CHANGED`,
`IDEMPOTENCY_CONFLICT`, `TASK_COMPLETED` (409);
`LEARNING_DISABLED` (503). A first successful POST returns 201, byte-equivalent
normalized `requestId` replay returns 200 with the original saved result.
Assessment/decision history are paginated using opaque resource-bound cursors,
limit 1–100, default 20.

A complete decision based on current fully assessed PASS becomes
`COMPLETED_PASSED`. A complete decision after REVISE, UNCERTAIN or no assessment
becomes `COMPLETED_BY_LEARNER`. A CONTINUE always stays ACTIVE.
Nothing writes a MEASURED/OBSERVED level to skill history. New uploads of a
terminal task get 409 `TASK_COMPLETED` **even with learning flag off**;
previous upload `requestId` replay remains valid. Later assessments cannot
rewrite terminal decisions.

## Tests and review

```powershell
cd backend
mvn test
```

Java unit tests are always run. Real-PostgreSQL integration tests require the
same CI env setup as the existing backend tests: `GUITAR_MENTOR_TEST_POSTGRES=true`
and an accessible test PostgreSQL database. Avoid production data.

Review matrix: AC18-01 backfill and snapshots; 02 truthful unassessed state;
03 revisions; 04–06 choices; 07 continue/new attempt; 08 replay;
09 optimistic version and assessment CAS; 10 ownership; 11 TTL retained history;
12 terminal replay/guard; 13 source validation/no false skill level; 14 flag/rollback.
The integration suite exercises service/repository transitions with real SQL;
audio grading itself is not tested or claimed.

For rollback: turn learning flag off and restart. Preserve V15 tables;
**do not** alter old Flyway migrations or auto-downgrade SQL. If deploying an
older backend, also disable uploads: it will not know terminal-state guards.
Recovery of discarded media requires an existing backup; the TTL cleaner does
not archive files.
