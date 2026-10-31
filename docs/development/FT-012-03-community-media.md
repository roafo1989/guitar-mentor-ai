# FT-012-03 / #167 — фото, необязательное аудио и защищённые media endpoints

Статус: implementation handoff to independent testing. Scope: Java backend + Flyway V32. Product UI/library redesign remains #168; shared Work identity remains #204.

## Reuse and boundaries

- Reuses `LibraryObjectStore`, `library_upload_operations`, `library_account_usage`, reservation/manifest/commit/replay and `LibraryOrphanRecovery` from the existing private-library pipeline. No second object root, no public bucket and no alternate reservation store.
- Original assets remain immutable. A photo's sanitized preview is a second opaque object key stored on the same `library_assets` row; original and derivative bytes/files are both charged to the existing account quota.
- A media upload changes only the private material revision. It never edits an existing `library_publications` snapshot. Publication remains a separate explicit operation and the existing #165 controller records rights separately for every selected asset.
- Existing PDF/GP creation remains on the original #160 endpoint with its existing five-file contract. Media is opt-in and does not increase account or multipart transport quotas.
- No OCR, score recognition, GP conversion, audio synthesis/transcription, AI, external search or automatic music approval.

## API

| Method | Endpoint | Contract |
|---|---|---|
| GET | `/api/v1/library/capabilities` | Admitted account; reports `enabled`, media role extensions and limits. With the media flag off, `enabled=false`, role extensions and role list are empty. |
| POST | `/api/v1/library/materials/{materialId}/files` | Multipart `metadata` JSON `{ "role": "PHOTO_ORIGINAL" }` (or `ORIGINAL_RECORDING` / `SYNTHESIZED_PREVIEW`) and 1–2 `files`. Requires `If-Match: "<material revision>"`, UUID `Idempotency-Key`, CSRF and actor-derived ownership. 201 + updated material view/ETag; identical replay 200, no second asset/quota charge; payload mismatch 409. |
| DELETE | `/api/v1/library/materials/{materialId}/files/{assetId}` | Requires `If-Match`. Returns 202 and queues durable physical deletion. Published references → 409 `CATALOG_ASSET_PUBLISHED`; deleting the last ready score/photo → 409 `CATALOG_LAST_SCORE`. |
| GET/HEAD | `/api/v1/library/materials/{materialId}/files/{assetId}/content` | Existing private-original route. Photo original remains attachment; MP3/WAV is inline audio. One byte range is supported only for audio. |
| GET/HEAD | `/api/v1/library/materials/{materialId}/files/{assetId}/preview` | Sanitized JPEG/PNG derivative only; never returns original bytes. |
| GET/HEAD | `/api/v1/community/publications/{publicationId}/files/{assetId}/content` | Existing #165/#166 admitted-only catalog route, extended for audio and one byte range. Membership + PUBLISHED + READY are checked on each request. |
| GET/HEAD | `/api/v1/community/publications/{publicationId}/files/{assetId}/preview` | Sanitized photo derivative only; active publication membership checked on every request. |

The repository's established public catalogue route is `/api/v1/community/publications` (not a parallel `/api/v1/catalog` API). `hasAudio` now evaluates the actual READY `ORIGINAL_RECORDING` / `SYNTHESIZED_PREVIEW` membership: omitted means no audio filter, `true` means at least one audio asset, `false` means none.

## Roles and validation

| Role | Accepted extensions | Stored original MIME | Additional validation |
|---|---|---|---|
| `PHOTO_ORIGINAL` | JPG/JPEG, PNG | `image/jpeg`, `image/png` | Signature + declared MIME + extension + ImageIO decode; width/height and total pixels checked before decode; preview re-encoded to same image format without EXIF/metadata. |
| `ORIGINAL_RECORDING` | MP3, WAV | `audio/mpeg`, `audio/wav` | Bounded in-process MP3 frame parser or RIFF/WAVE PCM16 parser. |
| `SYNTHESIZED_PREVIEW` | MP3, WAV | `audio/mpeg`, `audio/wav` | Same technical validation as recording; the role remains a user declaration, not proof of origin or score correspondence. |

Numerical limits from CATALOG-03:

- 1–2 files/request; aggregate original upload ≤20 MiB.
- ≤7 logical assets/material; ≤5 combined score/photo; ≤2 audio.
- Photo ≤5 MiB, ≤20,000,000 pixels and each side ≤12,000 px.
- Audio ≤20 MiB/file, ≤10 minutes, mono/stereo, 8–48 kHz.
- WAV: PCM format 1, 16-bit; MP3: MPEG Layer III frames. No arbitrary native process or `ffprobe` invocation.
- Preview output ≤5 MiB. Original and preview are separate objects and separately charged.
- Existing account quota remains the configured 200 MiB / 100 physical files by default; defaults are not raised by this feature. Derivative files count toward both physical byte and file usage.

## Atomicity, idempotency, ownership and deletion

