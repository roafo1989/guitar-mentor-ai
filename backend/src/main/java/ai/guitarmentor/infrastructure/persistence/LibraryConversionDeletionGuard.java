package ai.guitarmentor.infrastructure.persistence;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Prevents physical library deletion from racing an active conversion worker.
 * Called inside the deletion worker's transaction after account/material locks.
 */
@Component
public final class LibraryConversionDeletionGuard {
    private final JdbcClient jdbc;

    public LibraryConversionDeletionGuard(JdbcClient jdbc) { this.jdbc = jdbc; }

    public boolean cancelAndWaitForMaterial(UUID materialId) {
        return cancelAndWait("material_id", materialId);
    }

    public boolean cancelAndWaitForAsset(UUID assetId) {
        return cancelAndWait("source_asset_id", assetId);
    }

    private boolean cancelAndWait(String column, UUID id) {
        // column is selected only by the two fixed methods above, never from user input.
        String sql = """
            SELECT id,state FROM library_conversion_jobs
            WHERE %s=:id AND state IN ('QUEUED','RUNNING','AWAITING_TRACK_SELECTION','CANCEL_REQUESTED')
            ORDER BY created_at,id FOR UPDATE
            """.formatted(column);
        List<Map<String,Object>> jobs = jdbc.sql(sql).param("id", id).query().listOfRows();
        for (Map<String,Object> job : jobs) {
            UUID jobId = (UUID) job.get("id");
            String state = ObjectsText.value(job.get("state"));
            if ("QUEUED".equals(state) || "AWAITING_TRACK_SELECTION".equals(state)) {
                jdbc.sql("""
                    UPDATE library_conversion_jobs
                    SET state='CANCELLED',cancel_requested=TRUE,error_code='SOURCE_DELETING',
                        finished_at=now(),updated_at=now(),revision=revision+1
                    WHERE id=:job AND state IN ('QUEUED','AWAITING_TRACK_SELECTION')
                    """).param("job", jobId).update();
            } else if ("RUNNING".equals(state)) {
                jdbc.sql("""
                    UPDATE library_conversion_jobs
                    SET state='CANCEL_REQUESTED',cancel_requested=TRUE,error_code='SOURCE_DELETING',
                        updated_at=now(),revision=revision+1
                    WHERE id=:job AND state='RUNNING'
                    """).param("job", jobId).update();
            }
        }
        Long remaining = jdbc.sql("""
            SELECT count(*) FROM library_conversion_jobs
            WHERE %s=:id AND state IN ('QUEUED','RUNNING','AWAITING_TRACK_SELECTION','CANCEL_REQUESTED')
            """.formatted(column)).param("id", id).query(Long.class).single();
        return remaining == 0;
    }

    private static final class ObjectsText {
        static String value(Object value) { return value == null ? null : value.toString(); }
    }
}
