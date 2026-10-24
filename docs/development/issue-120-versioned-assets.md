# #120 — Version-pinned catalog assets: release invariant

An exercise assignment stores `exercise_id`. Its four URLs are derived from that exact persisted row's `code` and `version`; they must never be regenerated using the latest current version. A new musical revision requires a **new database row with a distinct (code, version)** and new version-specific classpath paths.

## Publishing and rollback rules

- Never delete, overwrite, or rename files under `backend/src/main/resources/exercises/` once published. Existing assigned versions must stay available after deploy and rollback.
- The backend PR CI rejects **M/D/R/C/T** changes in that directory compared with the target branch; newly added paths are permitted. For releases from other sources, perform equivalent checks before deployment.
- The publishing process must additionally verify that a new catalog row does not reuse an earlier `code + version`, that all four non-null paths exist in the deployable artifact, and that existing rows and resource paths are unchanged. DB unique constraints guard only (code, version), not filesystem bytes.
- A missing preview resource returns 404. It must not resolve to the current-version media. `HEAD` has identical status/content-type and no body.
- Never apply user-upload TTL cleanup to authored catalog resources. The read-only service does not expose write/delete endpoints.

## Verification

`GUITAR_MENTOR_TEST_POSTGRES=true ./mvnw verify` (with isolated PostgreSQL test configuration) includes `ExerciseVersionPostgresTest`: v1 and v2 have **different bytes** for score, events, preview audio and PDF; tests verify GET/HEAD status, Content-Type, no-cache and HEAD's empty body, plus missing-v1 PDF with existing-v2 PDF. The two versions and the missing-PDF row exist **only in test database fixtures**; test resources are synthetic, not production musical content.
