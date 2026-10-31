package ai.guitarmentor.infrastructure.persistence;

import ai.guitarmentor.infrastructure.librarymedia.LibraryMediaInspector;
import ai.guitarmentor.infrastructure.librarymedia.LibraryMediaInspector.Audio;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persists the two outputs of one conversion as a single private-library commit.
 * Physical objects are staged first; DB rows, quota charge, lineage and SUCCEEDED
 * are committed together. Unreferenced objects are safe for orphan recovery.
 */
@Component
public final class LibraryConversionOutputService {
    private static final long MAX_OUTPUT_BYTES = 20L * 1024 * 1024;
    private static final long RESERVED_BYTES = MAX_OUTPUT_BYTES * 2;
    private static final int RESERVED_FILES = 2;
    private static final int MAX_MATERIAL_ASSETS = 7;
    private static final int MAX_SCORE_AND_PHOTO_ASSETS = 5;
    private static final int MAX_AUDIO_ASSETS = 2;

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final LibraryObjectStore store;
    private final long maxAccountBytes;
    private final int maxAccountFiles;

    public LibraryConversionOutputService(JdbcClient jdbc, TransactionTemplate transactions,
            LibraryObjectStore store,
            @Value("${guitar-mentor.library.max-account-bytes:209715200}") long maxAccountBytes,
            @Value("${guitar-mentor.library.max-account-files:100}") int maxAccountFiles) {
        this.jdbc=jdbc; this.transactions=transactions; this.store=store;
        this.maxAccountBytes=maxAccountBytes; this.maxAccountFiles=maxAccountFiles;
        if(maxAccountBytes<RESERVED_BYTES || maxAccountFiles<RESERVED_FILES)
            throw new IllegalStateException("Library quota must accommodate one conversion reservation");
    }

    public record Persisted(UUID pdfAssetId, UUID audioAssetId, String sourceSha256,
            String pdfSha256, String audioSha256, long pdfBytes, long audioBytes,
            long audioDurationMillis, int audioChannels, int audioSampleRate) {}

    public Persisted persist(LibraryConversionJobLeaseService.Lease lease,
            LibraryConversionProcessRunner.WorkerResult output) throws IOException {
        if(lease==null || output==null || !output.rendered() || output.workspace()==null
                || output.pdfPath()==null || output.wavPath()==null)
            throw new IllegalArgumentException("A complete rendered output pair is required");
        if(!lease.sourceSha256().equals(output.sourceSha256())
                || lease.sourceFormat()==null || output.selectedTrackIndex()==null)
            throw new IllegalArgumentException("Conversion output lineage mismatch");

        byte[] pdfBytes=readBounded(output.pdfPath());
        byte[] wavBytes=readBounded(output.wavPath());
        validatePdf(pdfBytes);
        Audio audio=LibraryMediaInspector.inspectAudio(wavBytes,"WAV","audio/wav");
        if(audio.durationMillis()<1 || audio.durationMillis()>LibraryMediaInspector.MAX_DURATION_MILLIS)
            throw new IOException("Converted audio duration is outside library limits");

        reserve(lease,output.selectedTrackIndex());
        LibraryObjectStore.Stored pdf=null, wav=null;
        try {
            pdf=store.put(new java.io.ByteArrayInputStream(pdfBytes),MAX_OUTPUT_BYTES);
            wav=store.put(new java.io.ByteArrayInputStream(wavBytes),MAX_OUTPUT_BYTES);
            UUID pdfId=UUID.randomUUID(), audioId=UUID.randomUUID();
            LibraryObjectStore.Stored finalPdf=pdf, finalWav=wav;
            Persisted result=transactions.execute(status -> commit(lease,output,pdfId,audioId,finalPdf,finalWav,audio));
            return result;
        } catch(RuntimeException|IOException error) {
            cleanupIfUnreferenced(pdf);
            cleanupIfUnreferenced(wav);
            releaseReservation(lease);
            throw error;
        } finally {
            // Caller owns and removes the renderer workspace after persistence returns.
            java.util.Arrays.fill(pdfBytes,(byte)0);
            java.util.Arrays.fill(wavBytes,(byte)0);
        }
    }

