package ai.guitarmentor.application.attempt;

import ai.guitarmentor.domain.attempt.AttemptStatus;
import ai.guitarmentor.domain.attempt.MediaState;
import ai.guitarmentor.infrastructure.persistence.SubmissionAttemptRepository;
import ai.guitarmentor.infrastructure.persistence.DiagnosticEvidenceRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transaction boundaries are intentionally outside filesystem operations. */
@Service
public class MediaCleanupTransactions {
    private final SubmissionAttemptRepository attempts;
    private final DiagnosticEvidenceRepository evidence;
    @org.springframework.beans.factory.annotation.Autowired
    public MediaCleanupTransactions(SubmissionAttemptRepository attempts, DiagnosticEvidenceRepository evidence) {
        this.attempts=attempts;this.evidence=evidence;
    }
    public MediaCleanupTransactions(SubmissionAttemptRepository attempts) {
        this(attempts, null);
    }
    public record Candidate(UUID id,String key) {}

    @Transactional
    public List<Candidate> claim(Instant now,MediaRetentionPolicy.Policy policy) {
        var pending=attempts.findTop50ByMediaStateOrderByMediaDeleteAttemptsAscSubmittedAtAsc(MediaState.DELETE_PENDING)
            .stream().map(a -> new Candidate(a.getId(),a.getMediaKey())).toList();

        var selected=attempts.findEligibleForCleanup(
            now.minus(policy.afterFinal()),now.minus(policy.maxUnprocessed()));
        for(var a:selected) {
            boolean finalized=a.getProcessingFinalizedAt()!=null;
            if(!finalized) a.expireUnprocessed(now);
            a.markDeletePending(finalized ? "PROCESSED_TTL" : "UNPROCESSED_TTL");
        }
        attempts.flush();
        var all=new java.util.ArrayList<>(pending);
        all.addAll(selected.stream().map(a->new Candidate(a.getId(),a.getMediaKey())).toList());
        return all;
    }
    @Transactional
    public void deleted(UUID id,Instant now,boolean absent) {
        attempts.findById(id).ifPresent(a->{
            if(a.getMediaState()==MediaState.DELETE_PENDING) a.markDeleted(now,absent);
        });
    }
    @Transactional(readOnly=true)
    public boolean isReferenced(String key) { return attempts.existsByMediaKey(key) || (evidence != null && evidence.existsByKey(key)); }

    @Transactional
    public void failed(UUID id) {
        attempts.findById(id).ifPresent(a->{
            if(a.getMediaState()==MediaState.DELETE_PENDING) a.markDeleteFailure("DELETE_IO_ERROR");
        });
    }
}
