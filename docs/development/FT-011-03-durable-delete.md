# #162 — Private library deletion, durable cleanup

## API contract
`DELETE /api/v1/library/materials/{id}` requires admitted authenticated owner and current material `If-Match: "revision"`; CSRF protection is inherited from Spring Security. Successful first call returns HTTP 202, `{"id":"…","state":"DELETING"}`, `Cache-Control: no-store`. On an already-DELETING own material, repeated DELETE with syntactically valid If-Match returns 202 without new job or quota changes. Missing If-Match = 428; stale revision = 412; nonexistent/other owner's material = 404; unauthenticated = 401.

## Invariants
- Request changes READY → DELETING and queues a persistent `library_deletion_jobs` entry in **one DB transaction**. READY list/detail/download immediately return 404. Never re-expose DELETING material, including after restart.
- Scheduled `LibraryDeletionWorker` reads due jobs, takes account quota lock, then material and job locks, then retries physical object deletion. Files are identified **only** by opaque `library_assets.object_key`, never client path.
- Missing file after a crash is safe (idempotent `LibraryObjectStore.delete`). A failed file deletion leaves assets and quota charged, updates job RETRY with increasing backoff. Future job retries resume safely.
- After all file bytes are removed, worker deletes asset metadata, decreases charged_bytes/charged_files and deletes the job **in one database transaction**. Material tombstone stays indefinitely so replay of a successful idempotency key from #161 cannot resurrect an already deleted material.
- If DB commit fails after files were removed, tombstone continues to hide the asset and next retry ignores already-missing files. If deletion of some files fails, **never release quota** for that batch yet. Job retry remains durable across restarts.
- Private library objects are independent of attempt media and its 48-hour retention; the original orphan-recovery sweep must never delete referenced keys.
- Deletion does not automatically grant a user access to other accounts or remove entries in the separate indexed source catalog.
- **Before enabling publication #165**, its lifecycle must integrate withdraw/content revocation atomically with deletion, and worker must avoid deleting assets actively referenced by immutable published snapshots. #162 itself does not create publication references.

## Config
`guitar-mentor.library.enabled` also controls scheduler. `guitar-mentor.library.deletion-delay-ms` defaults to 15000 milliseconds. Worker batch size = 25; maximum explicit processBatch size = 100. Backoff capped at one hour. Do not reduce grace or perform direct manual filesystem deletes.

## Test cases
- Owner DELETE 202; immediately list/detail/original GET/HEAD fail to reveal material.
- Other actor delete 404; missing actor 401; no If-Match 428; stale 412; repeat own delete 202.
- PostgreSQL worker removes file assets and releases quota only after physical delete succeeds.
- Synthetic disk failure → RETRY / preserved assets/quota; success on next attempt.
- Concurrent retries serialize via PostgreSQL locks and cannot double-decrement quota.
- Restart: due PENDING/RETRY jobs processed without in-memory state, idempotent absent-file handling.
- Quota/account protection under concurrent uploads: both flows lock account usage before mutations.
- Storage outside dedicated library root and source archive are untouched.
- Real end-to-end deletion of disposable uploaded PDF/GP with byte checks and DB quota after worker run remains for user testing; **do not test with irreplaceable musical collection**.