    private void reserve(LibraryConversionJobLeaseService.Lease lease,int selectedTrack) {
        transactions.executeWithoutResult(status -> {
            lockUsage(lease.ownerAccountId());
            lockMaterialAndSource(lease);
            requireLiveLease(lease);
            Map<String,Object> job=lockJob(lease);
            if(!"RUNNING".equals(job.get("state")) || Boolean.TRUE.equals(job.get("cancel_requested")))
                throw new IllegalStateException("Conversion job is no longer active");
            Object selected=job.get("selected_track_index");
            if(selected==null || ((Number)selected).intValue()!=selectedTrack)
                throw new IllegalStateException("Selected track changed during conversion");
            String reservationState=Objects.toString(job.get("output_reservation_state"),"NONE");
            if("RESERVED".equals(reservationState)) {
                int priorAttempt=((Number)job.get("output_reservation_attempt")).intValue();
                if(priorAttempt>=lease.attemptCount())
                    throw new IllegalStateException("Output quota is already reserved for this worker attempt");
                int released=jdbc.sql("""
                    UPDATE library_account_usage SET reserved_bytes=reserved_bytes-:bytes,
                        reserved_files=reserved_files-:files,revision=revision+1
                    WHERE account_id=:owner AND reserved_bytes>=:bytes AND reserved_files>=:files
                    """).param("bytes",job.get("output_reserved_bytes"))
                    .param("files",job.get("output_reserved_files")).param("owner",lease.ownerAccountId()).update();
                if(released!=1) throw new IllegalStateException("Stale conversion reservation invariant violated");
                jdbc.sql("""
                    UPDATE library_conversion_jobs SET output_reserved_bytes=0,output_reserved_files=0,
                        output_reservation_state='RELEASED'
                    WHERE id=:job AND output_reservation_state='RESERVED'
                    """).param("job",lease.jobId()).update();
            } else if(!List.of("NONE","RELEASED").contains(reservationState)) {
                throw new IllegalStateException("Output quota reservation is already committed");
            }
            ensureMaterialCapacity(lease.materialId());
            Map<String,Object> usage=jdbc.sql("""
                SELECT charged_bytes,charged_files,reserved_bytes,reserved_files
                FROM library_account_usage WHERE account_id=:owner FOR UPDATE
                """).param("owner",lease.ownerAccountId()).query().singleRow();
            long used=((Number)usage.get("charged_bytes")).longValue()+((Number)usage.get("reserved_bytes")).longValue();
            long files=((Number)usage.get("charged_files")).longValue()+((Number)usage.get("reserved_files")).longValue();
            if(used>maxAccountBytes-RESERVED_BYTES || files>maxAccountFiles-RESERVED_FILES)
                throw new QuotaExceeded();
            jdbc.sql("""
                UPDATE library_account_usage SET reserved_bytes=reserved_bytes+:bytes,
                    reserved_files=reserved_files+:files,revision=revision+1 WHERE account_id=:owner
                """).param("bytes",RESERVED_BYTES).param("files",RESERVED_FILES)
                .param("owner",lease.ownerAccountId()).update();
            int updated=jdbc.sql("""
                UPDATE library_conversion_jobs SET output_reserved_bytes=:bytes,
                    output_reserved_files=:files,output_reservation_attempt=:attempt,
                    output_reservation_state='RESERVED',
                    updated_at=now(),revision=revision+1
                WHERE id=:job AND state='RUNNING' AND output_reservation_state='NONE'
                """).param("bytes",RESERVED_BYTES).param("files",RESERVED_FILES)
                .param("attempt",lease.attemptCount()).param("job",lease.jobId()).update();
            if(updated!=1) throw new IllegalStateException("Conversion reservation race");
        });
    }

