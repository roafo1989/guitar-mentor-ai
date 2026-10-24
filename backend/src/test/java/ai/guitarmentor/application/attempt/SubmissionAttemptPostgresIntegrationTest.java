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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.config.BeanPostProcessor;
import java.util.List;
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
@Import(SubmissionAttemptPostgresIntegrationTest.SqlProbeConfiguration.class)
class SubmissionAttemptPostgresIntegrationTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class SqlProbeConfiguration {
        @Bean
        static BeanPostProcessor attemptSqlProbe() {
            return new AttemptSqlProbe();
        }
    }

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
    @Autowired MediaCleanupTransactions cleanupTransactions;
    @Autowired MediaCleanupService cleanupService;
    @Autowired MediaUseCoordinator coordinator;

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
                learnerId, "D30 CI Student", java.sql.Timestamp.from(now));
        jdbc.update("INSERT INTO instrument_profiles(id,learner_id,instrument_code,technique_code)"
                        + " VALUES(?,?,?,?)",
                profileId, learnerId, "ACOUSTIC_GUITAR", "FINGERSTYLE");
        jdbc.update("INSERT INTO diagnostic_sessions"
                        + "(id,instrument_profile_id,status,created_at,updated_at,version)"
                        + " VALUES(?,?,?,?,?,?)",
                sessionId, profileId, "TASK_ASSIGNED", java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now), 0L);
        jdbc.update("INSERT INTO diagnostic_tasks(id,diagnostic_session_id,exercise_id,assigned_at)"
                        + " VALUES(?,?,?,?)",
                taskId, sessionId, EXERCISE_ID, java.sql.Timestamp.from(now));
    }

    @AfterEach
    void cleanFixture() throws IOException {
        if (taskId != null) {
            jdbc.update("DELETE FROM attempt_assessments WHERE task_id=?", taskId);
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
    void realPostgresCleanupPreservesHistoryAndIdempotency() throws IOException {
        UUID requestId=UUID.randomUUID();
        var view=service.submit(learnerId,sessionId,taskId,requestId,"keep history",wav());
        var key=jdbc.queryForObject("SELECT media_key FROM submission_attempts WHERE id=?",
            String.class,view.id());
        Instant previous=Instant.now().minusSeconds(49*3600);
        jdbc.update("""
            UPDATE submission_attempts SET status='PROCESSED',
              processing_finished_at=?,processing_finalized_at=?
            WHERE id=?
            """,java.sql.Timestamp.from(previous),java.sql.Timestamp.from(previous),view.id());
        var now=Instant.now();
        var policy=new MediaRetentionPolicy.Policy(java.time.Duration.ofHours(48),
            java.time.Duration.ofHours(48),java.time.Duration.ofHours(1));
        cleanupService.runCycle(now,policy);
        assertFalse(Files.exists(MEDIA_ROOT.resolve(key)));
        assertEquals("DELETED",jdbc.queryForObject(
            "SELECT media_state FROM submission_attempts WHERE id=?",String.class,view.id()));
        assertEquals(1,countRows());
        var retry=service.submit(learnerId,sessionId,taskId,requestId,"repeat",wav());
        assertEquals(view.id(),retry.id());
        assertEquals("DELETED",retry.mediaState());
        assertEquals(1,countRows());
        assertFalse(Files.exists(MEDIA_ROOT.resolve(key)));
    }

    @Test
    void concurrentUncommittedUploadCannotBeClaimedByCleanup() throws Exception {
        var stored=new java.util.concurrent.atomic.AtomicReference<String>();
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var transaction=worker.submit(()->{
                new TransactionTemplate(txManager).executeWithoutResult(tx->{
                    var view=service.submit(learnerId,sessionId,taskId,
                        UUID.randomUUID(),"concurrent rollback",wav());
                    stored.set(jdbc.queryForObject(
                        "SELECT media_key FROM submission_attempts WHERE id=?",
                        String.class,view.id()));
                    entered.countDown();
                    try {
                        if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))
                            throw new IllegalStateException("test synchronization timeout");
                    } catch(InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                    tx.setRollbackOnly();
                });
                return true;
            });
            assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS));
            assertNotNull(stored.get());
            assertTrue(Files.exists(MEDIA_ROOT.resolve(stored.get())));
            assertFalse(coordinator.tryAcquire(),
                "Cleanup must not acquire guard before upload transaction completion");
            release.countDown();
            assertTrue(transaction.get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(Files.exists(MEDIA_ROOT.resolve(stored.get())));
            assertEquals(0,countRows());
            assertTrue(coordinator.tryAcquire());
            coordinator.release();
        } finally { release.countDown();worker.shutdownNow(); }
    }

    @Test
    void realDatabaseRejectionOfDeletedStateRemainsRetryable() throws IOException {
        var view=service.submit(learnerId,sessionId,taskId,UUID.randomUUID(),"db-failure",wav());
        String key=jdbc.queryForObject("SELECT media_key FROM submission_attempts WHERE id=?",
            String.class,view.id());
        jdbc.update("UPDATE submission_attempts SET media_state='DELETE_PENDING' WHERE id=?",view.id());
        jdbc.execute("ALTER TABLE submission_attempts ADD CONSTRAINT d21_reject_deleted_state "
            + "CHECK (media_state <> 'DELETED')");
        var policy=new MediaRetentionPolicy.Policy(java.time.Duration.ofHours(48),
            java.time.Duration.ofHours(48),java.time.Duration.ofHours(1));
        try {
            cleanupService.runCycle(Instant.now(),policy);
            assertFalse(Files.exists(MEDIA_ROOT.resolve(key)));
            assertEquals("DELETE_PENDING",jdbc.queryForObject(
                "SELECT media_state FROM submission_attempts WHERE id=?",String.class,view.id()));
            assertEquals(null,jdbc.queryForObject(
                "SELECT media_deleted_at FROM submission_attempts WHERE id=?",java.sql.Timestamp.class,view.id()));
        } finally {
            jdbc.execute("ALTER TABLE submission_attempts DROP CONSTRAINT IF EXISTS d21_reject_deleted_state");
        }
        cleanupService.runCycle(Instant.now(),policy);
        assertEquals("DELETED",jdbc.queryForObject(
            "SELECT media_state FROM submission_attempts WHERE id=?",String.class,view.id()));
        assertNotNull(jdbc.queryForObject(
            "SELECT media_deleted_at FROM submission_attempts WHERE id=?",java.sql.Timestamp.class,view.id()));
    }

    @Test
    void unknownPlanAndLegacyTerminalWithoutFinalizationAreNeverDeleted() throws IOException {
        var legacy=service.submit(learnerId,sessionId,taskId,UUID.randomUUID(),"legacy",wav());
        var unknown=service.submit(learnerId,sessionId,taskId,UUID.randomUUID(),"unknown",wav());
        Instant old=Instant.now().minusSeconds(49*3600);
        jdbc.update("UPDATE submission_attempts SET status='PROCESSED',submitted_at=?,processing_finalized_at=NULL WHERE id=?",
            java.sql.Timestamp.from(old),legacy.id());
        jdbc.update("UPDATE submission_attempts SET processing_plan='FUTURE_MULTI_STAGE',submitted_at=? WHERE id=?",
            java.sql.Timestamp.from(old),unknown.id());
        cleanupService.runCycle(Instant.now(),new MediaRetentionPolicy.Policy(
            java.time.Duration.ofHours(48),java.time.Duration.ofHours(48),java.time.Duration.ofHours(1)));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM submission_attempts WHERE diagnostic_task_id=? AND media_state='AVAILABLE'",
            Integer.class,taskId));
        assertEquals(2,fileCount());
    }

    @Test
    void realPostgresBatchContinuesBeyondOneHundredRows() {
        Instant old=Instant.now().minusSeconds(49*3600);
        // All media keys deliberately point to absent files; ALREADY_ABSENT must be safe.
        jdbc.update("""
            INSERT INTO submission_attempts
             (id,diagnostic_task_id,request_id,attempt_number,submitted_at,status,
              media_key,media_format,size_bytes,processing_finished_at,processing_finalized_at)
            SELECT gen_random_uuid(), ?, gen_random_uuid(), n, ?, 'PROCESSED',
                   gen_random_uuid()::text || '.wav', 'wav', 12, ?, ?
            FROM generate_series(1, 125) AS n
            """,taskId,java.sql.Timestamp.from(old),java.sql.Timestamp.from(old),
            java.sql.Timestamp.from(old));
        var policy=new MediaRetentionPolicy.Policy(java.time.Duration.ofHours(48),
            java.time.Duration.ofHours(48),java.time.Duration.ofHours(1));
        for(int cycle=0;cycle<3;cycle++) cleanupService.runCycle(Instant.now(),policy);
        assertEquals(125,jdbc.queryForObject(
            "SELECT count(*) FROM submission_attempts WHERE diagnostic_task_id=? AND media_state='DELETED'",
            Integer.class,taskId));
        assertEquals(125,countRows());
    }

    @Test
    void beforeRetentionBoundaryFileStaysAndAtBoundaryItIsDeleted() throws IOException {
        var view=service.submit(learnerId,sessionId,taskId,UUID.randomUUID(),"boundary",wav());
        String key=jdbc.queryForObject("SELECT media_key FROM submission_attempts WHERE id=?",
            String.class,view.id());
        Instant now=Instant.now();
        Instant due=now.minusSeconds(48*3600);
        jdbc.update("""
            UPDATE submission_attempts SET status='PROCESSED',
              processing_finished_at=?,processing_finalized_at=? WHERE id=?
            """,java.sql.Timestamp.from(due),java.sql.Timestamp.from(due),view.id());
        var policy=new MediaRetentionPolicy.Policy(java.time.Duration.ofHours(48),
            java.time.Duration.ofHours(48),java.time.Duration.ofHours(1));
        cleanupService.runCycle(now.minusNanos(1_000_000),policy);
        assertTrue(Files.exists(MEDIA_ROOT.resolve(key)));
        cleanupService.runCycle(now,policy);
        assertFalse(Files.exists(MEDIA_ROOT.resolve(key)));
        assertEquals("DELETED",jdbc.queryForObject("SELECT media_state FROM submission_attempts WHERE id=?",
            String.class,view.id()));
    }

    @Test
    void restartAfterFileDeletionResumesPendingAndRetainsHistory() throws IOException {
        UUID requestId=UUID.randomUUID();
        var view=service.submit(learnerId,sessionId,taskId,requestId,"retain",wav());
        String key=jdbc.queryForObject("SELECT media_key FROM submission_attempts WHERE id=?",
            String.class,view.id());
        jdbc.update("UPDATE submission_attempts SET media_state='DELETE_PENDING' WHERE id=?",view.id());
        Files.delete(MEDIA_ROOT.resolve(key)); // crash window: physical delete, no DB confirmation
        cleanupService.runCycle(Instant.now(),new MediaRetentionPolicy.Policy(
            java.time.Duration.ofHours(48),java.time.Duration.ofHours(48),
            java.time.Duration.ofHours(1)));
        assertEquals("DELETED",jdbc.queryForObject(
            "SELECT media_state FROM submission_attempts WHERE id=?",String.class,view.id()));
        assertNotNull(jdbc.queryForObject("SELECT media_deleted_at FROM submission_attempts WHERE id=?",
            java.sql.Timestamp.class,view.id()));
        assertEquals("ALREADY_ABSENT",jdbc.queryForObject(
            "SELECT media_delete_outcome FROM submission_attempts WHERE id=?",
            String.class,view.id()));
        assertEquals("retain",service.list(learnerId,sessionId,taskId).get(0).comment());
    }

    @Test
    void realPostgresCleanupExpiresOldUnprocessedAttempt() throws IOException {
        var view=service.submit(learnerId,sessionId,taskId,UUID.randomUUID(),"expired",wav());
        Instant old=Instant.now().minusSeconds(49*3600);
        jdbc.update("UPDATE submission_attempts SET submitted_at=? WHERE id=?",
            java.sql.Timestamp.from(old),view.id());
        cleanupService.runCycle(Instant.now(),new MediaRetentionPolicy.Policy(
            java.time.Duration.ofHours(48),java.time.Duration.ofHours(48),
            java.time.Duration.ofHours(1)));
        assertEquals("FAILED",jdbc.queryForObject(
            "SELECT status FROM submission_attempts WHERE id=?",String.class,view.id()));
        assertEquals("RETENTION_EXPIRED",jdbc.queryForObject(
            "SELECT processing_error_code FROM submission_attempts WHERE id=?",String.class,view.id()));
        assertEquals("DELETED",jdbc.queryForObject(
            "SELECT media_state FROM submission_attempts WHERE id=?",String.class,view.id()));
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
    @Test
    void boundedSqlStatementsDoNotGrowWithPageSize() {
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO submission_attempts
              (id,diagnostic_task_id,request_id,attempt_number,submitted_at,status,
               media_key,media_format,size_bytes)
            SELECT gen_random_uuid(), ?, gen_random_uuid(), n, ?, 'UPLOADED',
                   gen_random_uuid()::text || '.wav', 'wav', 12
            FROM generate_series(1,51) AS n
            """, taskId, java.sql.Timestamp.from(now));

        // Warm the JPA/session infrastructure outside capture, then measure
        // actual JDBC prepareStatement calls in the synchronous service read.
        service.verifyOwnership(learnerId,sessionId,taskId);
        for (int limit : new int[]{1,20,50}) {
            var sql = AttemptSqlProbe.capture(() -> {
                var page = service.page(learnerId,sessionId,taskId,limit,null);
                assertEquals(limit,page.items().size());
                assertTrue(page.hasMore());
            });
            assertEquals(1, countStatements(sql,"from submission_attempts"),
                    "Exactly one bounded attempt SELECT at limit " + limit + ": " + sql);
            assertEquals(1, countStatements(sql,"from attempt_assessments"),
                    "Exactly one batch assessment SELECT at limit " + limit + ": " + sql);
            assertEquals(0, countStatements(sql,"count("),
                    "Page must not execute COUNT at limit " + limit + ": " + sql);
            assertTrue(sql.stream().anyMatch(statement -> statement.contains("limit")
                            || statement.contains("fetch first")),
                    "Attempts SELECT must contain a SQL row bound: " + sql);
        }
        var empty = AttemptSqlProbe.capture(() ->
                assertTrue(service.page(learnerId,sessionId,taskId,20,1).items().isEmpty()));
        assertEquals(1,countStatements(empty,"from submission_attempts"));
        assertEquals(0,countStatements(empty,"from attempt_assessments"));
        var capabilities = AttemptSqlProbe.capture(() ->
                service.verifyOwnership(learnerId,sessionId,taskId));
        assertEquals(0,countStatements(capabilities,"from submission_attempts"));
        assertEquals(0,countStatements(capabilities,"from attempt_assessments"));
    }

    private static long countStatements(List<String> statements,String needle) {
        return statements.stream().filter(sql -> sql.contains(needle)).count();
    }

    @Test
    void historyPagesStayStableWhenNewerAttemptArrives() {
        Instant created = Instant.now();
        jdbc.update("""
            INSERT INTO submission_attempts
              (id,diagnostic_task_id,request_id,attempt_number,submitted_at,status,
               media_key,media_format,size_bytes)
            SELECT gen_random_uuid(), ?, gen_random_uuid(), n, ?, 'UPLOADED',
                   gen_random_uuid()::text || '.wav', 'wav', 12
            FROM generate_series(1,45) AS n
            """, taskId, java.sql.Timestamp.from(created));
        UUID assessedAttempt = jdbc.queryForObject(
                "SELECT id FROM submission_attempts WHERE diagnostic_task_id=? AND attempt_number=45",
                UUID.class, taskId);
        for (int revision = 1; revision <= 2; revision++) {
            jdbc.update("""
                INSERT INTO attempt_assessments
                  (id,task_id,attempt_id,revision,source_operation_id,input_fingerprint,
                   method_id,method_version,source_kind,recommendation,created_at)
                VALUES (?,?,?,?,? ,?, 'manual','1','HUMAN_REVIEW','REVISE',?)
                """,UUID.randomUUID(),taskId,assessedAttempt,revision,UUID.randomUUID(),
                "f".repeat(64),java.sql.Timestamp.from(created));
        }
        var first = service.page(learnerId, sessionId, taskId, 20, null);
        assertTrue(first.items().get(0).assessmentAvailable(),
                "Any assessment revision makes the page flag true");
        assertFalse(first.items().get(1).assessmentAvailable());
        assertEquals(20, first.items().size());
        assertEquals(45, first.items().get(0).number());
        assertEquals(26, first.nextBeforeNumber());
        assertTrue(first.hasMore());
        jdbc.update("""
            INSERT INTO submission_attempts
              (id,diagnostic_task_id,request_id,attempt_number,submitted_at,status,
               media_key,media_format,size_bytes)
            VALUES (gen_random_uuid(),?,gen_random_uuid(),46,?,'UPLOADED',
                    gen_random_uuid()::text || '.wav','wav',12)
            """,taskId,java.sql.Timestamp.from(created));
        var older = service.page(learnerId,sessionId,taskId,20,first.nextBeforeNumber());
        assertEquals(25,older.items().get(0).number());
        assertEquals(6,older.nextBeforeNumber());
        assertEquals(20,older.items().size());
        var last = service.page(learnerId,sessionId,taskId,20,older.nextBeforeNumber());
        assertEquals(5,last.items().size());
        assertEquals(5,last.items().get(0).number());
        assertFalse(last.hasMore());
        assertEquals(null,last.nextBeforeNumber());
        assertEquals(46,service.page(learnerId,sessionId,taskId,20,null).items().get(0).number());
        assertEquals(46,service.list(learnerId,sessionId,taskId).size());
        assertEquals(first.items().get(0).id(),
                service.detail(learnerId,sessionId,taskId,first.items().get(0).id()).id());
        assertThrows(jakarta.persistence.EntityNotFoundException.class,
                () -> service.detail(learnerId,sessionId,taskId,UUID.randomUUID()));
        assertThrows(jakarta.persistence.EntityNotFoundException.class,
                () -> service.page(UUID.randomUUID(),sessionId,taskId,20,null));
        var all = service.page(learnerId,sessionId,taskId,50,null);
        assertEquals(46,all.items().size());
        assertFalse(all.hasMore());
        assertEquals(0,service.page(learnerId,sessionId,taskId,20,1).items().size());
    }

    @Test
    void emptyPageAndLimitBoundaries() {
        assertTrue(service.page(learnerId,sessionId,taskId,20,null).items().isEmpty());
        assertFalse(service.page(learnerId,sessionId,taskId,20,null).hasMore());
        assertThrows(IllegalArgumentException.class,
                () -> service.page(learnerId,sessionId,taskId,0,null));
        assertThrows(IllegalArgumentException.class,
                () -> service.page(learnerId,sessionId,taskId,51,null));
    }

}
