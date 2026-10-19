package ai.guitarmentor.application.attempt;

import ai.guitarmentor.domain.attempt.AttemptStatus;
import ai.guitarmentor.domain.attempt.MediaState;
import ai.guitarmentor.infrastructure.persistence.SubmissionAttemptRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transaction boundaries are intentionally outside filesystem operations. */
@Service
public class MediaCleanupTransactions {
    private final SubmissionAttemptRepository attempts;
    public MediaCleanupTransactions(SubmissionAttemptRepository attempts) { this.attempts=attempts; }
    public record Candidate(UUID id,String key) {}

    @Transactional
    public List<Candidate> claim(Instant now,MediaRetentionPolicy.Policy policy) {
        var pending=attempts.findTop100ByMediaStateOrderBySubmittedAtAsc(MediaState.DELETE_PENDING)
            .stream().map(a -> new Candidate(a.getId(),a.getMediaKey())).toList();
        if (!pending.isEmpty()) return pending;
        var selected=attempts.findEligibleForCleanup(
            now.minus(policy.afterFinal()),now.minus(policy.maxUnprocessed()));
        for(var a:selected) {
            boolean finalized=a.getProcessingFinalizedAt()!=null;
            if(!finalized) a.expireUnprocessed(now);
            a.markDeletePending(finalized ? "PROCESSED_TTL" : "UNPROCESSED_TTL");
        }
        attempts.flush();
        return selected.stream().map(a->new Candidate(a.getId(),a.getMediaKey())).toList();
    }
    @Transactional
    public void deleted(UUID id,Instant now,boolean absent) {
        attempts.findById(id).ifPresent(a->{
            if(a.getMediaState()==MediaState.DELETE_PENDING) a.markDeleted(now,absent);
        });
    }
    @Transactional
    public void failed(UUID id) {
        attempts.findById(id).ifPresent(a->{
            if(a.getMediaState()==MediaState.DELETE_PENDING) a.markDeleteFailure("DELETE_IO_ERROR");
        });
    }
}
