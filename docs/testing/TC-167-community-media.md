# TC-167 — community photo/audio media

Basis: CATALOG-03 §5–8 (recovered from the immutable specification link in Issue #167), MUSIC-01 ownership rules, existing #160–166 APIs and the accepted private-storage/recovery implementation. `AGENTS.md` and the referenced pre-existing `TC-CATALOG-02-community.md` are not present in current `main`; this test plan is the implementation-specific traceability record, not a claim that those files were executed.

## Automated evidence and test layers

| Layer | Test | Intended evidence |
|---|---|---|
| Unit | `LibraryMediaInspectorTest` | Real JDK ImageIO decode/re-encode, EXIF removal, JPEG/PNG signatures, dimension/size bounds, MP3 frame structure plus full pinned JLayer payload decoding (valid tracked MP3 fixture and valid silent frames accepted; non-decodable/truncated payload rejected), WAV RIFF/PCM16 metadata, duration/channel/sample-rate/format rejection. |
| PostgreSQL + Spring Security + MockMvc | `LibraryMediaHttpPostgresTest` | Actual Flyway schema and PostgreSQL transactions; real Spring Security filter chain with OIDC test identities, CSRF, CurrentActor resolution, two account identities, private/publication membership, protected preview/content, ETag/idempotency, Range/HEAD, withdrawal, quota and deletion worker. |
| Existing PostgreSQL regressions | `LibraryOriginalPostgresTest`, `LibraryOriginalHttpPostgresTest`, `LibraryPublicationPostgresTest`, `LibraryPublicationHttpPostgresTest`, `LibraryDeletionWorkerPostgresTest`, `LibraryOrphanRecovery` coverage | PDF/GP upload/download, ownership, existing storage reservations/recovery, publication lifecycle and previous quota/deletion behavior. |
| CI bootstrap | `.github/workflows/java-ci.yml` | Flyway migration and full backend verify on PostgreSQL 17; application bootstrap/HTTP smoke. |

Do not interpret unit tests as HTTP, direct controller tests as independent sessions, or MockMvc as a live browser. The media HTTP test uses separate authenticated test identities and the real configured security filter chain, but it is not an external browser test.

## Test cases

| ID | Scenario | Expected result | Automated coverage |
|---|---|---|---|
| M-01 | Valid JPEG and PNG | Decode; dimensions recorded; sanitized preview available; original remains byte-exact | Inspector unit + PostgreSQL HTTP |
| M-02 | JPEG EXIF metadata | Preview is re-encoded and contains no EXIF marker; original remains unchanged | Inspector unit |
| M-03 | Spoofed extension, MIME or signature | Reject; no asset/revision/quota mutation | Inspector unit; HTTP atomic-invalid batch |
| M-04 | Corrupt image / unsupported image | 400; no preview; no READY asset | Inspector unit |
| M-05 | Photo >5 MiB, side >12,000 px, >20 MP | Reject before full decode; 413 | Inspector unit |
| M-06 | Valid MP3 frames and WAV PCM16 | Correct duration, channels and sample rate persisted | Inspector unit + PostgreSQL HTTP |
| M-07 | Non-decodable/truncated MP3 payload, fake WAV, extension/MIME mismatch, corrupt stream | Reject; no READY asset | Inspector unit; real MP3 payload decoded with JLayer |
| M-08 | WAV non-PCM16, >2 channels, out-of-range sample rate or >10 min | Reject | Inspector unit |
| M-09 | MP3 >10 min or >20 MiB | Reject; no READY asset. Decoder timeout (30 s), two workers and queue capacity two are code-reviewed bounds; saturation/timeout stress is not claimed as executed | Inspector unit + bounded executor source review |
| M-10 | More than 2 files/request or >20 MiB aggregate | Reject before commit | Controller validation; CI HTTP suite |
| M-11 | Multiple files where second file is invalid | No first-file residue, no revision increment, no retained reservation/quota | PostgreSQL HTTP |
| M-12 | Stale material ETag | 412; no mutation | PostgreSQL HTTP |
| M-13 | Same idempotency key + same bytes + old original ETag | 200 replay; same assets/revision; no duplicate bytes/files/quota | PostgreSQL HTTP |
| M-14 | Same key + different file fingerprint | 409; no duplicate asset/quota | PostgreSQL HTTP |
| M-15 | Two accounts, guest and CSRF | Owner access succeeds; foreign private draft/preview is 404; guest 401; write without CSRF 403 | PostgreSQL HTTP |
| M-16 | Private sanitized preview vs original | Preview URL serves derivative only; original endpoint serves original attachment bytes | PostgreSQL HTTP |
| M-17 | Private and published audio GET/HEAD | Correct MIME, no-store/nosniff; HEAD has no body | PostgreSQL HTTP |
| M-18 | Single valid byte range | 206 with exact Content-Range and byte count | PostgreSQL HTTP |
| M-19 | Multiple/invalid/unsatisfiable range | 416 with `Content-Range: bytes */length` | PostgreSQL HTTP |
| M-20 | Published media membership | Other admitted account can read only snapshot assets; private draft remains hidden | PostgreSQL HTTP |
| M-21 | `hasAudio=true` / `false` / omitted | True includes only published READY audio; false excludes audio; omitted has no audio filter | PostgreSQL HTTP |
| M-22 | Withdraw/HIDE publication | New content/preview requests return 404; no stale snapshot bytes | PostgreSQL MockMvc (LibraryMediaHttpPostgresTest) + existing publication tests |
| M-23 | Delete asset referenced by active publication | 409; no mutation | PostgreSQL HTTP |
| M-24 | Delete unreferenced audio asset | 202; worker removes original/derivative and releases physical quota; repeated/missing cleanup is safe | PostgreSQL HTTP + worker test |
| M-25 | Last score/photo deletion | 409 `CATALOG_LAST_SCORE` | PostgreSQL HTTP (`LibraryMediaHttpPostgresTest`) |
| M-26 | Missing original/preview bytes | 404 on read; recovery marks damaged READY row REJECTED; no empty 200 | PostgreSQL HTTP + recovery in `LibraryMediaHttpPostgresTest` |
| M-27 | Media feature flag off | New media upload/delete/preview/media-content 503; capabilities report no media roles/limits and legacy 5-file contract; PDF/GP create/download unchanged | PostgreSQL HTTP (`LibraryOriginalHttpPostgresTest`) |
| M-28 | Recovery after interrupted staging/commit | Existing manifest releases reservation and sweeps only unreferenced original/preview keys | PostgreSQL recovery test (`LibraryMediaConcurrencyPostgresTest`) |
| M-29 | Concurrency: same account starts two media uploads | Per-account gate admits one; second receives 409; no lost quota, duplicate asset or revision | PostgreSQL direct-controller concurrency test (`LibraryMediaConcurrencyPostgresTest`); not an HTTP-session test |
| M-30 | Add photo after publication, then withdraw the old publication and explicitly publish the new revision with per-asset rights | Draft revision advances without mutating old snapshot; explicit replacement publishes the new asset set and withdraws the old publication | PostgreSQL MockMvc (LibraryMediaHttpPostgresTest) |
| M-31 | `SYNTHESIZED_PREVIEW` role | MP3/WAV metadata validated; role is explicit; review remains UNREVIEWED and no musical approval is inferred | PostgreSQL HTTP (`LibraryMediaHttpPostgresTest`) |

| M-32 | Existing PDF sandbox viewer regression | Keep iframe sandbox/CSP/no-network, pinned PDF.js, bounded same-origin bytes, page/canvas/render limits and cleanup; #168 extends exact library route allowlist while reusing this renderer | Source/lockfile review; no frontend files changed by #167 |

## Traceability to the shared catalogue test plan

The earlier `TC-CATALOG-02-community.md` was recovered from `analysis/system-analysis-standard` (it is absent from main). #167-specific cases are covered as follows:

| TC-COM | Scope in shared plan | #167 evidence |
|---|---|---|
| 11 | Photo originals and sanitized preview | M-01–05, M-16 |
| 12 | Malformed/oversize media and bounded processing | M-03–10 |
| 13 | Add audio after publication; explicit new publish; separate source/synth roles | M-13, M-20–21, M-30–31 |
| 14 | Audio Range/HEAD and withdrawal | M-17–19, M-22 |
| 15–16 | Isolated PDF preview, hostile/corrupt/large PDF fallback | Existing `ScoreReader` source/lockfile/browser-contract review (M-32). Library-route integration remains #168; no second renderer is created here. |
| 18–19 | Concurrent mutation, storage faults, commit/recovery | M-11–14, M-23–29 |
| 22–24 | Anonymous/CSRF/rate/flag-off, delete/retry and rollback | M-15, M-23–24, M-27–29 |

`TC-CATALOG-02` is a planning document whose rows are NOT_RUN; the crosswalk does not relabel its whole US end-to-end suite as passed. #167 evidence remains limited to the actual backend and viewer-source checks listed in this document.

## Review and CI acceptance evidence

Self-review (not an independent human review) corrected the findings recorded in FT-012-03: audio search SQL syntax; private/public audio Range response converter; old PDF MIME backfill; stale ETag on repeated deletion; deletion-job cleanup; last usable score/photo guard; disabled capability values; and MP3 payload validation. The final implementation adds bounded JLayer decoding, and the photo/audio routes are covered by PostgreSQL-backed Spring Security/MockMvc.

Final PR-head verification on `14aa2c90e7935629224b864cedb88874cdc9f54b` is running; the immediately preceding implementation SHA `df4dde97b990dbd58431a500efb744df653a8762` passed all three applicable workflows:
- Java backend CI run `41222830562`: SUCCESS; Java 21, PostgreSQL 17.11; 401 tests, 0 failures, 0 errors, 4 skips; Flyway V32, app bootstrap and existing live HTTP smoke passed.
- Vue frontend CI run `41222830486`: SUCCESS; typecheck/build, PDF.js provenance and real Chromium isolated ScoreReader passed.
- Audio benchmark validator run `41222830483`: SUCCESS; positive and negative corpus checks passed.

Test-layer honesty: LibraryMediaInspectorTest is unit; LibraryMediaHttpPostgresTest is PostgreSQL-backed Spring Security/MockMvc with separate mock identities/sessions, not independent live HTTP clients; LibraryMediaConcurrencyPostgresTest is direct-controller concurrency against PostgreSQL, not HTTP; the Java workflow's live HTTP smoke covers existing app/exercise endpoints, not the new media upload routes. The #167 photo/audio secured endpoints have not been represented as live-network HTTP smoke.

Any failed test is a defect to fix, not an item to hand to the owner as optional checking.

## Owner acceptance (after PR is ready)

The owner need only review the PR summary and, if desired, exercise one product flow after the feature is deliberately enabled in a non-production/test environment. No PILOT deployment or flag activation is part of #167. The task is not merged or closed until explicit owner acceptance.