1. Authenticate from `CurrentActor.accountId`; never accept owner IDs in metadata. Require CSRF, current material ETag and UUID idempotency key.
2. Reserve worst-case original + preview bytes and physical file count in the existing account usage row. Lock order is account usage before material, matching existing storage flows.
3. Store random-key originals and derivatives through `LibraryObjectStore`; add every key to the existing durable upload manifest. Mark COMMITTING before DB commit.
4. Under account/material/operation locks, verify revision and per-material caps; insert all logical assets and advance draft revision exactly once; charge actual bytes/files and release the entire reservation in the same transaction.
5. Any file validation/storage/DB failure aborts the operation and releases reservation. Manifest/orphan recovery handles process interruption. Successful replay checks the stored fingerprint and material ID, then returns the current view without creating objects or charging quota again.
6. Published snapshot remains untouched. Every selected asset still needs its own #165 rights attestation; no audio is required to publish.
7. DELETE marks one asset DELETING and queues a job. The worker locks quota/material, rechecks active publication references, deletes original and derivative, then removes the row and releases both physical file and byte charges. Partial physical failure is retried; DELETING objects are not served. Whole-material deletion removes pending asset jobs and handles every original/derivative through the existing deletion worker.
8. Recovery checks at most 100 READY rows per cycle for missing original/preview bytes, advances `last_storage_check_at`, and marks damaged assets REJECTED. Quota is retained conservatively until owner deletion; the system never advertises a missing object as READY.

## Bounded processing and feature flags

- `guitar-mentor.library.enabled` remains the existing base-library flag.
- New `guitar-mentor.library.community-media-enabled` / `GUITAR_MENTOR_COMMUNITY_MEDIA_ENABLED` defaults to `false`. It requires the base library flag. When off, media upload/delete/preview and media content return 503; the original PDF/GP flow remains available.
- Global media upload concurrency is 2; per-account upload concurrency is 1; media mutations are limited to 10/minute/account. Image decode uses a fixed 2-thread executor with a bounded queue, 30-second timeout, max 20 MP input and 5 MiB input/output. Media-enabled startup requires JVM max heap ≥512 MiB.
- Image/audio parsers are JDK/in-process only, no native command or network fetch. MP3 parsing is bounded by the 20 MiB input limit; WAV parsing validates chunk lengths and caps chunk count at 1,024.
- Multipart transport remains the existing 20 MiB per-file / 32 MiB request configuration. Application-level aggregate media cap remains 20 MiB.
- GET/HEAD use no-store and nosniff. Audio supports exactly one byte range; multiple/invalid/unsatisfiable ranges return 416 with `Content-Range: bytes */<length>`. A missing individual object returns 404 `LIBRARY_FILE_UNAVAILABLE` / `CATALOG_FILE_UNAVAILABLE`; an unavailable storage root returns 503. Deletion/recovery workers pause while the root is unavailable so they cannot release quota or mark every asset missing during a mount outage. HEAD returns headers and no body.

## Migration

`V32__library_community_media_assets.sql` is additive after current main V31. It extends allowed format values, adds role/MIME/audio/image/preview/recovery metadata and an asset deletion queue. Existing V1–V31 migration files and checksums are not modified. Existing PDF assets/snapshots are backfilled with `application/pdf`; existing assets default to `SCORE_ORIGINAL` and READY. No object copy, quota increase or backfill of inferred media occurs.

Rollback: set `GUITAR_MENTOR_COMMUNITY_MEDIA_ENABLED=false`, stop new media operations, keep V32 and all metadata/quotas; do not drop columns or erase assets. Existing PDF/GP endpoints remain active. Re-enable only after the storage root and DB snapshot are consistent.

## Evidence / acceptance matrix

See [`TC-167-community-media.md`](../testing/TC-167-community-media.md) for the test-by-test evidence classification. CI must run Java backend verify with PostgreSQL 17 and real Spring Security/MockMvc HTTP tests. Unit tests and direct controller calls are not counted as independent HTTP sessions. No PILOT/deploy/flag activation is part of this task.

## Existing PDF viewer isolation (reviewed; no duplicate renderer)

The existing `frontend/src/shared/score/ScoreReader.vue` already implements the CATALOG-03 isolation model: sandboxed iframe with only `allow-scripts` (no `allow-same-origin`), CSP with `connect-src 'none'`, locally bundled pinned PDF.js (`pdfjs-dist` 5.4.394), parent-to-frame `MessageChannel` plus nonce and exact `WindowProxy` check, bounded same-origin PDF fetch (20 MiB), ≤100 pages, ≤8 MP canvas, 10-second page render timeout, no XFA/eval, and teardown/cancellation on close or identity change. No second viewer or PDF server renderer is introduced by #167.

The current `pinnedPdfUrl` allowlist is intentionally exercise-specific (`/api/v1/exercises/.../preview/pdf`). #167 does not build the library UI. #168 must extend the URL/identity allowlist for the exact private library and published community `/content` routes while reusing this existing sandbox renderer; it must not accept arbitrary caller URLs or create another PDF viewer. This is the only remaining frontend integration boundary identified by the #167 review.