    private Persisted commit(LibraryConversionJobLeaseService.Lease lease,
            LibraryConversionProcessRunner.WorkerResult output,UUID pdfId,UUID audioId,
            LibraryObjectStore.Stored pdf,LibraryObjectStore.Stored wav,Audio audio) {
        lockUsage(lease.ownerAccountId());
        lockMaterialAndSource(lease);
        requireLiveLease(lease);
        Map<String,Object> job=lockJob(lease);
        if(!"RUNNING".equals(job.get("state")) || Boolean.TRUE.equals(job.get("cancel_requested"))
                || !"RESERVED".equals(job.get("output_reservation_state"))
                || ((Number)job.get("output_reserved_bytes")).longValue()!=RESERVED_BYTES
                || ((Number)job.get("output_reserved_files")).intValue()!=RESERVED_FILES
                || ((Number)job.get("output_reservation_attempt")).intValue()!=lease.attemptCount()
                || job.get("selected_track_index")==null
                || ((Number)job.get("selected_track_index")).intValue()!=output.selectedTrackIndex())
            throw new IllegalStateException("Conversion job changed before output commit");
        ensureMaterialCapacity(lease.materialId());
        long actualBytes=pdf.length()+wav.length();
        Map<String,Object> usage=jdbc.sql("""
            SELECT reserved_bytes,reserved_files FROM library_account_usage
            WHERE account_id=:owner FOR UPDATE
            """).param("owner",lease.ownerAccountId()).query().singleRow();
        if(((Number)usage.get("reserved_bytes")).longValue()<RESERVED_BYTES
                || ((Number)usage.get("reserved_files")).intValue()<RESERVED_FILES)
            throw new IllegalStateException("Conversion quota reservation is missing");

        jdbc.sql("""
            INSERT INTO library_assets(id,material_id,object_key,original_name,format,byte_size,sha256,
                role,media_type,processing_state)
            VALUES(:id,:material,:key,'converted-score.pdf','PDF',:size,:sha,
                'DERIVED_SCORE_PDF','application/pdf','READY')
            """).param("id",pdfId).param("material",lease.materialId()).param("key",pdf.key())
            .param("size",pdf.length()).param("sha",pdf.sha256()).update();
        jdbc.sql("""
            INSERT INTO library_assets(id,material_id,object_key,original_name,format,byte_size,sha256,
                role,media_type,duration_millis,channels,sample_rate,processing_state)
            VALUES(:id,:material,:key,'synthesized-preview.wav','WAV',:size,:sha,
                'SYNTHESIZED_PREVIEW','audio/wav',:duration,:channels,:rate,'READY')
            """).param("id",audioId).param("material",lease.materialId()).param("key",wav.key())
            .param("size",wav.length()).param("sha",wav.sha256()).param("duration",audio.durationMillis())
            .param("channels",audio.channels()).param("rate",audio.sampleRate()).update();

        int usageUpdated=jdbc.sql("""
            UPDATE library_account_usage SET charged_bytes=charged_bytes+:actualBytes,
                charged_files=charged_files+2,reserved_bytes=reserved_bytes-:reservedBytes,
                reserved_files=reserved_files-2,revision=revision+1
            WHERE account_id=:owner AND reserved_bytes>=:reservedBytes AND reserved_files>=2
            """).param("actualBytes",actualBytes).param("reservedBytes",RESERVED_BYTES)
            .param("owner",lease.ownerAccountId()).update();
        if(usageUpdated!=1) throw new IllegalStateException("Conversion quota accounting invariant violated");

        jdbc.sql("""
            INSERT INTO library_conversion_output_lineage(job_id,owner_account_id,material_id,
                source_asset_id,source_sha256,selected_track_index,score_revision,
                pdf_asset_id,audio_asset_id,pdf_sha256,audio_sha256)
            VALUES(:job,:owner,:material,:source,:sourceSha,:track,:revision,:pdf,:audio,:pdfSha,:audioSha)
            """).param("job",lease.jobId()).param("owner",lease.ownerAccountId())
            .param("material",lease.materialId()).param("source",lease.sourceAssetId())
            .param("sourceSha",lease.sourceSha256()).param("track",output.selectedTrackIndex())
            .param("revision",output.sourceSha256()).param("pdf",pdfId).param("audio",audioId)
            .param("pdfSha",pdf.sha256()).param("audioSha",wav.sha256()).update();

        int materialUpdated=jdbc.sql("""
            UPDATE library_materials SET revision=revision+1,updated_at=now()
            WHERE id=:material AND owner_account_id=:owner AND state='READY'
            """).param("material",lease.materialId()).param("owner",lease.ownerAccountId()).update();
        if(materialUpdated!=1) throw new IllegalStateException("Library material changed during conversion");
        int jobUpdated=jdbc.sql("""
            UPDATE library_conversion_jobs SET state='SUCCEEDED',stage='VERIFYING_OUTPUTS',
                progress=100,pdf_asset_id=:pdf,audio_asset_id=:audio,error_code=NULL,retryable=FALSE,
                finished_at=now(),updated_at=now(),revision=revision+1,
                output_reserved_bytes=0,output_reserved_files=0,output_reservation_state='COMMITTED'
            WHERE id=:job AND state='RUNNING' AND output_reservation_state='RESERVED'
            """).param("pdf",pdfId).param("audio",audioId).param("job",lease.jobId()).update();
        if(jobUpdated!=1) throw new IllegalStateException("Conversion job completion race");
        jdbc.sql("""
            UPDATE library_conversion_worker_slots
            SET job_id=NULL,lease_owner=NULL,lease_until=NULL,heartbeat_at=NULL
            WHERE job_id=:job AND lease_owner=:worker AND lease_until>now()
            """).param("job",lease.jobId()).param("worker",lease.leaseOwner()).update();
        return new Persisted(pdfId,audioId,lease.sourceSha256(),pdf.sha256(),wav.sha256(),
            pdf.length(),wav.length(),audio.durationMillis(),audio.channels(),audio.sampleRate());
    }

