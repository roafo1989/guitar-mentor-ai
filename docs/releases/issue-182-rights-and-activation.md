# #182 — rights record and guarded activation (24 October 2026)

## Material and decision
GM-DIAG-PULSE-3-8-01 version 0.1.0 is a 12-measure, 36-eighth-note
procedural technique pattern created specifically for Guitar Mentor AI.
The owner accepted its pedagogical use on 2026-10-24 under #145.
Resources: score.musicxml, score_events.json, preview_v010.pdf and
preview_v010.mp3 in backend classpath.

## Rights basis / limits
The guitar exercise events were authored as project content; PDF notation
and MP3 are generated from the same project event source, rather than
copied from a commercial score or recording. The project records the
publication basis as ORIGINAL_OWNED (distribution controlled by the project
owner), not CC BY or public domain. This records the project's provenance
assertion, **not** a legal determination that AI-generated content has
copyright protection in any jurisdiction. Do not claim exclusive rights to
unprotectable procedural patterns. Re-review provenance if external samples,
soundfonts, fonts or third-party score elements are added; this record
does not purport to clear their separate licenses. Source generator
scripts/diag145/generate_media.py and bundled output are the audit trail.
Any future version must undergo its own acceptance and rights review.

## Activation / rollback
Flyway V26 is additive (after the library's V25): it checks there is exactly one version and the
expected approved source metadata before transitioning V24's draft to
is_current=true, license=ORIGINAL_OWNED and review_status=APPROVED.
V24 is NOT edited. It does not touch GM-EB01 or previously assigned tasks.
Do not modify an already-applied migration to roll back publication: issue
a new migration that sets is_current=false and review_status=REJECTED
after incident review. Existing version-pinned assignments should remain
auditable.

## Acceptance smoke checklist
- PostgreSQL/Flyway migrations run on a clean DB and an upgraded V24 DB.
- Catalog includes 0.1.0 with 3/8 and tempoUnit=eighth (eighth=100 BPM).
- GET and HEAD version-pinned /preview/pdf and /preview/audio return
  application/pdf and audio/mpeg respectively; PDF has staff+TAB.
- Browser opens PDF and plays MP3 (manual, not demonstrated by CI).
- Missing/unreviewed and rejected probes remain hidden, and only a single
  version can be current per code.
- Assign probe to a ready learner session; upload recording; verify that
  upload alone does not mark the musical criteria PASS.
- Confirm existing GM-EB01 assignments and media still work.
- Run backend PostgreSQL/HTTP and frontend tests; user acceptance remains
  a separate gate. Audio grading is explicitly out of scope.
