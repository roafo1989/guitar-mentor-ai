# Metrics protocol v1 — planned for #15

There are **no measured algorithm results** as of this revision. Freeze matching and quality rules on CALIBRATION before evaluating HOLDOUT; never tune on holdout, mix transcoded copies across partitions, or silently discard difficult inputs.

- **Onset:** one-to-one maximum-cardinality pairing within declared `matchingWindowMs`; TP matches, FP detected unmatched attacks, FN manually annotated unmatched attacks. Precision TP/(TP+FP), recall TP/(TP+FN), F1 2PR/(P+R) when denominators permit. Event match cannot be reused. Report median and P95 absolute onset offset in milliseconds, with counts.
- **Tempo:** absolute `|estimatedBpm-referenceBpm|`, relative error divided by positive reference BPM; local section errors separately. Record half/double-tempo confusions explicitly. No non-linear alignment that removes genuine tempo defects.
- **Unexpected pauses:** one-to-one interval pairing and onset/end offset; count false positives for notated rests and normal sustain separately.
- **Bass:** event-level omission/substitution/unknown matrix, separate attack detection from pitch judgment; low frequency alone is not proof of a correct note.
- **Abstention:** assessed eligible units / all eligible units, categorical false statements / categorical statements, with enumerated reasons `INPUT_INVALID`, `RIGHTS_UNCONFIRMED`, `REFERENCE_MISSING`, `ANNOTATION_AMBIGUOUS`, `QUALITY_UNSUITABLE`, `OUTSIDE_VALIDATED_DOMAIN`, `METHOD_UNVALIDATED`.
- **Resources:** input duration, wall clock, CPU/memory when available, hardware and software versions, external calls and monetary cost (normally zero).

If a denominator is zero, emit `NOT_APPLICABLE` (never 100%, NaN or 0/0). If there is no independent holdout, emit `NO_INDEPENDENT_HOLDOUT` and refuse any generalizable GO claim. Confidence scores are not calibrated probabilities unless calibrated separately. Record uncertainty intervals and report excluded/ambiguous examples in a separate denominator. The benchmark output does not automatically certify student skills.
