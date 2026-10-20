package ai.guitarmentor.application.evidence;

import static org.junit.jupiter.api.Assertions.*;

import ai.guitarmentor.application.attempt.MediaCleanupService;
import ai.guitarmentor.application.attempt.MediaRetentionPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

/** Real PostgreSQL, transaction/file compensation and shared #21 TTL regression. */
@SpringBootTest(properties={"guitar-mentor.submissions.enabled=true",
        "guitar-mentor.submissions.poll-delay-ms=999999"})
@EnabledIfEnvironmentVariable(named="GUITAR_MENTOR_TEST_POSTGRES",matches="true")
class DiagnosticEvidencePostgresIntegrationTest {
    private static final Path AUDIO_ROOT=Path.of("target","d68-audio").toAbsolutePath();
    private static final Path SCORE_ROOT=Path.of("target","d68-score").toAbsolutePath();
    @DynamicPropertySource
    static void overrideMediaRoot(DynamicPropertyRegistry r) {
        r.add("guitar-mentor.submissions.storage-path",AUDIO_ROOT::toString);
        r.add("guitar-mentor.submissions.score-storage-path",SCORE_ROOT::toString);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired DiagnosticEvidenceService service;
    @Autowired ai.guitarmentor.infrastructure.persistence.DiagnosticEvidenceRepository evidenceRepository;
    @Autowired MediaCleanupService cleanup;
    private UUID learner, profile, session;

    @BeforeEach void setup() throws Exception {
        clear(AUDIO_ROOT); clear(SCORE_ROOT);
        learner=UUID.randomUUID();profile=UUID.randomUUID();session=UUID.randomUUID();
        Timestamp now=Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO learners(id,display_name,created_at) VALUES(?,?,?)",
                learner,"D68 CI Student",now);
        jdbc.update("INSERT INTO instrument_profiles(id,learner_id,instrument_code,technique_code) " +
                "VALUES(?,?,?,?)",profile,learner,"ACOUSTIC_GUITAR","FINGERSTYLE");
        jdbc.update("INSERT INTO diagnostic_sessions(id,instrument_profile_id,status," +
                "created_at,updated_at,version) VALUES(?,?,?,?,?,?)",
                session,profile,"COLLECTING_EVIDENCE",now,now,0);
    }
    @AfterEach void tearDown() throws Exception {
        jdbc.update("DELETE FROM diagnostic_evidence_media WHERE evidence_id IN " +
                "(SELECT id FROM diagnostic_evidence WHERE diagnostic_session_id=?)",session);
        jdbc.update("DELETE FROM diagnostic_evidence WHERE diagnostic_session_id=?",session);
        jdbc.update("DELETE FROM diagnostic_sessions WHERE id=?",session);
        jdbc.update("DELETE FROM instrument_profiles WHERE id=?",profile);
        jdbc.update("DELETE FROM learners WHERE id=?",learner);
        clear(AUDIO_ROOT);clear(SCORE_ROOT);
    }
    private static void clear(Path root) throws IOException {
        if(!Files.exists(root))return;
        try(Stream<Path> s=Files.list(root)) {
            for(Path file:s.toList()) Files.deleteIfExists(file);
        }
    }
    private static MockMultipartFile audio() {
        return new MockMultipartFile("audio","mine.wav","audio/wav",
                new byte[]{'R','I','F','F',4,0,0,0,'W','A','V','E',0,0,0,0});
    }
    private static MockMultipartFile pdf() {
        return new MockMultipartFile("score","tabs.pdf","application/pdf",
                "%PDF-1.7\nCI sample".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static long count(Path root) throws IOException {
        if(!Files.isDirectory(root))return 0;
        try(Stream<Path> s=Files.list(root)){return s.count();}
    }

    /** API-level acceptance tests dispatch through the real controller and persistence. */
    private org.springframework.test.web.servlet.MockMvc evidenceHttp() {
        return org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new ai.guitarmentor.interfaces.rest.evidence.DiagnosticEvidenceController(service))
                .build();
    }
    private String evidencePath() {
        return "/api/v1/learners/" + learner + "/diagnostic-sessions/" + session + "/evidence";
    }

    @Test void httpUploadListGetAndIdempotentReplayHaveCorrectContracts() throws Exception {
        var http=evidenceHttp();
        UUID key=UUID.randomUUID();
        var original=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart(evidencePath())
                .file(audio()).file(pdf())
                .param("requestId",key.toString()).param("title","Yesterday")
                .param("composer","Beatles"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.title").value("Yesterday"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.audio.format").value("wav"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.score.format").value("pdf"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.skillEvidence").value("NOT_ASSESSED"))
                .andReturn();
        assertTrue(original.getResponse().getHeader("Location").endsWith("/evidence/" +
                service.list(learner,session).getFirst().id()));

        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart(evidencePath()).file(audio()).file(pdf())
                .param("requestId",key.toString()).param("title","Yesterday")
                .param("composer","Beatles"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        var id=service.list(learner,session).getFirst().id();
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get(evidencePath()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].id").value(id.toString()));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get(evidencePath()+"/"+id))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.id").value(id.toString()));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get(evidencePath()+"/capabilities"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.enabled").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.maxItems").value(10));
        assertEquals(1,service.list(learner,session).size());
        assertEquals(1,count(AUDIO_ROOT));
        assertEquals(1,count(SCORE_ROOT));
    }

    @Test void httpRejectsChangedPayloadForSameKeyWithoutDestroyingExisting() throws Exception {
        var http=evidenceHttp();
        UUID key=UUID.randomUUID();
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart(evidencePath()).file(audio())
                .param("requestId",key.toString()).param("title","Yesterday"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        var changed=new MockMultipartFile("audio","different.wav","audio/wav",
                new byte[]{'R','I','F','F',4,0,0,0,'W','A','V','E',8,7,6,5});
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart(evidencePath()).file(changed)
                .param("requestId",key.toString()).param("title","Yesterday"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("IDEMPOTENCY_PAYLOAD_MISMATCH"));
        assertEquals(1,service.list(learner,session).size());
        assertEquals(1,count(AUDIO_ROOT));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart(evidencePath()).file(changed)
                .param("requestId",UUID.randomUUID().toString()).param("title","Yesterday"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        assertEquals(2,service.list(learner,session).size());
    }

    @Test void httpRejectsForgedScoreAndDoesNotLeaveAudioOrHistory() throws Exception {
        var http=evidenceHttp();
        var forged=new MockMultipartFile("score","fake.pdf","application/pdf",
                "not a pdf".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart(evidencePath()).file(audio()).file(forged)
                .param("requestId",UUID.randomUUID().toString()).param("title","Yesterday"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("INVALID_SCORE_CONTENT"));
        assertTrue(service.list(learner,session).isEmpty());
        assertEquals(0,count(AUDIO_ROOT));
        assertEquals(0,count(SCORE_ROOT));
    }

    @Test void httpRejectsNewUploadAfterIntakeHasFinishedButAllowsIdenticalReplay() throws Exception {
        var http=evidenceHttp();
        UUID key=UUID.randomUUID();
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart(evidencePath()).file(audio())
                .param("requestId",key.toString()).param("title","Yesterday"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        jdbc.update("UPDATE diagnostic_sessions SET status='READY_FOR_TASK' WHERE id=?",session);
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart(evidencePath()).file(audio())
                .param("requestId",key.toString()).param("title","Yesterday"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart(evidencePath()).file(audio())
                .param("requestId",UUID.randomUUID().toString()).param("title","Another"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("INVALID_SESSION_STATE"));
        assertEquals(1,service.list(learner,session).size());
    }


    @Test void httpEleventhEvidenceIsRejectedAndIntakeCanStillFinish() throws Exception {
        var http=evidenceHttp();
        for(int i=0;i<10;i++) {
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .multipart(evidencePath()).file(audio())
                    .param("requestId",UUID.randomUUID().toString())
                    .param("title","Practice piece "+i))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        }
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart(evidencePath()).file(audio())
                .param("requestId",UUID.randomUUID().toString()).param("title","Over quota"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("MAX_ITEMS_REACHED"));
        assertEquals(10,service.list(learner,session).size());
        assertEquals(10,count(AUDIO_ROOT));
        jdbc.update("UPDATE diagnostic_sessions SET status='READY_FOR_TASK' WHERE id=?",session);
        assertEquals(10,service.list(learner,session).size());
    }

    @Test void sixtyFailedPendingDeletesCannotStarveFreshEligibleMedia() {
        Instant now=Instant.now();
        Instant old=now.minusSeconds(50*3600);
        java.util.Set<UUID> firstPendingIds=new java.util.HashSet<>();
        for(int i=0;i<60;i++) {
            UUID evidenceId=UUID.randomUUID(),mediaId=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO diagnostic_evidence(id,diagnostic_session_id,request_id,title,submitted_at)
                VALUES(?,?,?,?,?)
                """,evidenceId,session,UUID.randomUUID(),"old evidence "+i,Timestamp.from(old));
            jdbc.update("""
                INSERT INTO diagnostic_evidence_media(id,evidence_id,kind,storage_key,
                media_format,size_bytes,status,finalized_at,media_state,delete_attempts)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """,mediaId,evidenceId,"SCORE",UUID.randomUUID()+".pdf","pdf",9L,
                    "PROCESSED",Timestamp.from(old),"DELETE_PENDING",0);
        }
        UUID newEvidence=UUID.randomUUID(),freshMedia=UUID.randomUUID();
        Instant freshAge=now.minusSeconds(49*3600);
        jdbc.update("""
            INSERT INTO diagnostic_evidence(id,diagnostic_session_id,request_id,title,submitted_at)
            VALUES(?,?,?,?,?)
            """,newEvidence,session,UUID.randomUUID(),"newly expired",Timestamp.from(freshAge));
        jdbc.update("""
            INSERT INTO diagnostic_evidence_media(id,evidence_id,kind,storage_key,
            media_format,size_bytes,status,finalized_at,media_state,delete_attempts)
            VALUES(?,?,?,?,?,?,?,?,?,?)
            """,freshMedia,newEvidence,"AUDIO",UUID.randomUUID()+".wav","wav",16L,
                "PROCESSED",Timestamp.from(freshAge),"AVAILABLE",0);

        Instant boundary=now.minusSeconds(48*3600);
        var first=evidenceRepository.claimExpired(boundary,boundary);
        assertEquals(26,first.size()); // 25 failed retries + 1 newly expired file
        assertTrue(first.stream().anyMatch(x->x.id().equals(freshMedia)));
        assertEquals("DELETE_PENDING",jdbc.queryForObject(
                "SELECT media_state FROM diagnostic_evidence_media WHERE id=?",
                String.class,freshMedia));
        for(var item:first) {
            if(!item.id().equals(freshMedia)) {
                firstPendingIds.add(item.id());
                evidenceRepository.markFailed(item.id());
            }
        }
        var next=evidenceRepository.claimExpired(boundary,boundary);
        // Lowest failure count first: previously untried pending rows are processed next.
        assertTrue(next.stream().anyMatch(x->!x.id().equals(freshMedia)
                && !firstPendingIds.contains(x.id())));
    }

    @Test void twoAssetsSurviveRefreshAndRetireTogetherWithHistory() throws Exception {
        UUID request=UUID.randomUUID();
        var first=service.submit(learner,session,request,"Yesterday","Beatles",
                null,audio(),pdf());
        assertTrue(first.created());
        assertEquals(1,count(AUDIO_ROOT));
        assertEquals(1,count(SCORE_ROOT));
        var again=service.submit(learner,session,request,"Yesterday","Beatles",
                null,audio(),pdf());
        assertFalse(again.created());
        assertEquals(first.evidence().id(),again.evidence().id());
        assertEquals(1,service.list(learner,session).size());
        jdbc.update("UPDATE diagnostic_sessions SET status='READY_FOR_TASK' WHERE id=?",session);
        assertFalse(service.submit(learner,session,request,"Yesterday","Beatles",
                null,audio(),pdf()).created());
        assertEquals("INVALID_SESSION_STATE",assertThrows(ResponseStatusException.class,
                ()->service.submit(learner,session,UUID.randomUUID(),"New",null,
                        null,audio(),null)).getReason());
        Instant previous=Instant.now().minusSeconds(49*3600);
        jdbc.update("""
            UPDATE diagnostic_evidence_media SET status='PROCESSED',finalized_at=?
            WHERE evidence_id=?
            """,Timestamp.from(previous),first.evidence().id());
        var policy=new MediaRetentionPolicy.Policy(java.time.Duration.ofHours(48),
                java.time.Duration.ofHours(48),java.time.Duration.ofMinutes(60));
        cleanup.runCycle(Instant.now(),policy);
        assertEquals(0,count(AUDIO_ROOT));
        assertEquals(0,count(SCORE_ROOT));
        var retained=service.list(learner,session).getFirst();
        assertEquals("DELETED",retained.audio().mediaState());
        assertEquals("DELETED",retained.score().mediaState());
        assertEquals("NOT_ASSESSED",retained.skillEvidence());
    }
    @Test void committedAudioAWithLostResponseCannotAcknowledgeChangedAudioB() throws Exception {
        UUID key=UUID.randomUUID();
        var a=service.submit(learner,session,key,"Yesterday","Beatles",null,audio(),null);
        assertTrue(a.created());
        var b=new MockMultipartFile("audio","mine.wav","audio/wav",
                new byte[]{'R','I','F','F',8,0,0,0,'W','A','V','E',1,2,3,4});
        var mismatch=assertThrows(ResponseStatusException.class,
                ()->service.submit(learner,session,key,"Yesterday","Beatles",null,b,null));
        assertEquals(org.springframework.http.HttpStatus.CONFLICT,mismatch.getStatusCode());
        assertEquals("IDEMPOTENCY_PAYLOAD_MISMATCH",mismatch.getReason());
        assertEquals(1,service.list(learner,session).size());
        assertEquals(1,count(AUDIO_ROOT));
        var distinct=service.submit(learner,session,UUID.randomUUID(),"Yesterday",
                "Beatles",null,b,null);
        assertTrue(distinct.created());
        assertNotEquals(a.evidence().id(),distinct.evidence().id());
        assertEquals(2,service.list(learner,session).size());
        assertEquals(2,count(AUDIO_ROOT));
    }

    @Test void changedScoreOrTitleCannotReusePreviouslyCommittedRequest() throws Exception {
        UUID key=UUID.randomUUID();
        service.submit(learner,session,key,"Yesterday","Beatles",null,audio(),pdf());
        var altered=new MockMultipartFile("score","tabs.pdf","application/pdf",
                "%PDF-1.7\nsecond score".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("IDEMPOTENCY_PAYLOAD_MISMATCH",assertThrows(ResponseStatusException.class,
                ()->service.submit(learner,session,key,"Yesterday","Beatles",null,audio(),altered))
                .getReason());
        assertEquals("IDEMPOTENCY_PAYLOAD_MISMATCH",assertThrows(ResponseStatusException.class,
                ()->service.submit(learner,session,key,"Changed title","Beatles",null,audio(),pdf()))
                .getReason());
        assertEquals(1,service.list(learner,session).size());
    }

    @Test void invalidSecondFileCompensatesPreviouslyUploadedAudio() throws Exception {
        var forged=new MockMultipartFile("score","fake.pdf","application/pdf",
                "not really a pdf".getBytes());
        assertThrows(ResponseStatusException.class,()->service.submit(learner,session,
                UUID.randomUUID(),"Bad",null,null,audio(),forged));
        assertEquals(0,count(AUDIO_ROOT));
        assertEquals(0,count(SCORE_ROOT));
        assertTrue(service.list(learner,session).isEmpty());
    }
    @Test void cannotReadOtherSessionByGuessingUuid() {
        assertThrows(RuntimeException.class,()->service.list(UUID.randomUUID(),session));
    }
}
