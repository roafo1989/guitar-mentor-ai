package ai.guitarmentor.interfaces.rest.attempt;

import ai.guitarmentor.domain.attempt.SubmissionAttempt;
import java.time.Instant;
import java.util.UUID;

public final class AttemptDtos {
    private AttemptDtos() {}

    public record UploadCapabilities(boolean enabled, long maxBytes, String[] formats) {}

    public record AttemptView(UUID id, UUID taskId, int number, Instant submittedAt,
            String status, String comment, String format, long bytes,
            boolean assessmentAvailable, Instant processingStartedAt,
            Instant processingFinishedAt, String processingErrorCode,
            java.math.BigDecimal audioDurationSeconds, String mediaState, Instant mediaDeletedAt) {
        public static AttemptView from(SubmissionAttempt attempt) {
            return new AttemptView(attempt.getId(), attempt.getTask().getId(),
                    attempt.getAttemptNumber(), attempt.getSubmittedAt(),
                    attempt.getStatus().name(), attempt.getStudentComment(),
                    attempt.getMediaFormat(), attempt.getSizeBytes(), false,
                    attempt.getProcessingStartedAt(), attempt.getProcessingFinishedAt(),
                    attempt.getProcessingErrorCode(), attempt.getAudioDurationSeconds(),
                    attempt.getMediaState().name(), attempt.getMediaDeletedAt());
        }
    }
}
