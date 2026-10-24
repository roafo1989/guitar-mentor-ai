-- #182: additive, fail-closed release of the pedagogically accepted 3/8 probe.
-- V24 stays immutable. Historical GM-EB01 rows/assignments are untouched.
-- Rights basis: project-produced procedural exercise and project-generated notation
-- and synthesized audio; see docs/releases/issue-182-rights-and-activation.md.
DO $$
DECLARE affected integer;
BEGIN
  IF (SELECT count(*) FROM exercises
      WHERE code='GM-DIAG-PULSE-3-8-01') <> 1 THEN
    RAISE EXCEPTION 'Probe release expects exactly one version';
  END IF;
  IF EXISTS (SELECT 1 FROM exercises
      WHERE code='GM-DIAG-PULSE-3-8-01'
      AND (version <> '0.1.0' OR is_current)) THEN
    RAISE EXCEPTION 'Unexpected current probe version; review before release';
  END IF;
  UPDATE exercises
  SET license='ORIGINAL_OWNED',
      review_status='APPROVED',
      is_current=TRUE
  WHERE code='GM-DIAG-PULSE-3-8-01' AND version='0.1.0'
    AND content_kind='DIAGNOSTIC_PROBE'
    AND license='RIGHTS_PENDING'
    AND review_status='PENDING_PEDAGOGICAL_REVIEW'
    AND is_current=FALSE
    AND meter='3/8' AND bpm=100 AND tempo_unit='eighth'
    AND duration_target_seconds=22
    AND preview_audio_resource='exercises/GM-DIAG-PULSE-3-8-01/preview_v010.mp3'
    AND preview_pdf_resource='exercises/GM-DIAG-PULSE-3-8-01/preview_v010.pdf';
  GET DIAGNOSTICS affected = ROW_COUNT;
  IF affected <> 1 THEN
    RAISE EXCEPTION 'Probe release preconditions not satisfied';
  END IF;
END $$;
