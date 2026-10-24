package ai.guitarmentor.application.exercise;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/** Distinguish approved user-facing repertoire from draft diagnostic probes. */
class ExercisePublicationPolicyTest {
    @Test void alreadyAcceptedRepertoireRemainsAvailable() {
        assertTrue(ExercisePublicationPolicy.eligible("LEARNING_REPERTOIRE",
            "CC BY 4.0", "ACCEPTED_FOR_MVP_PROTOTYPE", null, null, null));
    }
    @Test void draftProbeNeverAppearsWithMissingAssets() {
        assertFalse(ExercisePublicationPolicy.eligible("DIAGNOSTIC_PROBE",
            "RIGHTS_PENDING", "PENDING_PEDAGOGICAL_REVIEW", null, null, 22));
        assertFalse(ExercisePublicationPolicy.eligible("DIAGNOSTIC_PROBE",
            "ORIGINAL_OWNED", "APPROVED", null, "score.pdf", 22));
        assertFalse(ExercisePublicationPolicy.eligible("DIAGNOSTIC_PROBE",
            "ORIGINAL_OWNED", "APPROVED", "preview.mp3", null, 22));
    }
    @Test void shortApprovedProbeRequiresRealReviewAndMediaMetadata() {
        assertTrue(ExercisePublicationPolicy.eligible("DIAGNOSTIC_PROBE",
            "ORIGINAL_OWNED", "APPROVED", "preview.mp3", "score.pdf", 22));
        assertFalse(ExercisePublicationPolicy.eligible("DIAGNOSTIC_PROBE",
            "ORIGINAL_OWNED", "REJECTED", "preview.mp3", "score.pdf", 22));
        assertFalse(ExercisePublicationPolicy.eligible("DIAGNOSTIC_PROBE",
            "ORIGINAL_OWNED", "APPROVED", "preview.mp3", "score.pdf", 90));
        assertFalse(ExercisePublicationPolicy.eligible("UNRECOGNIZED",
            "CC BY 4.0", "APPROVED", "preview.mp3", "score.pdf", 22));
    }
}
