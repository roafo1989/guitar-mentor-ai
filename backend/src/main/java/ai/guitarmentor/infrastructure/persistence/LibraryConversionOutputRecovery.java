package ai.guitarmentor.infrastructure.persistence;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Releases output quota reservations left by a crashed or lease-expired renderer. */
@Component
public final class LibraryConversionOutputRecovery {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final boolean enabled;

    public LibraryConversionOutputRecovery(JdbcClient jdbc,TransactionTemplate transactions,
            @Value("${guitar-mentor.library.enabled:false}") boolean libraryEnabled) {
        this.jdbc=jdbc; this.transactions=transactions; this.enabled=libraryEnabled;
    }

    @Scheduled(fixedDelayString="${guitar-mentor.library.conversion-output-recovery-delay-ms:30000}")
    public void runScheduled() {
        if(!enabled) return;
        try { releaseAbandonedReservations(25); }
        catch(RuntimeException error) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn(
                "Conversion output reservation recovery deferred: {}",error.getClass().getSimpleName());
        }
    }

    public int releaseAbandonedReservations(int maximum) {
        if(maximum<1 || maximum>100) throw new IllegalArgumentException("Invalid recovery batch");
        List<Map<String,Object>> candidates=jdbc.sql("""
            SELECT id,owner_account_id,material_id
            FROM library_conversion_jobs
            WHERE output_reservation_state='RESERVED'
            ORDER BY updated_at,id LIMIT :limit
            """).param("limit",maximum).query().listOfRows();
        int released=0;
        for(Map<String,Object> candidate:candidates) {
            UUID jobId=(UUID)candidate.get("id");
            UUID owner=(UUID)candidate.get("owner_account_id");
            UUID material=(UUID)candidate.get("material_id");
            Boolean didRelease=transactions.execute(status -> {
                jdbc.sql("SELECT account_id FROM library_account_usage WHERE account_id=:owner FOR UPDATE")
                    .param("owner",owner).query().singleRow();
                jdbc.sql("SELECT id FROM library_materials WHERE id=:material FOR UPDATE")
                    .param("material",material).query().list();
                Long liveSlots=jdbc.sql("""
                    SELECT count(*) FROM library_conversion_worker_slots
                    WHERE job_id=:job AND lease_until>now() FOR UPDATE
                    """).param("job",jobId).query(Long.class).single();
                if(liveSlots>0) return false;
                var jobs=jdbc.sql("""
                    SELECT output_reserved_bytes,output_reserved_files,output_reservation_state
                    FROM library_conversion_jobs WHERE id=:job AND owner_account_id=:owner FOR UPDATE
                    """).param("job",jobId).param("owner",owner).query().listOfRows();
                if(jobs.isEmpty() || !"RESERVED".equals(jobs.getFirst().get("output_reservation_state")))
                    return false;
                long bytes=((Number)jobs.getFirst().get("output_reserved_bytes")).longValue();
                int files=((Number)jobs.getFirst().get("output_reserved_files")).intValue();
                int changed=jdbc.sql("""
                    UPDATE library_account_usage SET reserved_bytes=reserved_bytes-:bytes,
                        reserved_files=reserved_files-:files,revision=revision+1
                    WHERE account_id=:owner AND reserved_bytes>=:bytes AND reserved_files>=:files
                    """).param("bytes",bytes).param("files",files).param("owner",owner).update();
                if(changed!=1) throw new IllegalStateException("Conversion reservation accounting invariant violated");
                jdbc.sql("""
                    UPDATE library_conversion_jobs SET output_reserved_bytes=0,output_reserved_files=0,
                        output_reservation_state='RELEASED',updated_at=now(),revision=revision+1
                    WHERE id=:job AND output_reservation_state='RESERVED'
                    """).param("job",jobId).update();
                return true;
            });
            if(Boolean.TRUE.equals(didRelease)) released++;
        }
        return released;
    }
}
