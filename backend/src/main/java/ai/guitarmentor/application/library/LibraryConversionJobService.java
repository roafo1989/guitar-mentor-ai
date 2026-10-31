package ai.guitarmentor.application.library;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Owner-scoped durable conversion job commands. Renderer execution is a later increment (#170-B). */
@Service
public final class LibraryConversionJobService {
    private static final long MAX_SOURCE_BYTES = 20L * 1024 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<TrackView>> TRACKS = new TypeReference<>() {};
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final boolean libraryEnabled;
    private final boolean conversionEnabled;

    public LibraryConversionJobService(JdbcClient jdbc, TransactionTemplate transactions,
            @Value("${guitar-mentor.library.enabled:false}") boolean libraryEnabled,
            @Value("${guitar-mentor.library.conversion-enabled:false}") boolean conversionEnabled) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.libraryEnabled = libraryEnabled;
        this.conversionEnabled = conversionEnabled;
    }

    public record TrackView(int trackIndex, String name, boolean guitar, String instrument) {}
    public record JobView(UUID id, UUID materialId, UUID sourceAssetId, String sourceFormat,
            String state, String stage, int progress, List<TrackView> tracks,
            Integer selectedTrackIndex, boolean cancelRequested, int attemptCount,
            boolean retryable, String errorCode, UUID pdfAssetId, UUID audioAssetId,
            long revision, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
    public record Created(JobView job, boolean replayed) {}

    public Created create(UUID owner, UUID materialId, UUID sourceAssetId,
            UUID idempotencyKey, long expectedMaterialRevision) {
        requireEnabled();
        if (idempotencyKey == null || materialId == null || sourceAssetId == null)
            throw failure(400, "CONVERSION_INVALID_REQUEST");
        String fingerprint = fingerprint(materialId, sourceAssetId);
        return transactions.execute(status -> {
            var replay = jdbc.sql("""
                SELECT * FROM library_conversion_jobs
                WHERE owner_account_id=:owner AND idempotency_key=:key
                """).param("owner", owner).param("key", idempotencyKey).query().listOfRows();
            if (!replay.isEmpty()) {
                Map<String,Object> existing = replay.getFirst();
                if (!fingerprint.equals(existing.get("request_fingerprint")))
                    throw failure(409, "IDEMPOTENCY_KEY_REUSED");
                return new Created(view(existing), true);
            }

            jdbc.sql("INSERT INTO library_account_usage(account_id) VALUES(:owner) ON CONFLICT DO NOTHING")
                .param("owner", owner).update();
            jdbc.sql("SELECT account_id FROM library_account_usage WHERE account_id=:owner FOR UPDATE")
                .param("owner", owner).query().singleRow();

            // Recheck after the owner lock: concurrent identical POSTs must replay, not 409.
            var replayAfterLock = jdbc.sql("""
                SELECT * FROM library_conversion_jobs
                WHERE owner_account_id=:owner AND idempotency_key=:key
                """).param("owner", owner).param("key", idempotencyKey).query().listOfRows();
            if (!replayAfterLock.isEmpty()) {
                Map<String,Object> existing = replayAfterLock.getFirst();
                if (!fingerprint.equals(existing.get("request_fingerprint")))
                    throw failure(409, "IDEMPOTENCY_KEY_REUSED");
                return new Created(view(existing), true);
            }

            var materials = jdbc.sql("""
                SELECT revision,state FROM library_materials
                WHERE id=:material AND owner_account_id=:owner FOR UPDATE
                """).param("material", materialId).param("owner", owner).query().listOfRows();
            if (materials.isEmpty()) throw failure(404, "LIBRARY_MATERIAL_NOT_FOUND");
            Map<String,Object> material = materials.getFirst();
            if (!"READY".equals(material.get("state")))
                throw failure(409, "LIBRARY_MATERIAL_NOT_READY");
            long actualRevision = ((Number) material.get("revision")).longValue();
            if (actualRevision != expectedMaterialRevision)
                throw failure(412, "LIBRARY_REVISION_CONFLICT");

            var assets = jdbc.sql("""
                SELECT id,role,format,byte_size,sha256,processing_state
                FROM library_assets
                WHERE id=:asset AND material_id=:material FOR UPDATE
                """).param("asset", sourceAssetId).param("material", materialId).query().listOfRows();
            if (assets.isEmpty()) throw failure(404, "LIBRARY_ASSET_NOT_FOUND");
            Map<String,Object> asset = assets.getFirst();
            if (!"SCORE_ORIGINAL".equals(asset.get("role")))
                throw failure(415, "CONVERSION_SOURCE_ROLE_UNSUPPORTED");
            String format = Objects.toString(asset.get("format"), "");
            if (!List.of("GP3", "GP4", "GP5").contains(format))
                throw failure(415, "CONVERSION_FORMAT_UNSUPPORTED");
            if (!"READY".equals(asset.get("processing_state")))
                throw failure(409, "CONVERSION_SOURCE_NOT_READY");
            long size = ((Number) asset.get("byte_size")).longValue();
            if (size < 1 || size > MAX_SOURCE_BYTES)
                throw failure(413, "CONVERSION_SOURCE_TOO_LARGE");

            var active = jdbc.sql("""
                SELECT id FROM library_conversion_jobs
                WHERE owner_account_id=:owner
                  AND state IN ('QUEUED','RUNNING','AWAITING_TRACK_SELECTION','CANCEL_REQUESTED')
                FOR UPDATE
                """).param("owner", owner).query(UUID.class).list();
            if (!active.isEmpty()) throw failure(409, "CONVERSION_JOB_ALREADY_ACTIVE");

            UUID jobId = UUID.randomUUID();
            jdbc.sql("""
                INSERT INTO library_conversion_jobs(
                    id,owner_account_id,material_id,source_asset_id,source_sha256,
                    source_format,source_byte_size,idempotency_key,request_fingerprint,
                    state,stage,progress,tracks,revision)
                VALUES(:id,:owner,:material,:asset,:sha,:format,:size,:key,:fingerprint,
                    'QUEUED',NULL,0,'[]'::jsonb,1)
                """).param("id", jobId).param("owner", owner).param("material", materialId)
                .param("asset", sourceAssetId).param("sha", asset.get("sha256"))
                .param("format", format).param("size", size).param("key", idempotencyKey)
                .param("fingerprint", fingerprint).update();
            Map<String,Object> inserted = jdbc.sql("SELECT * FROM library_conversion_jobs WHERE id=:id")
                .param("id", jobId).query().singleRow();
            return new Created(view(inserted), false);
        });
    }

    public JobView get(UUID owner, UUID materialId, UUID jobId) {
        requireEnabled();
        var rows = jdbc.sql("""
            SELECT * FROM library_conversion_jobs
            WHERE id=:job AND material_id=:material AND owner_account_id=:owner
            """).param("job", jobId).param("material", materialId).param("owner", owner)
            .query().listOfRows();
        if (rows.isEmpty()) throw failure(404, "CONVERSION_JOB_NOT_FOUND");
        return view(rows.getFirst());
    }

    public JobView selectTrack(UUID owner, UUID materialId, UUID jobId,
            long expectedRevision, int trackIndex) {
        requireEnabled();
        return transactions.execute(status -> {
            Map<String,Object> job = lockJob(owner, materialId, jobId);
            checkRevision(job, expectedRevision);
            if (!"AWAITING_TRACK_SELECTION".equals(job.get("state")))
                throw failure(409, "CONVERSION_INVALID_JOB_STATE");
            List<TrackView> tracks = tracks(job.get("tracks"));
            TrackView selected = tracks.stream().filter(t -> t.trackIndex() == trackIndex)
                .findFirst().orElseThrow(() -> failure(400, "CONVERSION_TRACK_NOT_FOUND"));
            if (!selected.guitar()) throw failure(400, "CONVERSION_TRACK_NOT_GUITAR");
            jdbc.sql("""
                UPDATE library_conversion_jobs
                SET selected_track_index=:track,state='QUEUED',stage=NULL,progress=0,
                    revision=revision+1,updated_at=now()
                WHERE id=:job
                """).param("track", trackIndex).param("job", jobId).update();
            return view(loadJob(jobId));
        });
    }

    public JobView cancel(UUID owner, UUID materialId, UUID jobId, long expectedRevision) {
        requireEnabled();
        return transactions.execute(status -> {
            Map<String,Object> job = lockJob(owner, materialId, jobId);
            checkRevision(job, expectedRevision);
            String state = Objects.toString(job.get("state"), "");
            if ("CANCELLED".equals(state) || "CANCEL_REQUESTED".equals(state)) return view(job);
            if ("QUEUED".equals(state) || "AWAITING_TRACK_SELECTION".equals(state)) {
                jdbc.sql("""
                    UPDATE library_conversion_jobs SET state='CANCELLED',cancel_requested=TRUE,
                        error_code='CANCELLED_BY_USER',finished_at=now(),updated_at=now(),
                        revision=revision+1 WHERE id=:job
                    """).param("job", jobId).update();
            } else if ("RUNNING".equals(state)) {
                jdbc.sql("""
                    UPDATE library_conversion_jobs SET state='CANCEL_REQUESTED',cancel_requested=TRUE,
                        updated_at=now(),revision=revision+1 WHERE id=:job
                    """).param("job", jobId).update();
            } else {
                throw failure(409, "CONVERSION_INVALID_JOB_STATE");
            }
            return view(loadJob(jobId));
        });
    }

    public JobView retry(UUID owner, UUID materialId, UUID jobId, long expectedRevision) {
        requireEnabled();
        return transactions.execute(status -> {
            Map<String,Object> job = lockJob(owner, materialId, jobId);
            checkRevision(job, expectedRevision);
            String state = Objects.toString(job.get("state"), "");
            boolean retryable = Boolean.TRUE.equals(job.get("retryable"));
            int attempts = ((Number) job.get("attempt_count")).intValue();
            if (!(("FAILED".equals(state) || "TIMED_OUT".equals(state))
                    && retryable && attempts < 2))
                throw failure(409, "CONVERSION_RETRY_NOT_ALLOWED");
            jdbc.sql("""
                UPDATE library_conversion_jobs SET state='QUEUED',stage=NULL,progress=0,
                    cancel_requested=FALSE,retryable=FALSE,error_code=NULL,finished_at=NULL,
                    updated_at=now(),revision=revision+1 WHERE id=:job
                """).param("job", jobId).update();
            return view(loadJob(jobId));
        });
    }

    private Map<String,Object> lockJob(UUID owner, UUID materialId, UUID jobId) {
        var rows = jdbc.sql("""
            SELECT * FROM library_conversion_jobs
            WHERE id=:job AND material_id=:material AND owner_account_id=:owner
            FOR UPDATE
            """).param("job", jobId).param("material", materialId).param("owner", owner)
            .query().listOfRows();
        if (rows.isEmpty()) throw failure(404, "CONVERSION_JOB_NOT_FOUND");
        return rows.getFirst();
    }

    private Map<String,Object> loadJob(UUID jobId) {
        return jdbc.sql("SELECT * FROM library_conversion_jobs WHERE id=:job")
            .param("job", jobId).query().singleRow();
    }

    private void checkRevision(Map<String,Object> job, long expected) {
        if (((Number) job.get("revision")).longValue() != expected)
            throw failure(412, "CONVERSION_REVISION_CONFLICT");
    }

    private List<TrackView> tracks(Object json) {
        try {
            return MAPPER.readValue(Objects.toString(json, "[]"), TRACKS);
        } catch (Exception e) {
            throw new IllegalStateException("Persisted conversion track catalog is invalid", e);
        }
    }

    private JobView view(Map<String,Object> row) {
        Object selected = row.get("selected_track_index");
        return new JobView((UUID) row.get("id"), (UUID) row.get("material_id"),
            (UUID) row.get("source_asset_id"), Objects.toString(row.get("source_format"), ""),
            Objects.toString(row.get("state"), ""), (String) row.get("stage"),
            ((Number) row.get("progress")).intValue(), tracks(row.get("tracks")),
            selected == null ? null : ((Number) selected).intValue(),
            Boolean.TRUE.equals(row.get("cancel_requested")),
            ((Number) row.get("attempt_count")).intValue(),
            Boolean.TRUE.equals(row.get("retryable")), (String) row.get("error_code"),
            (UUID) row.get("pdf_asset_id"), (UUID) row.get("audio_asset_id"),
            ((Number) row.get("revision")).longValue(), date(row.get("created_at")),
            date(row.get("updated_at")));
    }

    private static OffsetDateTime date(Object value) {
        if (value instanceof OffsetDateTime date) return date;
        if (value instanceof java.sql.Timestamp timestamp)
            return timestamp.toInstant().atOffset(ZoneOffset.UTC);
        throw new IllegalStateException("Unexpected timestamp type: " + value);
    }

    private static String fingerprint(UUID materialId, UUID assetId) {
        try {
            byte[] bytes = ("library-conversion-v1\n" + materialId + "\n" + assetId)
                .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void requireEnabled() {
        if (!libraryEnabled) throw failure(503, "LIBRARY_DISABLED");
        if (!conversionEnabled) throw failure(503, "LIBRARY_CONVERSION_DISABLED");
    }

    private static ConversionJobException failure(int status, String code) {
        return new ConversionJobException(status, code);
    }

    public static final class ConversionJobException extends RuntimeException {
        private final int status;
        private final String code;
        public ConversionJobException(int status, String code) {
            super(code); this.status = status; this.code = code;
        }
        public int status() { return status; }
        public String code() { return code; }
    }
}
