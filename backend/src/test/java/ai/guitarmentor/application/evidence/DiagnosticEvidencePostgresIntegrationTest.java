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
