# #194 — Private music catalog API

Read-only, account-scoped metadata endpoints. Not the community catalog (#156) or uploaded personal materials (#155).

## Endpoints
- `GET /api/v1/music-catalog/works?q=Yesterday&format=gpx&parserStatus=PARSED&sort=title&limit=20&offset=0` returns `{items,limit,offset,nextOffset}`.
- `GET /api/v1/music-catalog/works/{sourceWorkId}` returns `{work,arrangements:[{arrangement,files}]}`.
- `GET /api/v1/music-catalog/arrangements/{sourceArrangementId}` returns `{arrangement,files}`.

`q` searches literal, case-insensitive substrings of work title and artist (both original and normalized). `format`: gp/gp3/gp4/gp5/gpx/pdf; `parserStatus`: nonempty alphanumeric/underscore (case sensitive); `sort`: title/artist/title_desc. Limit 1–50 (default 20), offset 0–200000. `nextOffset=null` means no further page. An empty query lists only works with arrangements. No count estimate returned.

Security: all reads derive owner from `CurrentActor` (admitted active account); 401 when missing. 404 for another account's or missing detail, no-store on responses. HTTP methods are GET only. All file views include `originalAvailability=UNAVAILABLE`: this index stores metadata alone, never makes D:\\ paths or file bytes downloadable. Source filenames and rights are metadata, not publication permission.

QA: tests for BOLA across two accounts; Cyrillic, literal wildcard characters, filter combinations and pagination; forbidden controls and bad parameters; response no-store and file DTO never exposes source paths, object keys or URLs. PostgreSQL migration V27 is already installed locally and covered by Java backend CI.

Follow-ups: `#195` user interface; `#196` separately authorized original-file ingestion. Offset pagination is deliberately simple; benchmark against 77k corpus and upgrade to stable cursor if measured latency is inadequate, without relaxing owner scoping.
