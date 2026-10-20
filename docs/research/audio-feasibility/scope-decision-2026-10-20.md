# Decision: #14 technical acceptance and #97 real-music corpus verification

**Date:** 2026-10-20  
**Decision:** approved by project owner in conversation, after the real-corpus verification gate was found blocked.  
**Traceability:** [#14](https://github.com/roafo1989/guitar-mentor-ai/issues/14) · [#97](https://github.com/roafo1989/guitar-mentor-ai/issues/97) · [#15](https://github.com/roafo1989/guitar-mentor-ai/issues/15) · parent US-005 [#59](https://github.com/roafo1989/guitar-mentor-ai/issues/59).

## Purpose

Separate shippable, *offline technical research tooling* from data-dependent musical ground-truth acceptance. This **changes the acceptance boundary, not the underlying requirements**: every former unresolved real-corpus requirement remains explicitly assigned to #97. The original requirements/specification and historical acceptance criteria in #14 remain visible for traceability.

## #14 — offline benchmark infrastructure (technical acceptance)

Accept independently from the real music corpus:
- Public corpus/score/annotation manifest v1 contracts and recording/provenance protocol, respecting privacy, consent and no external upload.
- Offline Java 21 read-only validator using SHA-256 and ffprobe, safe path checks, status codes `READY_FOR_POC / INCOMPLETE / INVALID`, no product DB/API/storage/TTL changes.
- Synthetic fixtures, negative input/security tests, non-skipped positive synthetic validator integration and reproducible CI evidence.
- Metric/abstention protocol and explicit *no musical accuracy claim*; source files absent/rejected/withdrawn never produce false readiness; no holdout, missing rights or missing human review must block readiness.
- Documentation/rollback/reproduction and private pilot inventory *as exploratory evidence only*.

**Technical acceptance checks:** 
- [ ] T14-01 Contract, scope/rights/privacy and source provenance documented; no real media, original score images or personal answers committed.
- [ ] T14-02 Validator rejects corrupted media, hash mismatch, invalid reference/event/time/path/symlinks and unsupported formats as documented.
- [ ] T14-03 Missing rights, human event review or independent holdout returns INCOMPLETE (not READY); only synthetic harness may reach a test READY.
- [ ] T14-04 Metrics/abstention/zero denominator/no blind holdout are unambiguously documented; no algorithm accuracy stated.
- [ ] T14-05 CI tests and positive ffprobe integration pass, with skip protection; reproducible invocation and rollback instructions available.
- [ ] T14-06 No production endpoints, database, upload worker or storage TTL changes; issue testing and owner sign-off still required.

A **successful synthetic `READY_FOR_POC` test is not a real-data result**. If the real private pilot is incomplete, technical #14 can nevertheless be accepted when T14-01…06 pass. #14 should enter *Testing / Queue* pending the project's testing process, not be immediately merged or closed.

## #97 — remaining real-corpus data acceptance

Transfer the **real-world demonstration** parts of original AC14-02 / AC14-03 / AC14-07, plus actual data-evidence portions of AC14-01 / 06 / 08 / 10, to [#97](https://github.com/roafo1989/guitar-mentor-ai/issues/97). The original AC14-04 / 05 / 09 and structural/security/abstention portions of others remain verified under technical #14.

In particular, #97 requires legal/source provenance of notation, independently reviewed music events and timing, explicit uncertainty and false click/onset handling, a real offline manifest with immutable hashes, and an actual **Java validator run** on an approved private REAL corpus. It must not mark `manualConfirmed=true` from AI heuristics or performer self-review alone.

Original six takes are physically distinct per owner, but **all have already been inspected**, so the proposed CALIBRATION/HOLDOUT split is not genuinely blind; this limitation persists. A fresh unseen holdout is required for claims of unbiased generalization. The owner's LOCAL_RESEARCH-only consent for their own performances **does not permit sending media to third parties**. Any proposed independent reviewer workflow that requires disclosure needs separate explicit consent; otherwise choose an alternative permitted protocol.

## #15 — diagnostic PoC allowed before #97 closes

[#15](https://github.com/roafo1989/guitar-mentor-ai/issues/15) may begin **analysis/planning and bounded diagnostic implementation** after technical #14 is accepted. Allowed:
- Synthetic or owned/licensed controlled tasks, exploratory detection of bass/onset/pause/tempo, profiling of latency and local computational cost.
- Owner's original six recordings only within their existing LOCAL_RESEARCH scope, with `DIAGNOSTIC_ONLY` status; no rebranding of a previously inspected take as independent blind HOLDOUT.
- Raw candidate tables, confusion hypotheses, error/uncertainty categories, and reproducible **exploratory** comparisons labeled as non-validated.
- Abstain / `METHOD_UNVALIDATED` output where evidence is insufficient.

**Prohibited until #97 is accepted:** assertion of validated precision/recall/F1, verified bass note correctness, calibrated confidence, reliable generalization or a definitive musical GO decision; any automatic student skill confirmation or deployment of unvalidated musical evaluation. Original full #15 acceptance and #31 GO/LIMITED/NO-GO gates are **not lowered**.

## Workflow/traceability

- #14: **Testing / Queue (requested)** once branch/docs/code satisfy technical implementation; CI is evidence, not complete owner acceptance. PR #73 goes to normal code review/testing; no auto-merge/close.
- #97: **Backlog / Queue** pending independent assessment, effort estimate and a privacy-compliant reviewer/source plan.
- #15: **Analysis / Queue (recommended next)**; diagnostic development requires the usual explicit selection/approval and is not started by this decision.
- #59 story and #53 epic remain open. No claim to update the GitHub Projects v2 custom `Stage/Status` fields unless the board API write itself succeeds.

Reference these links in all three issues and PR #73. Keep all personal audio, notation photographs, hashes, self-review JSON and full private annotations **off GitHub**.
