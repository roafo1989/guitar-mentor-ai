package ai.guitarmentor.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;

import ai.guitarmentor.interfaces.rest.library.LibraryConversionJobController;
import ai.guitarmentor.infrastructure.security.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL integration tests for atomic private PDF/WAV persistence and reservation recovery. */
@SpringBootTest(properties={
    "guitar-mentor.security.enabled=false",
    "guitar-mentor.library.enabled=true",
    "guitar-mentor.library.conversion-enabled=true",
    "guitar-mentor.library.cursor-secret=ci-only-conversion-output-secret-long-enough",
    "guitar-mentor.library.conversion-reclaim-delay-ms=3600000",
    "guitar-mentor.library.conversion-output-recovery-delay-ms=3600000",
    "guitar-mentor.library.reconcile-delay-ms=3600000",
    "guitar-mentor.library.deletion-delay-ms=3600000",
    "guitar-mentor.library.asset-deletion-delay-ms=3600000"
})
@Transactional
@EnabledIfEnvironmentVariable(named="GUITAR_MENTOR_TEST_POSTGRES",matches="true")
class LibraryConversionOutputPostgresTest {
    @Autowired JdbcClient jdbc;
    @Autowired LibraryObjectStore store;
    @Autowired LibraryConversionJobController controller;
    @Autowired LibraryConversionJobLeaseService leases;
    @Autowired LibraryConversionOutputService outputs;
    @Autowired LibraryConversionOutputRecovery recovery;

    @TempDir Path temp;

    private record Fixture(UUID owner,UUID material,UUID asset,String objectKey,String sha) {}

    private Fixture fixture() throws Exception {
        UUID owner=UUID.randomUUID(),material=UUID.randomUUID(),asset=UUID.randomUUID();
        byte[] source="synthetic immutable Guitar Pro source bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var stored=store.put(new ByteArrayInputStream(source),1024);
        jdbc.sql("INSERT INTO accounts(id,role,status) VALUES(:id,'LEARNER','ACTIVE')")
            .param("id",owner).update();
        jdbc.sql("INSERT INTO library_account_usage(account_id) VALUES(:id)").param("id",owner).update();
        jdbc.sql("""
            INSERT INTO library_materials(id,owner_account_id,title,material_type,state,revision)
            VALUES(:id,:owner,'Output conversion fixture','ETUDE','READY',1)
            """).param("id",material).param("owner",owner).update();
        jdbc.sql("""
            INSERT INTO library_assets(id,material_id,object_key,original_name,format,byte_size,sha256)
            VALUES(:id,:material,:key,'original.gp','GP5',:size,:sha)
            """).param("id",asset).param("material",material).param("key",stored.key())
            .param("size",stored.length()).param("sha",stored.sha256()).update();
        return new Fixture(owner,material,asset,stored.key(),stored.sha256());
    }

    private static HttpServletRequest request(UUID owner) {
        var request=new MockHttpServletRequest();
        request.setAttribute("guitarMentorActor",new CurrentActor(owner,null,"LEARNER"));
        return request;
    }

    private LibraryConversionJobLeaseService.Lease lease(Fixture f) {
        var created=controller.create(request(f.owner()),f.material(),UUID.randomUUID().toString(),"\"1\"",
            new LibraryConversionJobController.CreateRequest(f.asset()));
        UUID worker=UUID.randomUUID();
        var lease=leases.claimNext(worker).orElseThrow();
        assertEquals(created.getBody().id(),lease.jobId());
        jdbc.sql("UPDATE library_conversion_jobs SET selected_track_index=0 WHERE id=:job")
            .param("job",lease.jobId()).update();
        return lease;
    }

    private Path pdfFile() throws Exception {
        byte[] bytes=new byte[1400];
        byte[] header="%PDF-1.7\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(header,0,bytes,0,header.length);
        byte[] eof="%%EOF".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(eof,0,bytes,bytes.length-eof.length, eof.length);
        Path path=temp.resolve(UUID.randomUUID()+".pdf");
        Files.write(path,bytes);
        return path;
    }

