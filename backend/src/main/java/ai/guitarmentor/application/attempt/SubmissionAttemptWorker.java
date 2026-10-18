package ai.guitarmentor.application.attempt;

import ai.guitarmentor.infrastructure.media.LocalAttemptMediaStore;
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
    private final LocalAttemptMediaStore media;

    public SubmissionAttemptWorker(SubmissionAttemptProcessor processor,
            LocalAttemptMediaStore media) {
        this.processor = processor;
        this.media = media;
    }

    @Scheduled(fixedDelayString = "${guitar-mentor.submissions.poll-delay-ms:3000}")
    public void poll() {
        processor.recoverInterrupted();
        var job = processor.claimNext();
        if (job.isEmpty()) return;
        var item = job.get();
        try {
            var digest = media.verifyAndHash(item.key(), item.size());
            processor.success(item.id(), digest);
        } catch (IOException failure) {
            // Only stable error codes enter the database; never expose disk locations.
            String message = failure.getMessage();
            String code = "IO_ERROR";
            if ("MEDIA_MISSING".equals(message) || "SIZE_MISMATCH".equals(message)
                    || "INVALID_FORMAT".equals(message) || "INVALID_KEY".equals(message)) {
                code = message;
            }
            processor.failure(item.id(), code);
        } catch (RuntimeException unexpected) {
            processor.failure(item.id(), "TECHNICAL_ERROR");
        }
    }
}
