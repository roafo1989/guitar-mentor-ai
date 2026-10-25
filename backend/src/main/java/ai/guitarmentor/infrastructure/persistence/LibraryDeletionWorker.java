package ai.guitarmentor.infrastructure.persistence;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable private-material deletion.
 *
 * DB tombstone is committed by the DELETE endpoint before this worker touches bytes.
 * Every retry is safe after a crash: an absent object counts as physically deleted.
 * Material tombstone is retained for upload replay -> 410; source files never reappear.
 */
@Component
public class LibraryDeletionWorker {
    private static final Logger LOG=LoggerFactory.getLogger(LibraryDeletionWorker.class);
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final LibraryObjectStore store;
    private final boolean enabled;

    public LibraryDeletionWorker(JdbcClient jdbc,TransactionTemplate transactions,
            LibraryObjectStore store,
            @Value("${guitar-mentor.library.enabled:false}") boolean enabled) {
        this.jdbc=jdbc;this.transactions=transactions;this.store=store;this.enabled=enabled;
    }

    @Scheduled(fixedDelayString="${guitar-mentor.library.deletion-delay-ms:15000}")
    public void runScheduled() {
        if(!enabled) return;
        try {processBatch(25);}
        catch (RuntimeException ex) {
            LOG.warn("Library deletion sweep deferred: {}",ex.getClass().getSimpleName());
        }
    }

    /** Public for isolated fault-recovery tests; enabled gate applies to scheduler only. */
    public int processBatch(int maximum) {
        if(maximum<1 || maximum>100) throw new IllegalArgumentException("Invalid batch size");
        // Candidates are only hints; each item is locked and rechecked in one transaction.
        List<UUID> ids=jdbc.sql("""
            SELECT material_id FROM library_deletion_jobs
            WHERE state IN ('PENDING','RETRY','RUNNING') AND next_attempt_at<=now()
            ORDER BY next_attempt_at,material_id LIMIT :limit
            """).param("limit",maximum).query(UUID.class).list();
        int completed=0;
        for(UUID id:ids) {
            if(Boolean.TRUE.equals(transactions.execute(status -> processOne(id))))
                completed++;
        }
        return completed;
    }

    private boolean processOne(UUID id) {
        // Account usage is locked before material/job, matching the upload lock order.
        List<UUID> owners=jdbc.sql("""
            SELECT owner_account_id FROM library_materials
            WHERE id=:id AND state='DELETING'
            """).param("id",id).query(UUID.class).list();
        if(owners.isEmpty()) return false;
        UUID owner=owners.getFirst();
        jdbc.sql("INSERT INTO library_account_usage(account_id) VALUES(:owner) ON CONFLICT DO NOTHING")
            .param("owner",owner).update();
        jdbc.sql("SELECT account_id FROM library_account_usage WHERE account_id=:owner FOR UPDATE")
            .param("owner",owner).query(UUID.class).single();
        // Prevent concurrent card state transitions and deletion workers.
        var rows=jdbc.sql("""
            SELECT id FROM library_materials
            WHERE id=:id AND owner_account_id=:owner AND state='DELETING' FOR UPDATE
            """).param("id",id).param("owner",owner).query(UUID.class).list();
        if(rows.isEmpty()) return false;
        var jobs=jdbc.sql("""
            SELECT state FROM library_deletion_jobs
            WHERE material_id=:id AND next_attempt_at<=now() FOR UPDATE
            """).param("id",id).query(String.class).list();
        if(jobs.isEmpty()) return false;
        jdbc.sql("UPDATE library_deletion_jobs SET state='RUNNING' WHERE material_id=:id")
            .param("id",id).update();

        var assets=jdbc.sql("""
            SELECT object_key,byte_size FROM library_assets
            WHERE material_id=:id ORDER BY id FOR UPDATE
            """).param("id",id).query().listOfRows();
        long bytes=0;
        for(var asset:assets) bytes+=((Number)asset.get("byte_size")).longValue();

        // Always check for future references; never erase an object still used elsewhere.
        try {
            for(var asset:assets) {
                String key=(String)asset.get("object_key");
                Long references=jdbc.sql("""
                    SELECT count(*) FROM library_assets WHERE object_key=:key
                    """).param("key",key).query(Long.class).single();
                if(references!=1) throw new IOException("Ambiguous object reference");
                store.delete(key); // no-op if a previous attempt removed this exact key
            }
        } catch(IOException | RuntimeException failure) {
            // No DB asset or quota mutation; physical deletion may have partially occurred.
            // All such files are hidden by the DELETING tombstone, retry is idempotent.
            jdbc.sql("""
                UPDATE library_deletion_jobs SET state='RETRY',attempts=attempts+1,
                    last_error_code='LIBRARY_DELETE_RETRY',
                    next_attempt_at=now()+least(interval '1 hour',
                        (interval '10 seconds' * power(2,least(attempts,8))))
                WHERE material_id=:id
                """).param("id",id).update();
            LOG.warn("Library deletion retry scheduled for material {}",id);
            return false;
        }

        // Only after all physical objects are absent may quotas and DB references go.
        jdbc.sql("DELETE FROM library_assets WHERE material_id=:id").param("id",id).update();
        int updated=jdbc.sql("""
            UPDATE library_account_usage
            SET charged_bytes=charged_bytes-:bytes,
                charged_files=charged_files-:files,revision=revision+1
            WHERE account_id=:owner AND charged_bytes>=:bytes AND charged_files>=:files
            """).param("bytes",bytes).param("files",assets.size()).param("owner",owner).update();
        if(updated!=1) throw new IllegalStateException("Library quota invariant violated");
        long remaining=jdbc.sql("""
            SELECT count(*) FROM library_assets WHERE material_id=:id
            """).param("id",id).query(Long.class).single();
        if(remaining!=0) throw new IllegalStateException("Library assets remain");
        jdbc.sql("DELETE FROM library_deletion_jobs WHERE material_id=:id")
            .param("id",id).update();
        return true;
    }
}
