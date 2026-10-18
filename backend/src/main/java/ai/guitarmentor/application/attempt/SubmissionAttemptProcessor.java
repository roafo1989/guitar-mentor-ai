package ai.guitarmentor.application.attempt;

import ai.guitarmentor.domain.attempt.AttemptStatus;
import ai.guitarmentor.infrastructure.persistence.SubmissionAttemptRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Database transitions are committed separately from the file-inspection work. */
@Service
public class SubmissionAttemptProcessor {
    private final SubmissionAttemptRepository attempts;

    public SubmissionAttemptProcessor(SubmissionAttemptRepository attempts) {
        this.attempts = attempts;
    }

    public record ProcessingJob(UUID id, String key, long size) {}

    @Transactional
    public Optional<ProcessingJob> claimNext() {
        var next = attempts.findFirstByStatusOrderBySubmittedAtAsc(AttemptStatus.UPLOADED);
        if (next.isEmpty()) return Optional.empty();
        var attempt = next.get();
        attempt.startProcessing();
        attempts.saveAndFlush(attempt);
        return Optional.of(new ProcessingJob(attempt.getId(), attempt.getMediaKey(),
                attempt.getSizeBytes()));
    }

    @Transactional
    public void success(UUID id, String digest) {
        attempts.findById(id).ifPresent(attempt -> {
            if (attempt.getStatus() == AttemptStatus.PROCESSING) {
                attempt.finishTechnicalValidation(digest);
            }
        });
    }

    @Transactional
    public void failure(UUID id, String code) {
        attempts.findById(id).ifPresent(attempt -> {
            if (attempt.getStatus() == AttemptStatus.PROCESSING) {
                attempt.failTechnicalValidation(code);
            }
        });
    }

    @Transactional
    public int recoverInterrupted() {
        var stale = attempts.findByStatusAndProcessingStartedAtBefore(
                AttemptStatus.PROCESSING, Instant.now().minus(5, ChronoUnit.MINUTES));
        stale.forEach(attempt -> attempt.requeueAfterInterruptedProcessing());
        return stale.size();
    }
}
