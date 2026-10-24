# #125 — Cursor-paginated submission attempt history

Base: `main` at `395decd` (#51 and #120 already integrated).

## API contract

Prefix: `/api/v1/learners/{learnerId}/diagnostic-sessions/{sessionId}/tasks/{taskId}/attempts`.

- `GET /page`: `limit` defaults to 20, must be 1–50; optional exclusive `beforeNumber` must be 1–2147483647. Descending attempt numbers. Response `{items, nextBeforeNumber, hasMore}`. Last/empty page returns `null,false`; reads at most limit+1 records, without `COUNT` or `OFFSET`.
- `GET /{attemptId}`: returns the existing `AttemptView` only for an attempt belonging to the current learner/session/task.
- Both endpoints use `Cache-Control: no-store`; bad query returns `400 INVALID_ATTEMPT_PAGE`; bad attempt UUID returns `400 INVALID_ATTEMPT_REFERENCE`; missing/foreign resources return `404 RESOURCE_NOT_FOUND`; unexpected read failure returns `500 ATTEMPT_READ_FAILED`.
- `GET /capabilities` now verifies ownership without reading attempts, adds no-store, and retains the response schema.
- Legacy `GET /attempts` still yields a complete array in descending order. Multipart `POST`, idempotency, terminal guard, media retention, and schema are unchanged.

## Implementation and verification

A dedicated repository keyset query uses the existing V7 index; `LIMIT limit+1` is applied via Spring Data `Pageable` returning `List`, avoiding a generated count query. A single bounded `SELECT DISTINCT attempt_id ... IN (...)` builds assessment flags, including any revision. Empty pages skip assessment lookup. The full legacy history shares the batch mapping but is not capped; it is retained solely for backward compatibility and should be retired after #126 transitions the frontend.

Tests include PostgreSQL cursor sequence (45→20/20/5), a newer insertion between pages, empty/limit 50, ownership/detail and unchanged legacy results; controller unit tests exercise invalid/repeated/overflow parameters, safe 404/500 and capabilities. PostgreSQL tests run only with `GUITAR_MENTOR_TEST_POSTGRES=true` and a configured disposable database; do not count skipped integration tests as a pass.

No database migration, config flag, or additional dependency is required. New frontend clients should use /page and /{attemptId} from #126. Rollback backend only after rolling back that frontend client; legacy clients remain compatible.

## Additional HTTP QA — 24 October 2026

- Head `447f77d713ac1612f14ac5d80f133cf2dd04656a`.
- [Java CI](https://github.com/roafo1989/guitar-mentor-ai/actions/runs/40065886766): SUCCESS, 212 tests, 0 failures/errors, 4 skipped. PostgreSQL attempt integration 13 PASS; controller unit 3 PASS.
- [Vue CI](https://github.com/roafo1989/guitar-mentor-ai/actions/runs/40065886750): SUCCESS.
- Live Spring Boot + PostgreSQL HTTP QA (the new `backend/scripts/qa125_attempt_http.py`): **PASS** for page/default/limit/cursor/detail/capabilities, exclusive bound, empty final page, repeated/invalid/overflow query parameters, unknown/foreign ownership, safe error codes and no-store. Runs after the existing multipart/idempotency/TTL smoke in the Java workflow; output explicitly reports `QA #125 live HTTP contract: ... PASS`.
- Real PostgreSQL paging fixture covers 45→20/20/5, inserted newer attempt, multiple assessment revisions and legacy GET.
- **Outstanding:** no instrumented numeric SQL-statement count was gathered; source-level bounded two-query design does not alone prove a numeric budget. Independent review and owner acceptance still pending. Do not merge or close #125 yet.
