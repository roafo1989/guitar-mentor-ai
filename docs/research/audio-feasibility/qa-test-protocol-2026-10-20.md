# #14 — Technical QA test protocol (20.10.2026)

**Status:** ACCEPTED — technical T14-01…06 accepted by the product owner on 2026-10-20, after reviewing the QA summary and explicitly authorizing completion. Outstanding REAL musical validation remains #97.  
**Scope:** the owner-approved technical-only T14-01…06; [scope decision](scope-decision-2026-10-20.md).  
**Code baseline:** `875deb4549f804a192fa31d43b4bc12eba8a8efe`; subsequently only editorial corrections to README/recording-protocol and this report.  
**PR:** [#73](https://github.com/roafo1989/guitar-mentor-ai/pull/73), fourth code review [PASS](https://github.com/roafo1989/guitar-mentor-ai/pull/73#pullrequestreview-5584958409).  
**Evidence source:** exact GitHub Actions run IDs below and source inspection. **Do not interpret CI on synthetic data as real musical validation.**

## Evidence and confidence

| Evidence | ID | Observed outcome |
|---|---|---|
| Dedicated offline validator CI | 39417517914 | SUCCESS. `AudioCorpusValidatorTest`: 11 tests, 0 failures/errors/skips. `AudioCorpusReadinessTest`: 2 tests, 0 failures/errors/skips. Total **13**, all passed; explicit no-skip verification passed. `ffmpeg` and `ffprobe` installed before test execution. |
| Java backend CI | 39417517651 | SUCCESS. **124** tests, no failures/errors, **4 skips in the general suite**. PostgreSQL/Flyway startup and product HTTP smoke successfully completed. The separate audio CI above, not the general job, proves positive audio tests ran without skips. |
| Vue frontend CI | 39417517691 | SUCCESS. Typecheck/build passed. |
| PR changed-file audit | PR #73 file list | 13 paths confined to backend research Java/test classes, dependency configuration, an audio workflow and public research documentation. No changes to product REST controllers, migrations, entity model, media TTL/upload worker, S3 or web UI. |
| PR public-content inspection | Source and docs | Changed paths are text source/docs/synthetic JSON only; searched for local `/mnt/data` paths, embedded audio/image payloads and supplied private audio/image filenames — none found. This is a targeted PR inspection, not a proof about the entire repository or hidden service logs. |
| Local CLI reproduction | Independent working environment | **NOT RUN**: Maven/Jackson dependency runtime is not provisioned in the QA container and direct GitHub clone is not available there. CI executed the compiled Java validator and its full synthetic tests. Reproducible Java 21 invocation and exit codes are documented in README. |
| Real musical corpus | [#97](https://github.com/roafo1989/guitar-mentor-ai/issues/97) | **OUT OF SCOPE / INCOMPLETE**: private six real takes do not have independently confirmed musical events or a genuinely blind holdout. No real musical READY or accuracy is asserted. |

**Status vocabulary:** PASS-CI = validated by the named GitHub CI job; PASS-INSPECTION = reviewer checked source/docs/changed-file list; NOT-RUN = no execution evidence; PENDING-OWNER = final product-owner acceptance. No secret user media is included.

## Technical cases and observed results

| Test ID | T14 | Scenario and expected outcome | Evidence | Result |
|---|---|---|---|---|
| QA14-01 | 01 | Contract defines manifest/reference/annotation types, origins/rights/consent, splitting and privacy without hidden real-media dependency. | `contract.md`, `recording-protocol.md`, `scope-decision`, `README` inspected | PASS-INSPECTION |
| QA14-02 | 01 | Public PR contains no private recordings, photos of notation, personal feedback or absolute local paths. | 13 changed paths + source/fixture scan | PASS-INSPECTION |
| QA14-03 | 02 | Valid **synthetic six-take structural harness** yields `READY_FOR_POC`; it is explicitly NOT real performance evidence. | `AudioCorpusReadinessTest`, audio CI run 39417517914 | PASS-CI |
| QA14-04 | 02 | Bad byte/hash, missing source, malformed manifest, oversized dataset and unreachable/offline refs do not return READY. | Validator/Readiness tests + contract | PASS-CI |
| QA14-05 | 02 | Traversal, absolute path, URL, symlink within corpus and symlink above corpus root are rejected. | `AudioCorpusValidatorTest` incl. symlink tests | PASS-CI |
| QA14-06 | 02 | Missing meter/voice, wrong beat, non-note string, unknown eventId, invalid observation bounds/voice cause `INVALID`. | `AudioCorpusReadinessTest` negative scenarios | PASS-CI |
| QA14-07 | 02 | WAV/MP3/OGG/WebM positives; M4A extension spoof, container mismatch, unsupported WAV codec negatives. | positive `ffmpeg` encode and `ffprobe` tests in audio CI | PASS-CI |
| QA14-08 | 02 | Derived REAL must have valid original SHA+safe path+transform; parent hash tampering and missing transform yield `INVALID`. | `AudioCorpusReadinessTest` provenance regression | PASS-CI |
| QA14-09 | 03 | Two distinct WAV derivatives of **same parent M4A**, split CALIBRATION/HOLDOUT, yield `INVALID`; two originals under same group fail. | Cross-lineage regressions committed in `533e0184` | PASS-CI |
| QA14-10 | 03 | Extra derivative in **same** group/split is allowed but counted once for independent coverage. | `AudioCorpusReadinessTest` additional sibling/coverage scenario | PASS-CI |
| QA14-11 | 03 | Missing rights, review or holdout-independence declarations produce `INCOMPLETE`, not fabricated READY. | `AudioCorpusReadinessTest`; `AudioCorpusValidatorTest` | PASS-CI |
| QA14-12 | 03 | `annotation.manualConfirmed=false` gives `INCOMPLETE`, malformed string pseudo-boolean gives `INVALID`; schema numeric strings rejected. | dedicated readiness regression tests | PASS-CI |
| QA14-13 | 03 | Synthetic-only/empty corpus stays `INCOMPLETE`. | ValidatorTest `syntheticOnlyIsNeverReady` | PASS-CI |
| QA14-14 | 04 | Explicit one-to-one event matching, zero denominator `NOT_APPLICABLE`, abstain categories, no-blind-holdout gate, no invented F1/accuracy. | `metrics.md`, `scope-decision`, `README` | PASS-INSPECTION |
| QA14-15 | 04 | No automatic student skill award or ungrounded GO based on an exploratory six-take pilot; #15/#31/#97 separation intact. | `metrics.md`, scope decision, PR changed paths | PASS-INSPECTION |
| QA14-16 | 05 | Positive audio integration actually runs and cannot silently skip; 13 tests passed with zero skips. | audio CI log, no-skip XML test step | PASS-CI |
| QA14-17 | 05 | Backend and frontend pass existing product regressions and runtime checks. | backend CI and frontend CI runs above | PASS-CI |
| QA14-18 | 05 | Java 21 local invocation, statuses/exit codes and rollback of temporary outputs are documented without modifying originals. | README's Running/Readiness/Rollback sections | PASS-INSPECTION |
| QA14-19 | 05 | Independent manual invocation of packaged Java CLI with `--root/--manifest/--report/--offline`. | No Maven/Jackson local environment; CI covers lower-level main classes via tests. | NOT-RUN (non-blocking for documented technical gate, disclose) |
| QA14-20 | 06 | No production API, database migrations, upload pipeline or media retention implementation changed in PR. | 13 changed paths, diff scope and product CI | PASS-INSPECTION |
| QA14-21 | 06 | Technical code-review PASS on frozen implementation revision. | Review #5584958409 | PASS-INSPECTION |
| QA14-22 | 06 | Product-owner decision after reviewing technical QA results. | Owner authorized acceptance and completion in the conversation on 2026-10-20, after seeing the 20 PASS / 1 NOT-RUN / 1 pending summary. | PASS-OWNER |

## QA notes / limitations

1. **Minor documentation inconsistency corrected** during testing: `recording-protocol.md` previously claimed real takes had not been provided, and README still called #73 Draft; both were aligned with the real, privacy-preserving status without adding recordings or changing Java code.
2. Synthetic fixtures use `kind=REAL` deliberately to exercise readiness control flow. Their `manualConfirmed:true` and `rightsConfirmed:true` fields are **test doubles**, not evidence of human attestation. No such field was changed for a private real take.
3. The displayed statuses and test counts are grounded in CI job logs, not a separate local Maven execution. Four skipped tests in the general Java backend suite are distinct from the **zero skipped audio tests** in the dedicated workflow.
4. Private data remain LOCAL_RESEARCH-only, and no third-party sharing permission was requested or assumed. All REAL musical review, notation rights, and prospective blind-holdout tests belong to #97.
5. The product owner's acceptance authorizes PR merge and #14 closure; the final merge SHA and closure result are tracked in GitHub PR/Issue history, not forecast here. GitHub Projects v2 custom Stage/Status fields require separate board synchronization because the available connector has no write action.

**Final technical QA disposition:** 22 cases — **20 PASS-CI/PASS-INSPECTION, 1 PASS-OWNER, 1 NOT-RUN**. No blocking defect found in the approved T14-01…06 technical scope. The missing standalone local CLI smoke test was disclosed to and accepted by the owner as a limitation; the Java validator's positive/negative synthetic integration tests ran in GitHub CI. **Technical #14 ACCEPTED for merge and closure**. This does not make the private REAL corpus READY_FOR_POC, nor does it accept any real note/accuracy/rights evidence; those are #97. No owner permission for third-party disclosure has been inferred.
