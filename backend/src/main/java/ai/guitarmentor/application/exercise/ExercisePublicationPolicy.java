package ai.guitarmentor.application.exercise;

import ai.guitarmentor.domain.exercise.Exercise;
import java.util.Set;

/** Guard new assignments/public listings. Historic version-pinned tasks stay readable. */
public final class ExercisePublicationPolicy {
    private static final Set<String> LICENSES = Set.of(
            "CC BY 4.0", "CC0 1.0", "PUBLIC_DOMAIN", "ORIGINAL_OWNED");
    private static final Set<String> REVIEWS = Set.of(
            "ACCEPTED_FOR_MVP_PROTOTYPE", "APPROVED", "PUBLISHED");

    private ExercisePublicationPolicy() {}

    public static boolean eligible(Exercise e) {
        return eligible(e.getContentKind(), e.getLicense(), e.getReviewStatus(),
                e.getPreviewAudioResource(), e.getPreviewPdfResource(),
                e.getDurationTargetSeconds());
    }

    public static boolean eligible(String kind, String license, String review,
            String audio, String pdf, Integer seconds) {
        if (kind == null || license == null || review == null) return false;
        if (!LICENSES.contains(license) || !REVIEWS.contains(review)) return false;
        if ("LEARNING_REPERTOIRE".equals(kind)) return true;
        if (!"DIAGNOSTIC_PROBE".equals(kind)) return false;
        // A short probe MUST have both directly playable media and notation preview
        // before release; draft prototypes do not leak into the learner catalog.
        return seconds != null && seconds >= 5 && seconds <= 60
                && audio != null && !audio.isBlank()
                && pdf != null && !pdf.isBlank();
    }
}