    private void lockUsage(UUID owner) {
        jdbc.sql("INSERT INTO library_account_usage(account_id) VALUES(:owner) ON CONFLICT DO NOTHING")
            .param("owner",owner).update();
        jdbc.sql("SELECT account_id FROM library_account_usage WHERE account_id=:owner FOR UPDATE")
            .param("owner",owner).query().singleRow();
    }

    private Map<String,Object> lockJob(LibraryConversionJobLeaseService.Lease lease) {
        return jdbc.sql("""
            SELECT * FROM library_conversion_jobs
            WHERE id=:job AND owner_account_id=:owner AND material_id=:material FOR UPDATE
            """).param("job",lease.jobId()).param("owner",lease.ownerAccountId())
            .param("material",lease.materialId()).query().singleRow();
    }

    private void lockMaterialAndSource(LibraryConversionJobLeaseService.Lease lease) {
        var material=jdbc.sql("""
            SELECT state FROM library_materials WHERE id=:material AND owner_account_id=:owner FOR UPDATE
            """).param("material",lease.materialId()).param("owner",lease.ownerAccountId()).query().listOfRows();
        if(material.isEmpty() || !"READY".equals(material.getFirst().get("state")))
            throw new IllegalStateException("Source material is not available");
        var source=jdbc.sql("""
            SELECT sha256,role,format,byte_size,processing_state,object_key FROM library_assets
            WHERE id=:asset AND material_id=:material FOR UPDATE
            """).param("asset",lease.sourceAssetId()).param("material",lease.materialId()).query().listOfRows();
        if(source.isEmpty()) throw new IllegalStateException("Source asset is not available");
        Map<String,Object> row=source.getFirst();
        if(!"SCORE_ORIGINAL".equals(row.get("role")) || !"READY".equals(row.get("processing_state"))
                || !lease.sourceFormat().equals(row.get("format"))
                || !lease.sourceSha256().equals(row.get("sha256"))
                || ((Number)row.get("byte_size")).longValue()!=lease.sourceByteSize())
            throw new IllegalStateException("Source lineage changed");
        try {
            Path sourceObject=store.file((String)row.get("object_key"));
            if(!lease.sourceSha256().equals(sha256(sourceObject)))
                throw new IllegalStateException("Stored source object hash mismatch");
        } catch(IOException error) {
            throw new IllegalStateException("Stored source object is unavailable",error);
        }
    }

    private void requireLiveLease(LibraryConversionJobLeaseService.Lease lease) {
        List<Integer> slots=jdbc.sql("""
            SELECT slot_number FROM library_conversion_worker_slots
            WHERE slot_number=:slot AND job_id=:job AND lease_owner=:worker AND lease_until>now()
            FOR UPDATE
            """).param("slot",lease.slotNumber()).param("job",lease.jobId())
            .param("worker",lease.leaseOwner()).query(Integer.class).list();
        if(slots.size()!=1) throw new IllegalStateException("Conversion worker lease was lost");
    }

    private void ensureMaterialCapacity(UUID materialId) {
        Long total=jdbc.sql("SELECT count(*) FROM library_assets WHERE material_id=:id")
            .param("id",materialId).query(Long.class).single();
        Long scores=jdbc.sql("SELECT count(*) FROM library_assets WHERE material_id=:id AND role IN ('SCORE_ORIGINAL','DERIVED_SCORE_PDF','PHOTO_ORIGINAL')")
            .param("id",materialId).query(Long.class).single();
        Long audio=jdbc.sql("SELECT count(*) FROM library_assets WHERE material_id=:id AND role IN ('ORIGINAL_RECORDING','SYNTHESIZED_PREVIEW')")
            .param("id",materialId).query(Long.class).single();
        if(total+2>MAX_MATERIAL_ASSETS || scores+1>MAX_SCORE_AND_PHOTO_ASSETS || audio+1>MAX_AUDIO_ASSETS)
            throw new MaterialCapacityExceeded();
    }

