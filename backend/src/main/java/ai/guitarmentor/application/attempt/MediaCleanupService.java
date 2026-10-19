package ai.guitarmentor.application.attempt;

import java.io.IOException;
import ai.guitarmentor.application.evidence.EvidenceMediaCleanup;
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
    private final EvidenceMediaCleanup evidenceCleanup;
    private final Clock clock=Clock.systemUTC();
    private Instant lastRun;
    public MediaCleanupService(MediaCleanupTransactions transactions,MediaRetentionPolicy settings,
            AttemptMediaStore store,MediaUseCoordinator coordinator,EvidenceMediaCleanup evidenceCleanup) {
        this.transactions=transactions;this.settings=settings;this.store=store;this.coordinator=coordinator;
        this.evidenceCleanup=evidenceCleanup;
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
            runCycle(now,policy);
            reconcileOrphans(now,policy);
            evidenceCleanup.reconcileDocumentOrphans(now,policy);
            lastRun=now;
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
            String cursor=null;
            // Continue past referenced files; bounded pages avoid starving distant orphans.
            while (deleted<100) {
                var page=store.scanOlderThan(cutoff,256,cursor);
                if(page.isEmpty()) break;
                for (var orphan:page) {
                    if (transactions.isReferenced(orphan.key())) continue;
                    if (store.isUnreferencedCandidateOld(orphan.key(),cutoff)
                            && !transactions.isReferenced(orphan.key())) {
                        store.delete(orphan.key());
                        deleted++;
                        if(deleted>=100) break;
                    }
                }
                cursor=page.get(page.size()-1).key();
                if(page.size()<256) break;
            }
        } catch(IOException | RuntimeException failure) {
            log.error("event=MEDIA_ORPHAN_DELETE_FAILED code=ORPHAN_SCAN_OR_DATABASE_ERROR");
        }
    }
    public void runCycle(Instant now,MediaRetentionPolicy.Policy policy) {
        // Public entry point is also called by recovery and integration tests;
        // always coordinate filesystem deletion, even outside the scheduler.
        coordinator.acquire();
        try {
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
            evidenceCleanup.runCycle(now,policy);
        } finally { coordinator.release(); }
    }
}
