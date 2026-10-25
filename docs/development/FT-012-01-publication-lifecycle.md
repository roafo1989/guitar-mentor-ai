# FT-012-01 / #165 — publication lifecycle API and QA

Date: 2026-10-25. PR #215, branch `feature/165-publication-lifecycle`.

## Scope and security
Only **explicitly uploaded** personal `library_materials` with original assets can be published. The private indexed source corpus (#193/#194) is in unrelated tables, with no pathway to publication and no downloadable origin. Private upload remains private until the owner explicitly publishes with proof of rights **for each individual asset**. The server stores an attestation and provided evidence; it does not independently verify copyright, so production policy and takedown processing remain necessary.

- Each publication is a distinct immutable snapshot of the material's title, type, author, source revision, asset IDs, original filenames, SHA-256, sizes, opaque storage keys, and per-file rights.
- Shared details and bytes use **authenticated/admitted actors** and `PUBLISHED` + `library_materials.state=READY`; no anonymous response, signed public URL, static paths, or bypass of the user's private object storage.
- Material PATCH changes only the private draft; an active publication is unchanged until the owner withdraws and publishes another revision. A material has at most one active publication. Republishing increments `version`.
- Owner can withdraw only own active publication; system OWNER may `HIDE` a live publication with a mandatory reason and audit entry; ordinary account edit/delete never targets another account.
- Material DELETE **atomically** changes every active publication to WITHDRAWN, appends audit `DELETE_WITHDRAW`, and queues existing private deletion. Old shared links respond 404 immediately. Physical cleanup and quotas remain owned by the #162 worker; no destructive public-file cascade.
- Separate shared search/filter #166, shared Work association #204, web UI #168 and preview/audio #167 remain OUT OF SCOPE for this PR.

## API

| HTTP method and endpoint | Access | Contract |
| --- | --- | --- |
| POST `/api/v1/library/materials/{materialId}/publications` | Material owner | Header `If-Match: "revision"` and JSON `{"assets":[{"assetId":"UUID","provenance":"CREATED","rightsBasis":"OWN_WORK","rightsEvidence":"..."}]}`; exactly one attestation per material asset; 201 with snapshot + Location |
| GET `/api/v1/library/materials/{materialId}/publications` | Material owner | All publication versions/history; PRIVATE metadata restricted |
| POST `/api/v1/library/materials/{materialId}/publications/{publicationId}/withdraw` | Material owner | 204, immediately closes public detail and bytes |
| GET `/api/v1/community/publications/{publicationId}` | Authenticated admitted | 200 only for active `PUBLISHED` + material `READY`; 404 otherwise |
| GET/HEAD `/api/v1/community/publications/{publicationId}/files/{assetId}/content` | Authenticated admitted | Byte-exact attached original; `Content-Disposition: attachment`, no-store/nosniff; only asset in this publication and still present in private store |
| POST `/api/v1/community/publications/{publicationId}/hide` | Operator `OWNER` | JSON `{"reason":"..."}` (8–500 chars); 204 and immutable audit; not a DELETE |

Rights basis: `OWN_WORK`, `LICENSE`, `PUBLIC_DOMAIN`, `PERMISSION`. Provenance: `CREATED`, `LICENSED`, `PUBLIC_DOMAIN`, `PERMISSION`. Evidence must be 8–500 characters. Unsupported/missing/duplicate asset IDs, missing proof, or unknown rights fail with 400. Rights evidence is user-supplied assertion, not automatic license verification.

## Rejection and behavior
- Invalid, missing rights: `400 PUBLICATION_INVALID_RIGHTS`.
- Missing `If-Match`: `428 PUBLICATION_PRECONDITION_REQUIRED`; stale revision: `412 LIBRARY_REVISION_CONFLICT`.
- Attempt to publish another user's draft: `404 LIBRARY_MATERIAL_NOT_FOUND`.
- Existing active publication: `409 PUBLICATION_ALREADY_ACTIVE`.
- Not found, withdrawn, hidden, removed content: `404 PUBLICATION_NOT_FOUND`.
- Not system owner moderator: `403 PUBLICATION_MODERATION_FORBIDDEN`.
- Missing operator reason: `400 PUBLICATION_INVALID_REASON`.
- Anonymous: `401 UNAUTHENTICATED` (Spring security filter can reject before controller).
- Disabled library: `503 LIBRARY_DISABLED`.

## Automated test evidence
`LibraryPublicationPostgresTest`: two LEARNER accounts, publication absent until explicitly requested, ownership isolation, incomplete and invalid rights, publication idempotent rejection, owner withdrawal immediately blocks all shared reads, immutable snapshot across edit, new version after republish, moderator HIDE authorization, audit, and DELETE atomic revocation. Runs under `GUITAR_MENTOR_TEST_POSTGRES=true`. Existing #162/#164 PostgreSQL suite covers original storage integrity/retry/quotas; new tests do not require actual music archive.

## Deployment and rollback
Flyway V28 is additive. No automatic sharing, no backfill, no migration of indexed source objects. Rollback route: stop new endpoint deployment, leave publication tables in place (do **not** delete evidence/audit). Current private library API remains unchanged except DELETE now also withdraws active publications. Operator use requires a documented legal moderation reason.

## Acceptance boundary
This task is backend publication/withdraw/HIDE; **not** visible in the current UI until #168 and not discoverable via public search until #166. Direct public-detail links are addressable only with a known publication UUID. CI and security review must pass before user acceptance, merge and closing #165.
