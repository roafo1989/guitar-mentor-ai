package ai.guitarmentor.infrastructure.persistence;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL-backed global worker slots and expiring leases.
 * No JVM semaphore is used: the two slot rows are shared by every app instance.
 */
@Component
public final class LibraryConversionJobLeaseService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final boolean enabled;
    private final int leaseSeconds;

    public LibraryConversionJobLeaseService(JdbcClient jdbc, TransactionTemplate transactions,
            @Value("${guitar-mentor.library.enabled:false}") boolean libraryEnabled,
            @Value("${guitar-mentor.library.conversion-enabled:false}") boolean conversionEnabled,
            @Value("${guitar-mentor.library.conversion-lease-seconds:90}") int leaseSeconds) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.enabled = libraryEnabled && conversionEnabled;
        if (leaseSeconds < 70 || leaseSeconds > 180)
            throw new IllegalStateException("Conversion lease must exceed the 60s renderer watchdog");
        this.leaseSeconds = leaseSeconds;
    }

    public record Lease(int slotNumber, UUID jobId, UUID ownerAccountId, UUID materialId,
            UUID sourceAssetId, String sourceSha256, String sourceFormat,
            long sourceByteSize, int attemptCount, UUID leaseOwner) {}

    /** Atomically claims one of the two global slots and the oldest queued job. */
    public Optional<Lease> claimNext(UUID workerId) {
        if (!enabled || workerId == null) return Optional.empty();
        Lease lease = transactions.execute(status -> {
            reclaimExpiredLocked(25);
            List<Integer> slots = jdbc.sql("""
                SELECT slot_number FROM library_conversion_worker_slots
                WHERE job_id IS NULL ORDER BY slot_number
                LIMIT 1 FOR UPDATE SKIP LOCKED
                """).query(Integer.class).list();
            if (slots.isEmpty()) return null;
            int slot = slots.getFirst();
            List<Map<String,Object>> jobs = jdbc.sql("""
                SELECT id,owner_account_id,material_id,source_asset_id,source_sha256,source_format,
                       source_byte_size,attempt_count
                FROM library_conversion_jobs
                WHERE state='QUEUED'
                ORDER BY created_at,id
                FOR UPDATE SKIP LOCKED LIMIT 1
                """).query().listOfRows();
            if (jobs.isEmpty()) return null;
            Map<String,Object> job = jobs.getFirst();
            UUID jobId = (UUID) job.get("id");
            int attempts = ((Number) job.get("attempt_count")).intValue() + 1;
            jdbc.sql("""
                UPDATE library_conversion_worker_slots
                SET job_id=:job,lease_owner=:worker,
                    lease_until=now()+(:seconds * interval '1 second'),heartbeat_at=now()
                WHERE slot_number=:slot AND job_id IS NULL
                """).param("job", jobId).param("worker", workerId)
                .param("seconds", leaseSeconds).param("slot", slot).update();
            jdbc.sql("""
                UPDATE library_conversion_jobs
                SET state='RUNNING',stage='VALIDATING_SOURCE',progress=0,
                    attempt_count=:attempts,started_at=COALESCE(started_at,now()),
                    updated_at=now(),revision=revision+1
                WHERE id=:job AND state='QUEUED'
                """).param("attempts", attempts).param("job", jobId).update();
            return new Lease(slot, jobId, (UUID) job.get("owner_account_id"),
                (UUID) job.get("material_id"), (UUID) job.get("source_asset_id"), ObjectsText.value(job.get("source_sha256")),
                ObjectsText.value(job.get("source_format")),
                ((Number) job.get("source_byte_size")).longValue(), attempts, workerId);
        });
        return Optional.ofNullable(lease);
    }

    /** Returns false when the worker lost its lease or the job is no longer active. */
    public boolean heartbeat(UUID jobId, UUID workerId) {
        if (!enabled) return false;
        return Boolean.TRUE.equals(transactions.execute(status -> {
            int updated = jdbc.sql("""
                UPDATE library_conversion_worker_slots
                SET lease_until=now()+(:seconds * interval '1 second'),heartbeat_at=now()
                WHERE job_id=:job AND lease_owner=:worker AND lease_until>now()
                """).param("seconds", leaseSeconds).param("job", jobId)
                .param("worker", workerId).update();
            if (updated != 1) return false;
            int jobUpdated = jdbc.sql("""
                UPDATE library_conversion_jobs SET updated_at=now()
                WHERE id=:job AND state IN ('RUNNING','CANCEL_REQUESTED')
                """).param("job", jobId).update();
            return jobUpdated == 1;
        }));
    }

    /** True only while the current lease still owns a job that the user has cancelled. */
    public boolean isCancelRequested(UUID jobId, UUID workerId) {
        if (!enabled) return true;
        var rows = jdbc.sql("""
            SELECT j.cancel_requested,j.state
            FROM library_conversion_jobs j
            JOIN library_conversion_worker_slots s ON s.job_id=j.id
            WHERE j.id=:job AND s.lease_owner=:worker AND s.lease_until>now()
            """).param("job", jobId).param("worker", workerId).query().listOfRows();
        if (rows.isEmpty()) return true; // Lost lease: caller must terminate the child process.
        Map<String,Object> row = rows.getFirst();
        return Boolean.TRUE.equals(row.get("cancel_requested"))
            || "CANCEL_REQUESTED".equals(row.get("state"));
    }

    /** Worker publishes discovered tracks and relinquishes its global slot while awaiting user input. */
    public boolean awaitTrackSelection(UUID jobId, UUID workerId, String tracksJson) {
        if (!enabled || tracksJson == null || tracksJson.length() > 32768) return false;
        return Boolean.TRUE.equals(transactions.execute(status -> {
            var slots = jdbc.sql("""
                SELECT slot_number FROM library_conversion_worker_slots
                WHERE job_id=:job AND lease_owner=:worker AND lease_until>now()
                FOR UPDATE
                """).param("job", jobId).param("worker", workerId).query(Integer.class).list();
            if (slots.isEmpty()) return false;
            int updated = jdbc.sql("""
                UPDATE library_conversion_jobs
                SET state='AWAITING_TRACK_SELECTION',stage=NULL,progress=0,
                    tracks=CAST(:tracks AS jsonb),selected_track_index=NULL,
                    updated_at=now(),revision=revision+1
                WHERE id=:job AND state='RUNNING'
                """).param("tracks", tracksJson).param("job", jobId).update();
            if (updated != 1) return false;
            clearSlot(slots.getFirst(), jobId, workerId);
            return true;
        }));
    }

    /** Update stage/progress only while the exact worker still owns a live lease. */
    public boolean updateProgress(UUID jobId, UUID workerId, String stage, int progress) {
        if (!enabled || progress < 0 || progress > 99
                || !List.of("VALIDATING_SOURCE","IMPORTING_SCORE","RENDERING_PDF",
                    "SYNTHESIZING_AUDIO","VERIFYING_OUTPUTS").contains(stage)) return false;
        return Boolean.TRUE.equals(transactions.execute(status -> {
            var slots = jdbc.sql("""
                SELECT slot_number FROM library_conversion_worker_slots
                WHERE job_id=:job AND lease_owner=:worker AND lease_until>now()
                FOR UPDATE
                """).param("job", jobId).param("worker", workerId).query(Integer.class).list();
            if (slots.isEmpty()) return false;
            int updated = jdbc.sql("""
                UPDATE library_conversion_jobs
                SET stage=:stage,progress=:progress,updated_at=now(),revision=revision+1
                WHERE id=:job AND state='RUNNING'
                """).param("stage", stage).param("progress", progress).param("job", jobId).update();
            return updated == 1;
        }));
    }

    /** Worker terminal failure path. A concurrent user cancellation wins over a failure code. */
    public boolean finish(UUID jobId, UUID workerId, String terminalState,
            String errorCode, boolean retryable) {
        if (!enabled || !List.of("FAILED","UNSUPPORTED","TIMED_OUT","CANCELLED").contains(terminalState))
            return false;
        return Boolean.TRUE.equals(transactions.execute(status -> {
            var slots = jdbc.sql("""
                SELECT slot_number FROM library_conversion_worker_slots
                WHERE job_id=:job AND lease_owner=:worker AND lease_until>now() FOR UPDATE
                """).param("job", jobId).param("worker", workerId).query(Integer.class).list();
            if (slots.isEmpty()) return false;
            var jobs = jdbc.sql("""
                SELECT state,cancel_requested,error_code FROM library_conversion_jobs WHERE id=:job FOR UPDATE
                """).param("job", jobId).query().listOfRows();
            if (jobs.isEmpty()) return false;
            Map<String,Object> job = jobs.getFirst();
            String finalState = Boolean.TRUE.equals(job.get("cancel_requested"))
                || "CANCEL_REQUESTED".equals(job.get("state")) ? "CANCELLED" : terminalState;
            String priorError = ObjectsText.value(job.get("error_code"));
            String finalError = "CANCELLED".equals(finalState)
                ? (priorError == null ? "CANCELLED_BY_USER" : priorError) : errorCode;
            boolean finalRetryable = !"CANCELLED".equals(finalState) && retryable;
            jdbc.sql("""
                UPDATE library_conversion_jobs SET state=:state,error_code=:error,
                    retryable=:retryable,finished_at=now(),updated_at=now(),revision=revision+1
                WHERE id=:job AND state IN ('RUNNING','CANCEL_REQUESTED')
                """).param("state", finalState).param("error", finalError)
                .param("retryable", finalRetryable).param("job", jobId).update();
            clearSlot(slots.getFirst(), jobId, workerId);
            return true;
        }));
    }

    public int reclaimExpired(int maximum) {
        if (!enabled) return 0;
        if (maximum < 1 || maximum > 100) throw new IllegalArgumentException("Invalid reclaim batch");
        Integer count = transactions.execute(status -> reclaimExpiredLocked(maximum));
        return count == null ? 0 : count;
    }

    @Scheduled(fixedDelayString="${guitar-mentor.library.conversion-reclaim-delay-ms:10000}")
    public void runScheduledReclaim() {
        if (!enabled) return;
        try { reclaimExpired(25); }
        catch (RuntimeException ignored) {
            // The next sweep retries; never log raw database details or media paths.
        }
    }

    private int reclaimExpiredLocked(int maximum) {
        List<Map<String,Object>> expired = jdbc.sql("""
            SELECT slot_number,job_id FROM library_conversion_worker_slots
            WHERE job_id IS NOT NULL AND lease_until<=now()
            ORDER BY lease_until,slot_number
            LIMIT :limit FOR UPDATE SKIP LOCKED
            """).param("limit", maximum).query().listOfRows();
        int reclaimed = 0;
        for (Map<String,Object> slot : expired) {
            int slotNumber = ((Number) slot.get("slot_number")).intValue();
            UUID jobId = (UUID) slot.get("job_id");
            var jobs = jdbc.sql("""
                SELECT state,cancel_requested,attempt_count,error_code FROM library_conversion_jobs
                WHERE id=:job FOR UPDATE
                """).param("job", jobId).query().listOfRows();
            if (!jobs.isEmpty()) {
                Map<String,Object> job = jobs.getFirst();
                String state = ObjectsText.value(job.get("state"));
                if ("RUNNING".equals(state) || "CANCEL_REQUESTED".equals(state)) {
                    boolean cancelled = Boolean.TRUE.equals(job.get("cancel_requested"))
                        || "CANCEL_REQUESTED".equals(state);
                    int attempts = ((Number) job.get("attempt_count")).intValue();
                    String nextState = cancelled ? "CANCELLED" : attempts < 2 ? "QUEUED" : "FAILED";
                    String priorError = ObjectsText.value(job.get("error_code"));
                    String error = cancelled ? (priorError == null ? "CANCELLED_BY_USER" : priorError)
                        : attempts < 2 ? null : "WORKER_LEASE_EXPIRED";
                    boolean terminal = "CANCELLED".equals(nextState) || "FAILED".equals(nextState);
                    jdbc.sql("""
                        UPDATE library_conversion_jobs
                        SET state=:state,stage=NULL,progress=0,error_code=:error,
                            retryable=FALSE,finished_at=CASE WHEN :terminal THEN now() ELSE NULL END,
                            updated_at=now(),revision=revision+1
                        WHERE id=:job
                        """).param("state", nextState).param("error", error)
                        .param("terminal", terminal).param("job", jobId).update();
                }
            }
            jdbc.sql("""
                UPDATE library_conversion_worker_slots
                SET job_id=NULL,lease_owner=NULL,lease_until=NULL,heartbeat_at=NULL
                WHERE slot_number=:slot AND job_id=:job
                """).param("slot", slotNumber).param("job", jobId).update();
            reclaimed++;
        }
        return reclaimed;
    }

    private void clearSlot(int slot, UUID jobId, UUID workerId) {
        jdbc.sql("""
            UPDATE library_conversion_worker_slots
            SET job_id=NULL,lease_owner=NULL,lease_until=NULL,heartbeat_at=NULL
            WHERE slot_number=:slot AND job_id=:job AND lease_owner=:worker
            """).param("slot", slot).param("job", jobId).param("worker", workerId).update();
    }

    private static final class ObjectsText {
        static String value(Object value) { return value == null ? null : value.toString(); }
    }
}
