package ai.guitarmentor.interfaces.rest.library;

import static org.junit.jupiter.api.Assertions.*;
import ai.guitarmentor.application.library.LibraryConversionJobService;
import ai.guitarmentor.infrastructure.persistence.LibraryConversionDeletionGuard;
import ai.guitarmentor.infrastructure.persistence.LibraryConversionJobLeaseService;
import ai.guitarmentor.infrastructure.security.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.Transactional;

/** Real PostgreSQL checks for idempotency, owner scoping, state transitions and durable leases. */
@SpringBootTest(properties={
    "guitar-mentor.security.enabled=false",
    "guitar-mentor.library.enabled=true",
    "guitar-mentor.library.conversion-enabled=true",
    "guitar-mentor.library.cursor-secret=ci-only-conversion-secret-long-enough-32-bytes",
    "guitar-mentor.library.conversion-reclaim-delay-ms=3600000",
    "guitar-mentor.library.reconcile-delay-ms=3600000",
    "guitar-mentor.library.deletion-delay-ms=3600000",
    "guitar-mentor.library.asset-deletion-delay-ms=3600000"
})
@Transactional
@EnabledIfEnvironmentVariable(named="GUITAR_MENTOR_TEST_POSTGRES",matches="true")
class LibraryConversionJobPostgresTest {
    private static final String TRACKS_JSON = """
        [{"trackIndex":0,"name":"Lead Guitar","guitar":true,"instrument":"Guitar"},{"trackIndex":1,"name":"Rhythm Guitar","guitar":true,"instrument":"Guitar"}]
        """;

    @Autowired JdbcClient jdbc;
    @Autowired LibraryConversionJobController controller;
    @Autowired LibraryConversionJobLeaseService leases;
    @Autowired LibraryConversionDeletionGuard deletionGuard;

    private record Fixture(UUID owner, UUID material, UUID asset) {}

    private Fixture fixture(String format) {
        UUID owner=UUID.randomUUID(), material=UUID.randomUUID(), asset=UUID.randomUUID();
        jdbc.sql("INSERT INTO accounts(id,role,status) VALUES(:id,'LEARNER','ACTIVE')")
            .param("id",owner).update();
        jdbc.sql("INSERT INTO library_account_usage(account_id) VALUES(:id)")
            .param("id",owner).update();
        jdbc.sql("""
            INSERT INTO library_materials(id,owner_account_id,title,material_type,state,revision)
            VALUES(:id,:owner,'Conversion fixture','ETUDE','READY',1)
            """).param("id",material).param("owner",owner).update();
        jdbc.sql("""
            INSERT INTO library_assets(id,material_id,object_key,original_name,format,byte_size,sha256)
            VALUES(:id,:material,:key,'synthetic-score.gp',:format,128,:sha)
            """).param("id",asset).param("material",material).param("key",UUID.randomUUID().toString())
            .param("format",format).param("sha","a".repeat(64)).update();
        return new Fixture(owner,material,asset);
    }

    private static HttpServletRequest request(UUID owner) {
        var request=new MockHttpServletRequest();
        request.setAttribute("guitarMentorActor",new CurrentActor(owner,null,"LEARNER"));
        return request;
    }

    private LibraryConversionJobService.JobView create(Fixture f, UUID key) {
        return controller.create(request(f.owner()),f.material(),key.toString(),"\"1\"",
            new LibraryConversionJobController.CreateRequest(f.asset())).getBody();
    }

    private MapRow row(UUID jobId) {
        var r=jdbc.sql("SELECT state,revision,attempt_count,retryable,cancel_requested FROM library_conversion_jobs WHERE id=:id")
            .param("id",jobId).query().singleRow();
        return new MapRow(r.get("state").toString(),
            ((Number)r.get("revision")).longValue(),
            ((Number)r.get("attempt_count")).intValue(),
            Boolean.TRUE.equals(r.get("retryable")),
            Boolean.TRUE.equals(r.get("cancel_requested")));
    }
    private record MapRow(String state,long revision,int attempts,boolean retryable,boolean cancelRequested) {}

