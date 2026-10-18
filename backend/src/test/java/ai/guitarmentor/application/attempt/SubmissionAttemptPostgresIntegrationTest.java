package ai.guitarmentor.application.attempt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real PostgreSQL + Spring/JPA transaction-boundary tests.
 * Runs in CI with GUITAR_MENTOR_TEST_POSTGRES=true; locally only if explicitly enabled.
 * No mocked repository or mocked afterCompletion callbacks in this class.
 */
@SpringBootTest(properties = {
        "guitar-mentor.submissions.enabled=true",
        "guitar-mentor.submissions.poll-delay-ms=999999"
})
@EnabledIfEnvironmentVariable(named = "GUITAR_MENTOR_TEST_POSTGRES", matches = "true")
class SubmissionAttemptPostgresIntegrationTest {
    private static final Path MEDIA_ROOT = Path.of("target", "d30-postgres-integration-files")
            .toAbsolutePath().normalize();
    private static final UUID EXERCISE_ID =
            UUID.fromString("58430000-0000-4000-8000-000000000801");

    @DynamicPropertySource
    static void mediaProperties(DynamicPropertyRegistry registry) {
        registry.add("guitar-mentor.submissions.storage-path", MEDIA_ROOT::toString);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired SubmissionAttemptService service;

    private UUID learnerId;
    private UUID profileId;
    private UUID sessionId;
    private UUID taskId;

    private static MockMultipartFile wav() {
        byte[] content = {'R','I','F','F',4,0,0,0,'W','A','V','E', 0,0,0,0};
        return new MockMultipartFile("file", "integration.wav", "audio/wav", content);
    }

    @BeforeEach
    void createIsolatedRealDatabaseFixture() throws IOException {
        Files.createDirectories(MEDIA_ROOT);
        clearTestMedia();
        learnerId = UUID.randomUUID();
        profileId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        taskId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO learners(id,display_name,created_at) VALUES(?,?,?)",
                learnerId, "D30 CI Student", now);
        jdbc.update("INSERT INTO instrument_profiles(id,learner_id,instrument_code,technique_code)"
                        + " VALUES(?,?,?,?)",
                profileId, learnerId, "ACOUSTIC_GUITAR", "FINGERSTYLE");
        jdbc.update("INSERT INTO diagnostic_sessions"
                        + "(id,instrument_profile_id,status,created_at,updated_at,version)"
                        + " VALUES(?,?,?,?,?,?)",
                sessionId, profileId, "TASK_ASSIGNED", now, now, 0L);
        jdbc.update("INSERT INTO diagnostic_tasks(id,diagnostic_session_id,exercise_id,assigned_at)"
                        + " VALUES(?,?,?,?)",
                taskId, sessionId, EXERCISE_ID, now);
    }

    @AfterEach
    void cleanFixture() throws IOException {
        if (taskId != null) {
            jdbc.update("DELETE FROM submission_attempts WHERE diagnostic_task_id=?", taskId);
            jdbc.update("DELETE FROM diagnostic_tasks WHERE id=?", taskId);
            jdbc.update("DELETE FROM diagnostic_sessions WHERE id=?", sessionId);
            jdbc.update("DELETE FROM instrument_profiles WHERE id=?", profileId);
            jdbc.update("DELETE FROM learners WHERE id=?", learnerId);
        }
        clearTestMedia();
    }

    private static void clearTestMedia() throws IOException {
        if (!Files.isDirectory(MEDIA_ROOT)) return;
        try (Stream<Path> files = Files.list(MEDIA_ROOT)) {
            for (Path p : files.toList()) Files.deleteIfExists(p);
        }
    }

    private int countRows() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM submission_attempts WHERE diagnostic_task_id=?",
                Integer.class, taskId);
    }

    private long fileCount() throws IOException {
        try (Stream<Path> files = Files.list(MEDIA_ROOT)) {
            return files.count();
        }
    }

    @Test
    void confirmedLateRollbackDeletesFileEvenAfterSuccessfulSaveAndFlush() {
        AtomicReference<String> key = new AtomicReference<>();
        new TransactionTemplate(txManager).executeWithoutResult(transaction -> {
            var view = service.submit(learnerId, sessionId, taskId,
                    UUID.randomUUID(), "D30 late rollback", wav());
            assertNotNull(view.id());
            key.set(jdbc.queryForObject(
                    "SELECT media_key FROM submission_attempts WHERE id=?",
                    String.class, view.id()));
            assertTrue(Files.exists(MEDIA_ROOT.resolve(key.get())));
            transaction.setRollbackOnly(); // Late failure AFTER service returned.
        });
        assertNotNull(key.get());
        assertEquals(0, countRows());
        assertFalse(Files.exists(MEDIA_ROOT.resolve(key.get())));
    }

    @Test
    void actualPostgresFlushFailureRollsBackAndCompensatesWithoutFalseSuccess()
            throws IOException {
        // Real SQL constraint, not a Mockito repository throwing an exception.
        jdbc.execute("ALTER TABLE submission_attempts ADD CONSTRAINT d30_test_reject_flush "
                + "CHECK (student_comment <> 'D30_FORCE_FLUSH_FAIL')");
        try {
            long before = fileCount();
            assertThrows(RuntimeException.class,
                    () -> service.submit(learnerId, sessionId, taskId,
                            UUID.randomUUID(), "D30_FORCE_FLUSH_FAIL", wav()));
            assertEquals(0, countRows());
            assertEquals(before, fileCount());
        } finally {
            jdbc.execute("ALTER TABLE submission_attempts "
                    + "DROP CONSTRAINT IF EXISTS d30_test_reject_flush");
        }
    }

    @Test
    void committedUploadRetainsOnlyOneFileAndIdempotentRetryKeepsTheSameRow()
            throws IOException {
        UUID requestId = UUID.randomUUID();
        var original = service.submit(learnerId, sessionId, taskId,
                requestId, "D30 successful commit", wav());
        assertEquals(1, countRows());
        String key = jdbc.queryForObject(
                "SELECT media_key FROM submission_attempts WHERE id=?",
                String.class, original.id());
        assertTrue(Files.isRegularFile(MEDIA_ROOT.resolve(key)));
        assertEquals(1L, fileCount());

        var retry = service.submit(learnerId, sessionId, taskId,
                requestId, "D30 successful commit", wav());
        assertEquals(original.id(), retry.id());
        assertEquals(original.number(), retry.number());
        assertEquals(1, countRows());
        assertEquals(1L, fileCount());
        assertTrue(Files.isRegularFile(MEDIA_ROOT.resolve(key)));
    }
}