    private void releaseReservation(LibraryConversionJobLeaseService.Lease lease) {
        UUID jobId=lease.jobId(), owner=lease.ownerAccountId();
        try {
            transactions.executeWithoutResult(status -> {
                lockUsage(owner);
                var live=jdbc.sql("""
                    SELECT slot_number FROM library_conversion_worker_slots
                    WHERE job_id=:job AND lease_owner=:worker AND lease_until>now() FOR UPDATE
                    """).param("job",jobId).param("worker",lease.leaseOwner()).query(Integer.class).list();
                if(live.size()!=1) return; // Expired/lost leases are handled by scheduled recovery.
                var rows=jdbc.sql("""
                    SELECT output_reserved_bytes,output_reserved_files,output_reservation_state,output_reservation_attempt
                    FROM library_conversion_jobs WHERE id=:job AND owner_account_id=:owner FOR UPDATE
                    """).param("job",jobId).param("owner",owner).query().listOfRows();
                if(rows.isEmpty() || !"RESERVED".equals(rows.getFirst().get("output_reservation_state"))
                        || ((Number)rows.getFirst().get("output_reservation_attempt")).intValue()!=lease.attemptCount()) return;
                long bytes=((Number)rows.getFirst().get("output_reserved_bytes")).longValue();
                int files=((Number)rows.getFirst().get("output_reserved_files")).intValue();
                int updated=jdbc.sql("""
                    UPDATE library_account_usage SET reserved_bytes=reserved_bytes-:bytes,
                        reserved_files=reserved_files-:files,revision=revision+1
                    WHERE account_id=:owner AND reserved_bytes>=:bytes AND reserved_files>=:files
                    """).param("bytes",bytes).param("files",files).param("owner",owner).update();
                if(updated!=1) throw new IllegalStateException("Conversion reservation release invariant violated");
                jdbc.sql("""
                    UPDATE library_conversion_jobs SET output_reserved_bytes=0,output_reserved_files=0,
                        output_reservation_state='RELEASED',updated_at=now(),revision=revision+1
                    WHERE id=:job AND output_reservation_state='RESERVED'
                    """).param("job",jobId).update();
            });
        } catch(RuntimeException ignored) {
            // Durable reservation recovery owns a failed release after DB/storage outage.
        }
    }

    private void cleanupIfUnreferenced(LibraryObjectStore.Stored object) {
        if(object==null) return;
        try {
            Boolean referenced=jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM library_assets WHERE object_key=:key OR preview_object_key=:key)
                """).param("key",object.key()).query(Boolean.class).single();
            if(Boolean.FALSE.equals(referenced)) store.delete(object.key());
        } catch(Exception ignored) {
            // LibraryOrphanRecovery removes unknown objects after the grace period.
        }
    }

    private static String sha256(Path path) throws IOException {
        try(var input=Files.newInputStream(path)) {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer=new byte[8192];
            for(int count;(count=input.read(buffer))!=-1;) if(count>0) digest.update(buffer,0,count);
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch(java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] readBounded(Path path) throws IOException {
        if(path==null || Files.isSymbolicLink(path) || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Conversion output unavailable");
        long size=Files.size(path);
        if(size<1000 || size>MAX_OUTPUT_BYTES) throw new IOException("Conversion output size invalid");
        return Files.readAllBytes(path);
    }

    private static void validatePdf(byte[] bytes) throws IOException {
        if(bytes.length<1000 || !new String(bytes,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))
            throw new IOException("Invalid PDF signature");
        int start=Math.max(0,bytes.length-2048);
        String tail=new String(bytes,start,bytes.length-start,java.nio.charset.StandardCharsets.ISO_8859_1);
        if(!tail.contains("%%EOF")) throw new IOException("Incomplete PDF");
    }

    public static final class QuotaExceeded extends RuntimeException {}
    public static final class MaterialCapacityExceeded extends RuntimeException {}
}
