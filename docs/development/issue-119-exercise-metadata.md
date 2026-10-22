# Issue #119 — Java exercise learning metadata

API fields appended to every ExerciseView in catalog, detail, and nested task:
`learningGoal` (nullable), `practiceInstructions` (nullable),
`successCriteria` (always a string array), `tuning` (nullable),
`recommendedPracticeMinutes` (nullable integer).
No public authoring/write API is introduced.

Flyway V17 adds three nullable authoring columns and validates nonblank
content and maximum lengths at PostgreSQL level. Existing practice minutes
(V12) and `exercise_criteria.observable_result` remain authoritative.
No JSONB copy, backfill, or synthetic GM-EB02–05 material is created.

`ExerciseViewAssembler` performs one parameterized criteria query for
an entire catalog batch; task assignments use the persisted exercise UUID,
not a lookup of the current version. Criteria describe learning targets,
not verified learner achievements. No changes to filtering, rights,
assessment, or media retention.

## Verification

Local environment: Java 21, PostgreSQL test database, Flyway.
From `backend` with the isolated testing connection configured:

```bash
GUITAR_MENTOR_TEST_POSTGRES=true ./mvnw --batch-mode verify
```

Tests include the existing diagnostic assignment regression suite, a
single-query assembler test, V12 seed regression, DB boundary checks,
and an older-version UUID fixture. Never point the integration suite to
a production/personal database.

Runtime execution and CI outcome must be recorded in the PR separately;
documentation is not evidence that commands were run.
