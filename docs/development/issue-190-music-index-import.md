# #190 — Private music index: preparation and import

This is **not** the existing user-upload flow from V25. V27 adds a private metadata-only index. There are deliberately no new HTTP endpoints, no storage URLs, and no automatic access to local source files.

## Source
Keep `GuitarMentor-Index-v1.zip` unpacked under `D:\GuitarLibrary\Index\v1`. Keep the source CSVs **outside Git**; the repository is public. Expected dry-run counts: works 66,649; arrangements 77,596; files 77,596; review entries 24,222. Provisional arrangements are one per source file; no musical identity is verified.

## Validation (safe; no DB connection)
On Windows with Python installed:
```powershell
cd D:\Development\guitar-mentor-ai
py -3 scripts\music_catalog_import.py --directory 'D:\GuitarLibrary\Index\v1'
```
This checks CSV headers, identifiers, relationships, duplicated paths and PRIVATE flags. It does **not** read, move or upload original scores.

## DB migration and controlled import
1. Review/merge PR, apply Flyway V27 in a controlled local test database first.
2. Set an existing **ACTIVE** account's UUID; do not use an arbitrary UUID. Never paste credentials into the chat or put them in Git.
3. Install the optional dependency: `py -3 -m pip install "psycopg[binary]"`.
4. Configure `MUSIC_CATALOG_DSN` via a local environment variable (do not print/share it).
5. Only after backup and review, run:
```powershell
py -3 scripts\music_catalog_import.py --directory 'D:\GuitarLibrary\Index\v1' --apply --account-id '<ACTIVE_ACCOUNT_UUID>'
```
All source IDs are scoped by owner, so repeat import performs upserts. The four CSV imports are executed in a single transaction. Failures roll back the data transaction; no score bytes enter the DB.

## Important limits
- This stage is **schema + offline importer**, not a finished searchable UI.
- Existing `library_materials` and `library_assets` retain their own upload quota, validation, object storage and access control; do not backfill them with file paths.
- `PRIVATE` is enforced by the index schema. Rights are not inferred from filenames. Materials marked `private_course` remain private.
- Avoid direct SQL access for ordinary web users; application queries must always filter by `owner_account_id`. The DB application account itself is not subject to row-level security in this first version.
- Fields ending `_raw` deliberately preserve text from parsers; do not equate note counts to playing difficulty.
- Manual confirmation required before publishing, matching arrangements, and generating derivative content from rights-restricted materials.

## Review/acceptance checkpoints
1. Validate counts in dry-run.
2. Flyway V27 up/down compatibility reviewed (Flyway does not auto-rollback migrations).
3. Import in a disposable PostgreSQL test DB, rerun import and verify same row counts.
4. Test fail-closed behavior for invalid account and non-private entries.
5. Implement account-scoped search APIs separately, with authorization tests, before UI exposure.
