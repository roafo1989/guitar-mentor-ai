# Automated acceptance launcher — issue #216

The Windows PowerShell 7 entry point is `scripts/qa216-acceptance.ps1`.

## One command

From the local repository root in the **feature/216-manual-next-lesson** branch:

```powershell
pwsh -File .\scripts\qa216-acceptance.ps1
```

The script does **not** run `git pull`, merge branches, install dependencies, start/stop services, connect to a database, invoke production URLs or change PILOT. It runs available npm unit/contract suites and build, and Maven tests. It requires existing `frontend/node_modules`; if absent, frontend tests are explicitly `SKIP` rather than silently installing packages. For additional Chrome tests (which use mocked API), run `pwsh -File .\scripts\qa216-acceptance.ps1 -IncludeBrowser`. Chrome must be installed and usable.

## Output and interpretation

JSON results and local logs are under `.qa-local/216/<timestamp>/` (gitignored). The report contains the commit SHA and one entry per check with **PASS**, **FAIL** or **SKIP**. Log files may contain test data; keep them local, redact before sharing. The command exits nonzero for a failed automated check; exit code zero **does not** mean complete release acceptance if checks were skipped.

Manual/isolation gates are always SKIP until separately executed and documented:

- V29 migration/backup/restore rehearsal on a new disposable PostgreSQL database — TC-24.
- Real two-account authenticated HTTP authorization/CSRF checks — TC-18.
- Media TTL/archived task uploads, restart/retry/races, and interactive recording/keyboard accessibility — TC-09/10/11/12/22/23.

For isolation rules and stop conditions, see [RC-216-RELEASE-GATE.md](RC-216-RELEASE-GATE.md). Never copy live PILOT credentials into reports, and never run migration tests against the live PILOT DB.

## Release decision

This script is a repeatable partial acceptance tool, **not** proof that all 24 test cases passed. PR #217 remains open until the owner approves release after concrete evidence for the remaining gates. Do not update PILOT merely because this script exits zero.


## Optional isolated PostgreSQL integration tests

Run `pwsh -File .\scripts\qa216-acceptance.ps1 -IncludeBrowser -IncludeIsolatedPostgres` after updating the feature branch. This starts **a brand-new, disposable, loopback-only** PostgreSQL 17 container through Docker Desktop, runs `NextLessonPostgresIntegrationTest` plus `SecurityHttpIntegrationTest`, and destroys the container afterward. No database dump, volume mount or existing PostgreSQL endpoint is used. A cached `postgres:17-alpine` image is required; the script deliberately does **not** pull images. If Docker Desktop or the cached image is unavailable, the new check reports **SKIP**.

**This is not TC-24**: it proves fresh-schema integration and core HTTP checks, **not** upgrading an existing PILOT-shaped database from V28 to V29 or backup/restore/rollback. The existing `db-copy-migrate-restore` gate stays SKIP until separately executed. Do not mistake the new PostgreSQL PASS for evidence about existing-data migration or two real account sessions: `http-two-actors-csrf` remains SKIP pending real HTTP tests, even though synthetic MockMvc checks execute.

The disposable script was added remotely and has **not yet been run on the user's Windows Docker environment**. Its exit status is recorded independently; raw logs remain local and must be reviewed for secrets before sharing.
