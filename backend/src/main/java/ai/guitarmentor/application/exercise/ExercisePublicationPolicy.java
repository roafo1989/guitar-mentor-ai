package ai.guitarmentor.application.exercise;

import ai.guitarmentor.domain.exercise.Exercise;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;

/** Guard new assignments/public listings. Historic version-pinned tasks stay readable. */
public final class ExercisePublicationPolicy {
    private static final Set<String> LICENSES = Set.of(
            "CC BY 4.0", "CC0 1.0", "PUBLIC_DOMAIN", "ORIGINAL_OWNED");
    private static final Set<String> REVIEWS = Set.of(
            "ACCEPTED_FOR_MVP_PROTOTYPE", "APPROVED", "PUBLISHED");

    private ExercisePublicationPolicy() {}

    public static boolean eligible(Exercise e) {
        boolean approved = eligible(e.getContentKind(), e.getLicense(), e.getReviewStatus(),
                e.getPreviewAudioResource(), e.getPreviewPdfResource(),
                e.getDurationTargetSeconds());
        if (!approved || !"DIAGNOSTIC_PROBE".equals(e.getContentKind())) return approved;
        // Path metadata alone is insufficient: never expose a probe with broken media.
        return exists(e.getScoreResource()) && exists(e.getEventsResource())
                && exists(e.getPreviewAudioResource()) && exists(e.getPreviewPdfResource());
    }

    private static boolean exists(String path) {
        return path != null && path.startsWith("exercises/")
                && !path.contains("..") && path.indexOf('\\') < 0
                && new ClassPathResource(path).exists();
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
