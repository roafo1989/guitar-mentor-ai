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

Run `pwsh -File .\scripts\qa216-acceptance.ps1 -IncludeBrowser -IncludeIsolatedPostgres` after updating the feature branch. By default this creates a **new randomly named QA database on the user's existing PostgreSQL at 127.0.0.1** (without Docker), runs `NextLessonPostgresIntegrationTest` plus `SecurityHttpIntegrationTest`, and removes only that QA database after verifying its random ownership marker. DEV and PILOT databases are never used as test targets. The script locates psql.exe (including standard Windows PostgreSQL directories) and uses the local postgres maintenance database to create the isolated QA database. A PostgreSQL account with CREATE/DROP DATABASE privileges and a password is necessary; credentials are never committed. Optional `-UseDocker` selects the previous separate Docker-based runner and requires an existing postgres:17-alpine image.

**This is not TC-24**: it proves fresh-schema integration and core HTTP checks, **not** upgrading an existing PILOT-shaped database from V28 to V29 or backup/restore/rollback. The existing `db-copy-migrate-restore` gate stays SKIP until separately executed. Do not mistake the new PostgreSQL PASS for evidence about existing-data migration or two real account sessions: `http-two-actors-csrf` remains SKIP pending real HTTP tests, even though synthetic MockMvc checks execute.

The disposable script was added remotely and has **not yet been run on the user's Windows Docker environment**. Its exit status is recorded independently; raw logs remain local and must be reviewed for secrets before sharing.


**Safety:** the local runner creates a fresh `qa216_<random>` database only after connecting to the `postgres` maintenance database on loopback. It never invokes `DROP` for DEV or PILOT; cleanup requires verification of the random marker on the database object. PostgreSQL tools and admin rights must already be available. This still does not run a V28-to-V29 migration of a *copy of existing PILOT data*, which remains an independent release gate.


## One-command main integration

Run on the clean feature branch: `pwsh -NoProfile -File .\scripts\qa216-finalize.ps1`. It fetches, fast-forwards feature, tries a standard three-way merge of main, and stops on conflicts without resolving them. If merge succeeds, it runs complete local browser and isolated PostgreSQL tests and pushes only the feature branch if >=8 PASS, 0 FAIL, and PostgreSQL PASS. It does not merge the PR into main, deploy or update PILOT, or close remaining release gates. Conflicts are reported in `.qa-local/216/finalize/conflicts.txt`.
