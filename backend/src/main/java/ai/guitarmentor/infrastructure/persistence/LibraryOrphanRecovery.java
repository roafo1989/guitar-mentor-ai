package ai.guitarmentor.infrastructure.persistence;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Conservative crash recovery for upload orphans. Never delete a referenced asset:
 * only random-key objects from our isolated root, older than the staging grace.
 * A transient DB error aborts the sweep without deleting unknown ownership.
 */
@Configuration
@EnableScheduling
public class LibraryOrphanRecovery {
    private final LibraryObjectStore store;
    private final JdbcClient jdbc;
    private final boolean enabled;
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    public LibraryOrphanRecovery(LibraryObjectStore store, JdbcClient jdbc,
            org.springframework.transaction.support.TransactionTemplate transactions,
            @Value("${guitar-mentor.library.enabled:false}") boolean enabled) {
        this.store=store;this.jdbc=jdbc;this.enabled=enabled;this.transactions=transactions;
    }
    @Scheduled(fixedDelayString="${guitar-mentor.library.reconcile-delay-ms:600000}")
    public void reconcile() {
        if(!enabled) return;
        try {
            // Release abandoned quota reservations before scavenging physical objects.
            for(var stale:jdbc.sql("""
                SELECT id,owner_account_id FROM library_upload_operations
                WHERE state='RECEIVING' AND COALESCE(last_lease_at,updated_at) < now()-interval '60 minutes'
                ORDER BY updated_at LIMIT 100
                """).query().listOfRows()) {
                java.util.UUID operation=(java.util.UUID)stale.get("id");
                java.util.UUID owner=(java.util.UUID)stale.get("owner_account_id");
                transactions.executeWithoutResult(status -> {
                    jdbc.sql("SELECT account_id FROM library_account_usage WHERE account_id=:owner FOR UPDATE")
                        .param("owner",owner).query().singleRow();
                    var rows=jdbc.sql("""
                        SELECT state,reserved_bytes,reserved_files FROM library_upload_operations
                        WHERE id=:id AND COALESCE(last_lease_at,updated_at)
                          < now()-interval '60 minutes' FOR UPDATE
                        """).param("id",operation).query().listOfRows();
                    if(rows.isEmpty()) return;
                    var row=rows.getFirst();
                    if(!"RECEIVING".equals(row.get("state"))) return;
                    jdbc.sql("""
                        UPDATE library_account_usage
                        SET reserved_bytes=reserved_bytes-:bytes,reserved_files=reserved_files-:files,
                            revision=revision+1 WHERE account_id=:owner
                        """).param("bytes",row.get("reserved_bytes"))
                        .param("files",row.get("reserved_files")).param("owner",owner).update();
                    jdbc.sql("""
                        UPDATE library_upload_operations SET state='FAILED',reserved_bytes=0,
                          reserved_files=0,terminal_code='LIBRARY_UPLOAD_INTERRUPTED',updated_at=now()
                        WHERE id=:id
                        """).param("id",operation).update();
                });
            }
            for(Path candidate:store.expiredCandidates(Duration.ofMinutes(60),100)) {
                String name=candidate.getFileName().toString();
                if(name.startsWith(".staging-")) {
                    // Never promote staging; after grace any still-unfinished staging is orphaned.
                    store.deleteCandidate(candidate);
                    continue;
                }
                Boolean referenced=jdbc.sql("""
                    SELECT
                      EXISTS (SELECT 1 FROM library_assets WHERE object_key=:key)
                      OR EXISTS (
                        SELECT 1 FROM library_upload_operations
                        WHERE state='RECEIVING'
                          AND COALESCE(last_lease_at,updated_at)>now()-interval '60 minutes'
                          AND COALESCE(manifest->'keys','[]'::jsonb)
                              @> jsonb_build_array(CAST(:key AS text))
                      )
                    """).param("key",name).query(Boolean.class).single();
                if(Boolean.FALSE.equals(referenced)) store.deleteCandidate(candidate);
            }
        } catch(Exception e) {
            // Fail closed on database or storage unavailability; retry next scheduled cycle.
            org.slf4j.LoggerFactory.getLogger(getClass()).warn(
                "Library orphan sweep deferred (storage or DB unavailable): {}",e.getClass().getSimpleName());
        }
    }
}
