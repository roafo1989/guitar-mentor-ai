package ai.guitarmentor.application.attempt;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@org.springframework.scheduling.annotation.EnableScheduling
public class MediaCleanupService {
    private static final Logger log=LoggerFactory.getLogger(MediaCleanupService.class);
    private final MediaCleanupTransactions transactions;
    private final MediaRetentionPolicy settings;
    private final AttemptMediaStore store;
    private final MediaUseCoordinator coordinator;
    private final Clock clock=Clock.systemUTC();
    private Instant lastRun;
    public MediaCleanupService(MediaCleanupTransactions transactions,MediaRetentionPolicy settings,
            AttemptMediaStore store,MediaUseCoordinator coordinator) {
        this.transactions=transactions;this.settings=settings;this.store=store;this.coordinator=coordinator;
    }
    /** Independent of upload flag; policy checked every 60s, even after SQL edits. */
    @Scheduled(fixedDelay=60000,initialDelay=1000)
    public void tick() {
        MediaRetentionPolicy.Policy policy;
        try { policy=settings.read(); }
        catch(RuntimeException invalid) {
            log.error("event=MEDIA_CLEANUP_CONFIG_INVALID");
            return;
        }
        Instant now=clock.instant();
        if(lastRun!=null && now.isBefore(lastRun.plus(policy.interval()))) return;
        if(!coordinator.tryAcquire()) {
            log.info("event=MEDIA_CLEANUP_SKIPPED_BUSY");
            return;
        }
        try {
            lastRun=now;
            runCycle(now,policy);
            reconcileOrphans(now,policy);
        } catch(RuntimeException problem) {
            log.error("event=MEDIA_CLEANUP_FAILED code=DATABASE_OR_RUNTIME_ERROR");
        } finally {
            coordinator.release();
        }
    }
    private void reconcileOrphans(Instant now, MediaRetentionPolicy.Policy policy) {
        try {
            Instant cutoff=now.minus(policy.maxUnprocessed());
            int deleted=0;
            for (var orphan:store.scanOlderThan(cutoff,10000)) {
                if (deleted>=100) break;
                if (transactions.isReferenced(orphan.key())) continue;
                // Re-evaluate mtime, storage key and DB reference under the same local guard
                // held by upload, worker and cleanup.
                boolean stillOld=store.isUnreferencedCandidateOld(orphan.key(),cutoff);
                if (stillOld && !transactions.isReferenced(orphan.key())) {
                    store.delete(orphan.key());
                    deleted++;
                }
            }
        } catch(IOException | RuntimeException failure) {
            log.error("event=MEDIA_ORPHAN_DELETE_FAILED code=ORPHAN_SCAN_OR_DATABASE_ERROR");
        }
    }
    public void runCycle(Instant now,MediaRetentionPolicy.Policy policy) {
        for(var item:transactions.claim(now,policy)) {
            try {
                var result=store.delete(item.key());
                transactions.deleted(item.id(),clock.instant(),
                    result==AttemptMediaStore.DeleteOutcome.ALREADY_ABSENT);
            } catch(IOException | RuntimeException failure) {
                log.error("event=MEDIA_DELETE_FAILED attemptId={} code=DELETE_IO_ERROR",item.id());
                try { transactions.failed(item.id()); }
                catch(RuntimeException databaseProblem) {
                    log.error("event=MEDIA_DELETE_DB_FAILED attemptId={} code=DATABASE_ERROR",item.id());
                }
            }
        }
    }
}
