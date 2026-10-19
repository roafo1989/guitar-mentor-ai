package ai.guitarmentor.application.attempt;

import ai.guitarmentor.application.attempt.AttemptMediaStore;
import ai.guitarmentor.infrastructure.media.AudioDurationProbe;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Local MVP worker, enabled together with explicitly opted-in test uploads.
 * PROCESSED means file integrity verified, not that guitar playing was graded.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "guitar-mentor.submissions",
        name = "enabled", havingValue = "true")
public class SubmissionAttemptWorker {
    private final SubmissionAttemptProcessor processor;
    private final AttemptMediaStore media;
    private final AudioDurationProbe probe;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MediaUseCoordinator mediaCoordinator;

    public SubmissionAttemptWorker(SubmissionAttemptProcessor processor,
            AttemptMediaStore media, AudioDurationProbe probe) {
        this.processor = processor;
        this.media = media;
        this.probe = probe;
    }

    @Scheduled(fixedDelayString = "${guitar-mentor.submissions.poll-delay-ms:3000}")
    public void poll() {
        if (mediaCoordinator != null && !mediaCoordinator.tryAcquire()) return;
        try {
        processor.recoverInterrupted();
        var job = processor.claimNext();
        if (job.isEmpty()) return;
        var item = job.get();
        try {
            var digest = media.verifyAndHash(item.key(), item.size());
            double duration = probe.measure(media.resolveForInspection(item.key()));
            processor.success(item.id(), digest, duration);
        } catch (IOException failure) {
            // Only stable error codes enter the database; never expose disk locations.
            String message = failure.getMessage();
            String code = "IO_ERROR";
            if ("MEDIA_MISSING".equals(message) || "SIZE_MISMATCH".equals(message)
                    || "INVALID_FORMAT".equals(message) || "INVALID_KEY".equals(message)
                    || "INVALID_MEDIA".equals(message) || "DURATION_EXCEEDED".equals(message)
                    || "PROBE_UNAVAILABLE".equals(message) || "PROBE_TIMEOUT".equals(message)
                    || "PROBE_INTERRUPTED".equals(message)) {
                code = message;
            }
            processor.failure(item.id(), code);
        } catch (RuntimeException unexpected) {
            processor.failure(item.id(), "TECHNICAL_ERROR");
        }
        } finally { if (mediaCoordinator != null) mediaCoordinator.release(); }
    }
}
