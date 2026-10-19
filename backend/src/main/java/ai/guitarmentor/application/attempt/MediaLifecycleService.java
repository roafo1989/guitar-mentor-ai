package ai.guitarmentor.application.attempt;

import ai.guitarmentor.infrastructure.persistence.SubmissionAttemptRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Called only when every stage of the expected plan is terminal. */
@Service
public class MediaLifecycleService {
    private final SubmissionAttemptRepository attempts;
    private final Clock clock;
    public MediaLifecycleService(SubmissionAttemptRepository attempts) {
        this(attempts,Clock.systemUTC());
    }
    MediaLifecycleService(SubmissionAttemptRepository attempts,Clock clock) {
        this.attempts=attempts;
        this.clock=clock;
    }
    @Transactional
    public void finalizeProcessing(UUID attemptId,String expectedPlan) {
        var attempt=attempts.findById(attemptId)
            .orElseThrow(()->new IllegalArgumentException("Attempt not found"));
        attempt.finalizeProcessing(clock.instant(),expectedPlan);
    }
}
