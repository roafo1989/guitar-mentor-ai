package ai.guitarmentor.application.evidence;

import ai.guitarmentor.application.attempt.AttemptMediaStore;
import ai.guitarmentor.application.attempt.MediaRetentionPolicy;
import ai.guitarmentor.infrastructure.media.DocumentMediaStore;
import ai.guitarmentor.infrastructure.persistence.DiagnosticEvidenceRepository;
import java.io.IOException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** An additional media type inside #21's existing single scheduled cleanup cycle. */
@Service
public class EvidenceMediaCleanup {
    private static final Logger log = LoggerFactory.getLogger(EvidenceMediaCleanup.class);
    private final DiagnosticEvidenceRepository evidence;
    private final AttemptMediaStore audio;
    private final DocumentMediaStore scores;

    public EvidenceMediaCleanup(DiagnosticEvidenceRepository evidence,
            AttemptMediaStore audio, DocumentMediaStore scores) {
        this.evidence=evidence;this.audio=audio;this.scores=scores;
    }

    public void runCycle(Instant now, MediaRetentionPolicy.Policy policy) {
        for (var item : evidence.claimExpired(now.minus(policy.afterFinal()),
                now.minus(policy.maxUnprocessed()))) {
            try {
                if (item.kind().equals("AUDIO")) audio.delete(item.key());
                else scores.delete(item.key());
                evidence.markDeleted(item.id());
            } catch (IOException | RuntimeException failure) {
                log.error("event=EVIDENCE_MEDIA_DELETE_FAILED id={} code=DELETE_IO_ERROR",item.id());
                try { evidence.markFailed(item.id()); }
                catch (RuntimeException dbFailure) {
                    log.error("event=EVIDENCE_MEDIA_DELETE_DB_FAILED id={} code=DATABASE_ERROR",
                            item.id());
                }
            }
        }
    }

    /** Recheck both the filesystem and DB under the common media coordinator. */
    public void reconcileDocumentOrphans(Instant now, MediaRetentionPolicy.Policy policy) {
        Instant cutoff=now.minus(policy.maxUnprocessed());
        try {
            int deleted=0;
            for (String key : scores.oldCandidates(cutoff)) {
                if (deleted >= 100) break;
                if (!evidence.existsByKey(key) && scores.stillOld(key,cutoff)
                        && !evidence.existsByKey(key)) {
                    scores.delete(key);
                    deleted++;
                }
            }
        } catch (IOException | RuntimeException failure) {
            log.error("event=EVIDENCE_SCORE_ORPHAN_FAILED code=ORPHAN_IO_ERROR");
        }
    }
}