    private Path wavFile() throws Exception {
        int sampleRate=44100,channels=1,bits=16,dataBytes=sampleRate*channels*(bits/8);
        ByteBuffer b=ByteBuffer.allocate(44+dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        b.putInt(36+dataBytes);
        b.put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        b.putInt(16); b.putShort((short)1); b.putShort((short)channels);
        b.putInt(sampleRate); b.putInt(sampleRate*channels*(bits/8));
        b.putShort((short)(channels*(bits/8))); b.putShort((short)bits);
        b.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        b.putInt(dataBytes);
        b.position(44);
        Path path=temp.resolve(UUID.randomUUID()+".wav");
        Files.write(path,b.array());
        return path;
    }

    private static LibraryConversionProcessRunner.WorkerResult result(Fixture f,Path pdf,Path wav) throws Exception {
        return new LibraryConversionProcessRunner.WorkerResult("RENDERED",null,List.of(),
            0,"Lead Guitar",f.sha(),Files.size(pdf),Files.size(wav),1.0,tempPlaceholder(),pdf,wav);
    }

    private static Path tempPlaceholder() { return Path.of(System.getProperty("java.io.tmpdir")); }

    @Test void persistsPdfAndWavAsOnePrivateLineageAndChargesActualQuota() throws Exception {
        Fixture f=fixture();
        var lease=lease(f);
        Path pdf=pdfFile(),wav=wavFile();
        try {
            var persisted=outputs.persist(lease,result(f,pdf,wav));
            assertEquals(f.sha(),persisted.sourceSha256());
            var job=jdbc.sql("""
                SELECT state,progress,pdf_asset_id,audio_asset_id,output_reservation_state
                FROM library_conversion_jobs WHERE id=:id
                """).param("id",lease.jobId()).query().singleRow();
            assertEquals("SUCCEEDED",job.get("state"));
            assertEquals(100,((Number)job.get("progress")).intValue());
            assertEquals("COMMITTED",job.get("output_reservation_state"));
            UUID pdfId=(UUID)job.get("pdf_asset_id"),audioId=(UUID)job.get("audio_asset_id");
            assertEquals(persisted.pdfAssetId(),pdfId);
            assertEquals(persisted.audioAssetId(),audioId);
            var assets=jdbc.sql("""
                SELECT role,format,media_type,byte_size,sha256 FROM library_assets
                WHERE id IN (:pdf,:audio)
                """).param("pdf",pdfId).param("audio",audioId).query().listOfRows();
            assertEquals(2,assets.size());
            assertTrue(assets.stream().anyMatch(a->"DERIVED_SCORE_PDF".equals(a.get("role"))&&"PDF".equals(a.get("format"))));
            assertTrue(assets.stream().anyMatch(a->"SYNTHESIZED_PREVIEW".equals(a.get("role"))&&"WAV".equals(a.get("format"))));
            var lineage=jdbc.sql("SELECT source_asset_id,source_sha256,score_revision,selected_track_index,pdf_sha256,audio_sha256 FROM library_conversion_output_lineage WHERE job_id=:job")
                .param("job",lease.jobId()).query().singleRow();
            assertEquals(f.asset(),lineage.get("source_asset_id"));
            assertEquals(f.sha(),lineage.get("source_sha256"));
            assertEquals(f.sha(),lineage.get("score_revision"));
            assertEquals(0,((Number)lineage.get("selected_track_index")).intValue());
            var usage=jdbc.sql("SELECT charged_bytes,charged_files,reserved_bytes,reserved_files FROM library_account_usage WHERE account_id=:owner")
                .param("owner",f.owner()).query().singleRow();
            assertEquals(Files.size(pdf)+Files.size(wav),((Number)usage.get("charged_bytes")).longValue());
            assertEquals(3,((Number)usage.get("charged_files")).longValue()); // original + two derivatives
            assertEquals(0,((Number)usage.get("reserved_bytes")).longValue());
            assertEquals(0,((Number)usage.get("reserved_files")).longValue());
            String pdfKey=jdbc.sql("SELECT object_key FROM library_assets WHERE id=:id").param("id",pdfId).query(String.class).single();
            String wavKey=jdbc.sql("SELECT object_key FROM library_assets WHERE id=:id").param("id",audioId).query(String.class).single();
            assertTrue(Files.isRegularFile(store.file(pdfKey)));
            assertTrue(Files.isRegularFile(store.file(wavKey)));
            assertEquals(1,jdbc.sql("SELECT count(*) FROM library_assets WHERE id=:id AND role='SCORE_ORIGINAL'")
                .param("id",f.asset()).query(Long.class).single());
            store.delete(pdfKey); store.delete(wavKey);
        } finally {
            store.delete(f.objectKey());
        }
    }

    @Test void invalidWavCreatesNoPartialAssetAndDoesNotReserveQuota() throws Exception {
        Fixture f=fixture();
        var lease=lease(f);
        Path pdf=pdfFile(),wav=temp.resolve("bad.wav");
        Files.write(wav,new byte[1200]);
        try {
            assertThrows(Exception.class,()->outputs.persist(lease,result(f,pdf,wav)));
            assertEquals(0,jdbc.sql("SELECT count(*) FROM library_assets WHERE material_id=:material AND role IN ('DERIVED_SCORE_PDF','SYNTHESIZED_PREVIEW')")
                .param("material",f.material()).query(Long.class).single());
            var job=jdbc.sql("SELECT state,output_reservation_state FROM library_conversion_jobs WHERE id=:job")
                .param("job",lease.jobId()).query().singleRow();
            assertEquals("RUNNING",job.get("state"));
            assertEquals("NONE",job.get("output_reservation_state"));
            var usage=jdbc.sql("SELECT reserved_bytes,reserved_files FROM library_account_usage WHERE account_id=:owner")
                .param("owner",f.owner()).query().singleRow();
            assertEquals(0,((Number)usage.get("reserved_bytes")).longValue());
            assertEquals(0,((Number)usage.get("reserved_files")).longValue());
        } finally { store.delete(f.objectKey()); }
    }

    @Test void recoveryReleasesReservationOnlyAfterLeaseIsGone() throws Exception {
        Fixture f=fixture();
        var lease=lease(f);
        jdbc.sql("""
            UPDATE library_account_usage SET reserved_bytes=41943040,reserved_files=2 WHERE account_id=:owner
            """).param("owner",f.owner()).update();
        jdbc.sql("""
            UPDATE library_conversion_jobs SET output_reserved_bytes=41943040,
                output_reserved_files=2,output_reservation_state='RESERVED' WHERE id=:job
            """).param("job",lease.jobId()).update();
        assertEquals(0,recovery.releaseAbandonedReservations(10));
        jdbc.sql("""
            UPDATE library_conversion_worker_slots SET lease_until=now()-interval '1 second' WHERE job_id=:job
            """).param("job",lease.jobId()).update();
        assertEquals(1,recovery.releaseAbandonedReservations(10));
        var usage=jdbc.sql("SELECT reserved_bytes,reserved_files FROM library_account_usage WHERE account_id=:owner")
            .param("owner",f.owner()).query().singleRow();
        assertEquals(0,((Number)usage.get("reserved_bytes")).longValue());
        assertEquals(0,((Number)usage.get("reserved_files")).longValue());
        assertEquals("RELEASED",jdbc.sql("SELECT output_reservation_state FROM library_conversion_jobs WHERE id=:job")
            .param("job",lease.jobId()).query(String.class).single());
        store.delete(f.objectKey());
    }
}