    @Test void createIsOwnerScopedIdempotentAndRejectsKeyReuse() {
        Fixture f=fixture("GP5");
        UUID key=UUID.randomUUID();
        var first=create(f,key);
        assertEquals("QUEUED",first.state());
        assertEquals(1,first.revision());

        var replay=controller.create(request(f.owner()),f.material(),key.toString(),"\"1\"",
            new LibraryConversionJobController.CreateRequest(f.asset()));
        assertEquals(202,replay.getStatusCode().value());
        assertEquals(first.id(),replay.getBody().id());
        assertEquals("true",replay.getHeaders().getFirst("Idempotency-Replayed"));

        UUID otherAsset=UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO library_assets(id,material_id,object_key,original_name,format,byte_size,sha256)
            VALUES(:id,:material,:key,'other.gp','GP4',128,:sha)
            """).param("id",otherAsset).param("material",f.material())
            .param("key",UUID.randomUUID().toString()).param("sha","b".repeat(64)).update();
        var reused=assertThrows(LibraryConversionJobService.ConversionJobException.class,
            ()->controller.create(request(f.owner()),f.material(),key.toString(),"\"1\"",
                new LibraryConversionJobController.CreateRequest(otherAsset)));
        assertEquals(409,reused.status());
        assertEquals("IDEMPOTENCY_KEY_REUSED",reused.code());

        var foreign=assertThrows(LibraryConversionJobService.ConversionJobException.class,
            ()->controller.get(request(UUID.randomUUID()),f.material(),first.id()));
        assertEquals(404,foreign.status());
    }

    @Test void validatesFormatRevisionAndSingleActiveJobPerAccount() {
        Fixture unsupported=fixture("GPX");
        var format=assertThrows(LibraryConversionJobService.ConversionJobException.class,
            ()->create(unsupported,UUID.randomUUID()));
        assertEquals(415,format.status());
        assertEquals("CONVERSION_FORMAT_UNSUPPORTED",format.code());

        Fixture f=fixture("GP3");
        var stale=assertThrows(LibraryConversionJobService.ConversionJobException.class,
            ()->controller.create(request(f.owner()),f.material(),UUID.randomUUID().toString(),"\"2\"",
                new LibraryConversionJobController.CreateRequest(f.asset())));
        assertEquals(412,stale.status());

        create(f,UUID.randomUUID());
        UUID secondAsset=UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO library_assets(id,material_id,object_key,original_name,format,byte_size,sha256)
            VALUES(:id,:material,:key,'second.gp','GP4',128,:sha)
            """).param("id",secondAsset).param("material",f.material())
            .param("key",UUID.randomUUID().toString()).param("sha","c".repeat(64)).update();
        var active=assertThrows(LibraryConversionJobService.ConversionJobException.class,
            ()->controller.create(request(f.owner()),f.material(),UUID.randomUUID().toString(),"\"1\"",
                new LibraryConversionJobController.CreateRequest(secondAsset)));
        assertEquals(409,active.status());
        assertEquals("CONVERSION_JOB_ALREADY_ACTIVE",active.code());
    }

    @Test void trackSelectionCancelAndRetryRespectStateMachine() {
        Fixture selection=fixture("GP5");
        var selectedJob=create(selection,UUID.randomUUID());
        jdbc.sql("""
            UPDATE library_conversion_jobs SET state='AWAITING_TRACK_SELECTION',
                tracks=CAST(:tracks AS jsonb),revision=2
            WHERE id=:id
            """).param("tracks",TRACKS_JSON)
            .param("id",selectedJob.id()).update();
        var selected=controller.selectTrack(request(selection.owner()),selection.material(),
            selectedJob.id(),"\"2\"",new LibraryConversionJobController.TrackSelectionRequest(1));
        assertEquals("QUEUED",selected.getBody().state());
        assertEquals(1,selected.getBody().selectedTrackIndex());
        assertEquals(3,selected.getBody().revision());

        Fixture cancellation=fixture("GP4");
        var cancelJob=create(cancellation,UUID.randomUUID());
        var cancelled=controller.cancel(request(cancellation.owner()),cancellation.material(),
            cancelJob.id(),"\"1\"");
        assertEquals("CANCELLED",cancelled.getBody().state());
        assertEquals("CANCELLED_BY_USER",cancelled.getBody().errorCode());

        Fixture retry=fixture("GP3");
        var retryJob=create(retry,UUID.randomUUID());
        jdbc.sql("""
            UPDATE library_conversion_jobs SET state='FAILED',error_code='STORAGE_TEMPORARY_FAILURE',
                retryable=TRUE,attempt_count=1,finished_at=now(),revision=2
            WHERE id=:id
            """).param("id",retryJob.id()).update();
        var retried=controller.retry(request(retry.owner()),retry.material(),retryJob.id(),"\"2\"");
        assertEquals("QUEUED",retried.getBody().state());
        assertFalse(retried.getBody().retryable());
        assertNull(retried.getBody().errorCode());
        assertEquals(3,retried.getBody().revision());
    }

    @Test void databaseSlotsCapGlobalConcurrencyAndRecoverExpiredLeases() {
        Fixture one=fixture("GP3"),two=fixture("GP4"),three=fixture("GP5");
        var job1=create(one,UUID.randomUUID());
        var job2=create(two,UUID.randomUUID());
        var job3=create(three,UUID.randomUUID());
        jdbc.sql("UPDATE library_conversion_jobs SET created_at=now()-interval '3 seconds' WHERE id=:id")
            .param("id",job1.id()).update();
        jdbc.sql("UPDATE library_conversion_jobs SET created_at=now()-interval '2 seconds' WHERE id=:id")
            .param("id",job2.id()).update();
        jdbc.sql("UPDATE library_conversion_jobs SET created_at=now()-interval '1 second' WHERE id=:id")
            .param("id",job3.id()).update();

        UUID worker1=UUID.randomUUID(),worker2=UUID.randomUUID(),worker3=UUID.randomUUID();
        var lease1=leases.claimNext(worker1).orElseThrow();
        var lease2=leases.claimNext(worker2).orElseThrow();
        assertTrue(leases.claimNext(worker3).isEmpty());
        assertEquals(job1.id(),lease1.jobId());
        assertEquals(job2.id(),lease2.jobId());
        assertTrue(leases.heartbeat(lease1.jobId(),worker1));
        assertTrue(leases.updateProgress(lease1.jobId(),worker1,"IMPORTING_SCORE",20));
        assertTrue(leases.awaitTrackSelection(lease1.jobId(),worker1,TRACKS_JSON));
        assertEquals("AWAITING_TRACK_SELECTION",row(lease1.jobId()).state());

        jdbc.sql("""
            UPDATE library_conversion_worker_slots SET lease_until=now()-interval '1 second'
            WHERE job_id=:job
            """).param("job",lease2.jobId()).update();
        assertEquals(1,leases.reclaimExpired(10));
        assertEquals("QUEUED",row(lease2.jobId()).state());
        var secondAttempt=leases.claimNext(worker3).orElseThrow();
        assertEquals(2,secondAttempt.attemptCount());
        jdbc.sql("""
            UPDATE library_conversion_worker_slots SET lease_until=now()-interval '1 second'
            WHERE job_id=:job
            """).param("job",secondAttempt.jobId()).update();
        assertEquals(1,leases.reclaimExpired(10));
        assertEquals("FAILED",row(secondAttempt.jobId()).state());
        assertEquals("WORKER_LEASE_EXPIRED",jdbc.sql("SELECT error_code FROM library_conversion_jobs WHERE id=:id")
            .param("id",secondAttempt.jobId()).query(String.class).single());
        assertFalse(row(secondAttempt.jobId()).retryable());
    }

    @Test void deletionCancelsQueuedJobsAndWaitsForRunningLease() {
        Fixture queued=fixture("GP5");
        var queuedJob=create(queued,UUID.randomUUID());
        assertTrue(deletionGuard.cancelAndWaitForMaterial(queued.material()));
        assertEquals("CANCELLED",row(queuedJob.id()).state());

        Fixture running=fixture("GP4");
        var runningJob=create(running,UUID.randomUUID());
        UUID worker=UUID.randomUUID();
        var lease=leases.claimNext(worker).orElseThrow();
        assertEquals(runningJob.id(),lease.jobId());
        assertFalse(deletionGuard.cancelAndWaitForAsset(running.asset()));
        assertEquals("CANCEL_REQUESTED",row(runningJob.id()).state());
        assertTrue(leases.isCancelRequested(runningJob.id(),worker));
        assertTrue(leases.finish(runningJob.id(),worker,"FAILED","SYNTHETIC_FAILURE",false));
        assertEquals("CANCELLED",row(runningJob.id()).state());
        assertTrue(deletionGuard.cancelAndWaitForAsset(running.asset()));
    }
}
