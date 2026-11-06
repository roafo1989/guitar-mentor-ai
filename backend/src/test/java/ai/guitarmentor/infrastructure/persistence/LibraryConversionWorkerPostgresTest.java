package ai.guitarmentor.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import ai.guitarmentor.interfaces.rest.library.*;
import ai.guitarmentor.infrastructure.security.CurrentActor;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties={"guitar-mentor.security.enabled=false","guitar-mentor.library.enabled=true",
    "guitar-mentor.library.conversion-enabled=true","guitar-mentor.library.conversion-worker-enabled=false",
    "guitar-mentor.library.cursor-secret=ci-only-conversion-170e-secret-long-enough",
    "guitar-mentor.library.conversion-reclaim-delay-ms=3600000",
    "guitar-mentor.library.conversion-output-recovery-delay-ms=3600000",
    "guitar-mentor.library.reconcile-delay-ms=3600000","guitar-mentor.library.deletion-delay-ms=3600000",
    "guitar-mentor.library.asset-deletion-delay-ms=3600000"})
@Transactional
@EnabledIfEnvironmentVariable(named="GUITAR_MENTOR_TEST_POSTGRES",matches="true")
class LibraryConversionWorkerPostgresTest {
    @Autowired JdbcClient jdbc;
    @Autowired LibraryObjectStore store;
    @Autowired LibraryConversionJobLeaseService leases;
    @Autowired LibraryConversionOutputService outputs;
    @Autowired LibraryConversionJobController jobs;
    @Autowired LibraryOriginalController originals;
    @Autowired LibraryMaterialController materials;
    @TempDir Path temp;
    private record Fixture(UUID owner,UUID material,UUID asset,String sha,byte[] bytes) {}
    private MockHttpServletRequest actor(UUID owner) {
        var r=new MockHttpServletRequest(); r.setRemoteAddr(owner.toString());
        r.setAttribute("guitarMentorActor",new CurrentActor(owner,null,"LEARNER")); return r;
    }
    private Fixture upload(byte[] bytes,String format) {
        UUID owner=UUID.randomUUID();
        jdbc.sql("INSERT INTO accounts(id,role,status) VALUES(:id,'LEARNER','ACTIVE')").param("id",owner).update();
        var uploaded=originals.upload(actor(owner),UUID.randomUUID().toString(),
            Map.of("title","Synthetic 170-E","type","ETUDE"),
            List.of(new MockMultipartFile("files","synthetic."+format.toLowerCase(),"application/octet-stream",bytes)));
        assertEquals(201,uploaded.getStatusCode().value());
        var row=jdbc.sql("""
            SELECT m.id material,a.id asset,a.sha256 FROM library_materials m JOIN library_assets a ON a.material_id=m.id
            WHERE m.owner_account_id=:owner
            """).param("owner",owner).query().singleRow();
        return new Fixture(owner,(UUID)row.get("material"),(UUID)row.get("asset"),(String)row.get("sha256"),bytes);
    }
    private UUID create(Fixture f) {
        return jobs.create(actor(f.owner()),f.material(),UUID.randomUUID().toString(),"\"1\"",
            new LibraryConversionJobController.CreateRequest(f.asset())).getBody().id();
    }
    private ai.guitarmentor.application.library.LibraryConversionJobService.JobView get(Fixture f,UUID job) {
        return jobs.get(actor(f.owner()),f.material(),job).getBody();
    }
    private LibraryConversionWorker worker(LibraryConversionProcessRunner runner) {
        return new LibraryConversionWorker(leases,runner,outputs,store,jdbc,true,true,true);
    }
    private LibraryConversionProcessRunner.WorkerResult failure(String code) {
        return new LibraryConversionProcessRunner.WorkerResult("ERROR",code,List.of(),null,null,null,0,0,0,null,null,null);
    }
    private LibraryConversionProcessRunner.WorkerResult output(Fixture f) throws Exception {
        Path work=Files.createDirectory(temp.resolve("job-"+UUID.randomUUID()));
        byte[] pdf=("%PDF-1.7\n"+"x".repeat(1500)+"%%EOF").getBytes();
        var wav=ByteBuffer.allocate(44+88200).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes()).putInt(88236).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1)
            .putShort((short)1).putInt(44100).putInt(88200).putShort((short)2).putShort((short)16)
            .put("data".getBytes()).putInt(88200);
        Path p=Files.write(work.resolve("score.pdf"),pdf),a=Files.write(work.resolve("preview.wav"),wav.array());
        return new LibraryConversionProcessRunner.WorkerResult("RENDERED",null,List.of(),0,"Lead",f.sha(),pdf.length,wav.capacity(),1,work,p,a);
    }
    private void assertNoSlot(UUID job) {
        assertEquals(0,jdbc.sql("SELECT count(*) FROM library_conversion_worker_slots WHERE job_id=:job").param("job",job).query(Integer.class).single());
    }
    @Test void successIsAtomicPrivateAndDuplicateDeliveryIsNoOp() throws Exception {
        Fixture f=upload("FICHIER synthetic original".getBytes(),"GP5"); UUID job=create(f);
        var runner=mock(LibraryConversionProcessRunner.class); var result=output(f);
        when(runner.execute(any(),eq("GP5"),eq(f.sha()),isNull(),any())).thenReturn(result);
        var worker=worker(runner);
        try {
            assertTrue(worker.runNext()); assertFalse(worker.runNext());
            var done=get(f,job); assertEquals("SUCCEEDED",done.state()); assertNotNull(done.pdfAssetId()); assertNotNull(done.audioAssetId());
            assertEquals(1,done.attemptCount()); assertNoSlot(job);
            assertEquals(1,jdbc.sql("SELECT count(*) FROM library_conversion_output_lineage WHERE job_id=:job").param("job",job).query(Integer.class).single());
            assertEquals("PRIVATE",materials.get(actor(f.owner()),f.material()).getBody().visibility());
            assertThrows(RuntimeException.class,()->jobs.get(actor(UUID.randomUUID()),f.material(),job));
            verify(runner).deleteWorkspace(result.workspace());
        } finally {worker.close();}
    }
    @Test void failureCanRetryAndDoesNotConsumeQuota() throws Exception {
        Fixture f=upload("synthetic original".getBytes(),"GP5"); UUID job=create(f);
        var runner=mock(LibraryConversionProcessRunner.class);
        when(runner.execute(any(),any(),any(),any(),any())).thenReturn(failure("WORKER_UNAVAILABLE"),failure("TIMED_OUT"));
        var worker=worker(runner);
        try {
            worker.runNext(); var first=get(f,job); assertEquals("FAILED",first.state()); assertTrue(first.retryable()); assertNoSlot(job);
            jobs.retry(actor(f.owner()),f.material(),job,"\""+first.revision()+"\"");
            worker.runNext(); var second=get(f,job); assertEquals("TIMED_OUT",second.state()); assertFalse(second.retryable()); assertNoSlot(job);
            assertEquals(0,jdbc.sql("SELECT reserved_files FROM library_account_usage WHERE account_id=:id").param("id",f.owner()).query(Integer.class).single());
        } finally {worker.close();}
    }
    @Test void cancellationDuringExecutionWinsAndReleasesSlot() throws Exception {
        Fixture f=upload("synthetic original".getBytes(),"GP5"); UUID job=create(f);
        var runner=mock(LibraryConversionProcessRunner.class);
        when(runner.execute(any(),any(),any(),any(),any())).thenAnswer(invocation -> {
            var active=get(f,job); jobs.cancel(actor(f.owner()),f.material(),job,"\""+active.revision()+"\"");
            assertTrue(((java.util.function.BooleanSupplier)invocation.getArgument(4)).getAsBoolean());
            return failure("WORKER_FAILED");
        });
        var worker=worker(runner);
        try {worker.runNext(); assertEquals("CANCELLED",get(f,job).state()); assertNoSlot(job);}
        finally {worker.close();}
    }
    @Test void lostLeaseCannotPublishOutputAndKeepsReclaimedJobQueued() throws Exception {
        Fixture f=upload("synthetic original".getBytes(),"GP5"); UUID job=create(f);
        var runner=mock(LibraryConversionProcessRunner.class); var rendered=output(f);
        when(runner.execute(any(),any(),any(),any(),any())).thenAnswer(invocation -> {
            jdbc.sql("UPDATE library_conversion_worker_slots SET lease_until=now()-interval '1 second' WHERE job_id=:id")
                .param("id",job).update();
            leases.reclaimExpired(2);
            return rendered;
        });
        var worker=worker(runner);
        try {
            worker.runNext(); assertEquals("QUEUED",get(f,job).state()); assertNoSlot(job);
            assertEquals(1,jdbc.sql("SELECT count(*) FROM library_assets WHERE material_id=:id")
                .param("id",f.material()).query(Integer.class).single());
            verify(runner).deleteWorkspace(rendered.workspace());
        } finally {worker.close();}
    }
    @Test void quotaFailureLeavesOriginalAndNoPartialPair() throws Exception {
        Fixture f=upload("synthetic original".getBytes(),"GP5"); UUID job=create(f);
        jdbc.sql("UPDATE library_account_usage SET charged_bytes=209715200 WHERE account_id=:id")
            .param("id",f.owner()).update();
        var runner=mock(LibraryConversionProcessRunner.class); var rendered=output(f);
        when(runner.execute(any(),any(),any(),any(),any())).thenReturn(rendered);
        var worker=worker(runner);
        try {
            worker.runNext(); assertEquals("OUTPUT_QUOTA_EXCEEDED",get(f,job).errorCode()); assertNoSlot(job);
            assertEquals(1,jdbc.sql("SELECT count(*) FROM library_assets WHERE material_id=:id")
                .param("id",f.material()).query(Integer.class).single());
            assertEquals(0,jdbc.sql("SELECT reserved_files FROM library_account_usage WHERE account_id=:id")
                .param("id",f.owner()).query(Integer.class).single());
            verify(runner).deleteWorkspace(rendered.workspace());
        } finally {worker.close();}
    }
    @Test void featureFlagOffDoesNotClaimJobs() throws Exception {
        Fixture f=upload("synthetic original".getBytes(),"GP5"); UUID job=create(f);
        var runner=mock(LibraryConversionProcessRunner.class);
        var worker=new LibraryConversionWorker(leases,runner,outputs,store,jdbc,true,false,true);
        try {assertFalse(worker.runNext()); assertEquals("QUEUED",get(f,job).state()); verifyNoInteractions(runner);}
        finally {worker.close();}
    }

    @Test void realUploadQueueContainerSelectionPersistenceAndDownloadForEveryFormat() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("GM_TEST_REAL_DOCKER")));
        Path corpus=Path.of(System.getenv("GM_TEST_CONVERSION_CORPUS"));
        var runner=new LibraryConversionProcessRunner("docker","gm-conversion-worker:170e",temp.resolve("real work with spaces").toString());
        var worker=worker(runner);
        try {
            for(String format:List.of("GP3","GP4","GP5")) {
                Fixture f=upload(Files.readAllBytes(corpus.resolve("valid."+format.toLowerCase())),format); UUID job=create(f);
                assertTrue(worker.runNext()); var selection=get(f,job);
                assertEquals("AWAITING_TRACK_SELECTION",selection.state()); assertEquals(0,selection.attemptCount()); assertNoSlot(job);
                jobs.selectTrack(actor(f.owner()),f.material(),job,"\""+selection.revision()+"\"",new LibraryConversionJobController.TrackSelectionRequest(0));
                worker.runNext(); var done=get(f,job); assertEquals("SUCCEEDED",done.state(),done.errorCode()); assertEquals(1,done.attemptCount());
                assertNoSlot(job); assertFalse(worker.runNext());
                var material=materials.get(actor(f.owner()),f.material()).getBody();
                assertEquals(3,material.files().size()); assertEquals("PRIVATE",material.visibility());
                String key=jdbc.sql("SELECT object_key FROM library_assets WHERE id=:id").param("id",f.asset()).query(String.class).single();
                assertArrayEquals(f.bytes(),Files.readAllBytes(store.file(key)));
                var usage=jdbc.sql("SELECT charged_files,reserved_files,reserved_bytes FROM library_account_usage WHERE account_id=:owner").param("owner",f.owner()).query().singleRow();
                assertEquals(3,((Number)usage.get("charged_files")).intValue()); assertEquals(0,((Number)usage.get("reserved_files")).intValue()); assertEquals(0,((Number)usage.get("reserved_bytes")).longValue());
                for(var file:material.files()) {
                    assertNotNull(file.downloadUrl());
                    assertNotNull(originals.content(actor(f.owner()),f.material(),file.id()));
                }
                assertThrows(RuntimeException.class,()->jobs.get(actor(UUID.randomUUID()),f.material(),job));
            }
            try(var dirs=Files.list(temp.resolve("real work with spaces"))) {assertEquals(0,dirs.count());}
        } finally {worker.close();}
    }
}
