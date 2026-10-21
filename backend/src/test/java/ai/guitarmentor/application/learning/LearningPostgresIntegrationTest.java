package ai.guitarmentor.application.learning;

import ai.guitarmentor.application.attempt.SubmissionAttemptService;
import ai.guitarmentor.application.learning.LearningAssessmentWriter.CriterionInput;
import ai.guitarmentor.application.learning.LearningAssessmentWriter.Input;
import ai.guitarmentor.application.learning.LearningDtos.DecisionRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

/** #18 integration tests against Flyway V15, real PostgreSQL locks and REST service logic. */
@SpringBootTest(properties={
    "guitarmentor.learning.enabled=true",
    "guitarmentor.learning.review-principal=QA_TEST_REVIEWER",
    "guitarmentor.learning.review-token=qa-only-secret-not-for-production-18",
    "guitar-mentor.submissions.enabled=true",
    "guitar-mentor.submissions.poll-delay-ms=999999",
    "guitar-mentor.submissions.storage-path=./target/r4-learning-media"
})
@EnabledIfEnvironmentVariable(named="GUITAR_MENTOR_TEST_POSTGRES",matches="true")
class LearningPostgresIntegrationTest {
    static final UUID EXERCISE=UUID.fromString("58430000-0000-4000-8000-000000000801");
    @Autowired JdbcTemplate jdbc;
    @Autowired LearningService service;
    @Autowired LearningAssessmentWriter writer;
    @Autowired ai.guitarmentor.infrastructure.persistence.LearningRepository learningRepo;
    @Autowired SubmissionAttemptService uploads;
    @Autowired ai.guitarmentor.infrastructure.persistence.DiagnosticSessionRepository sessionsRepository;

    UUID learner,profile,session,task;
    Path mediaRoot=Path.of("target","r4-learning-media").toAbsolutePath();

    @BeforeEach void init() throws Exception {
        Files.createDirectories(mediaRoot);
        learner=UUID.randomUUID();profile=UUID.randomUUID();
        session=UUID.randomUUID();task=UUID.randomUUID();
        var now=Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO learners(id,display_name,created_at) VALUES(?,?,?)",
            learner,"R4 learning test",now);
        jdbc.update("INSERT INTO instrument_profiles(id,learner_id,instrument_code,technique_code) "
            +"VALUES(?,?,'ACOUSTIC_GUITAR','FINGERSTYLE')",profile,learner);
        jdbc.update("INSERT INTO diagnostic_sessions"
            +"(id,instrument_profile_id,status,created_at,updated_at,version)"
            +" VALUES(?,?,'TASK_ASSIGNED',?,?,0)",session,profile,now,now);
        jdbc.update("INSERT INTO diagnostic_tasks(id,diagnostic_session_id,exercise_id,assigned_at)"
            +" VALUES(?,?,?,?)",task,session,EXERCISE,now);
        learningRepo.initialize(task);
    }

    @AfterEach void cleanup() throws Exception {
        if(task!=null) {
            jdbc.update("DELETE FROM learner_decisions WHERE task_id=?",task);
            jdbc.update("DELETE FROM assessment_criterion_results WHERE task_id=?",task);
            jdbc.update("DELETE FROM attempt_assessments WHERE task_id=?",task);
            jdbc.update("DELETE FROM submission_attempts WHERE diagnostic_task_id=?",task);
            jdbc.update("DELETE FROM task_criterion_snapshots WHERE task_id=?",task);
            jdbc.update("DELETE FROM task_learning_state WHERE task_id=?",task);
            jdbc.update("DELETE FROM diagnostic_tasks WHERE id=?",task);
            jdbc.update("DELETE FROM diagnostic_sessions WHERE id=?",session);
            jdbc.update("DELETE FROM instrument_profiles WHERE id=?",profile);
            jdbc.update("DELETE FROM learners WHERE id=?",learner);
        }
        if(Files.isDirectory(mediaRoot)) {
            try(var stream=Files.list(mediaRoot)) {
                for(Path p:stream.toList()) Files.deleteIfExists(p);
            }
        }
    }

    private UUID upload() {
        var wav=new MockMultipartFile("file","learning.wav","audio/wav",
            new byte[]{'R','I','F','F',4,0,0,0,'W','A','V','E',0,0,0,0});
        return uploads.submit(learner,session,task,UUID.randomUUID(),null,wav).id();
    }

    private void markProcessed(UUID attempt) {
        var now=Timestamp.from(Instant.now());
        jdbc.update("UPDATE submission_attempts SET status='PROCESSED',"
          +" processing_finalized_at=?,processing_finished_at=? WHERE id=?",
          now,now,attempt);
    }

    private DecisionRequest command(UUID attempt,String action,long version,UUID assessment,UUID id) {
        return new DecisionRequest(id,attempt,action,version,assessment,null);
    }

    private Input assessment(String firstOutcome) {
        return new Input(UUID.randomUUID(),"HUMAN_REVIEW","INTERNAL_REVIEW","v1",
            "QA_TEST_REVIEWER","qa-only-secret-not-for-production-18",List.of(
            new CriterionInput("PULSE_STEADY","ASSESSED",firstOutcome,null,"observed pulse"),
            new CriterionInput("BASS_STEADY","ASSESSED","MEETS",null,null),
            new CriterionInput("MELODY_CLEAR","ASSESSED","MEETS",null,null)));
    }

    private static void code(String expected,Runnable action) {
        var problem=assertThrows(LearningProblem.class,action::run);
        assertEquals(expected,problem.code());
    }

    @Test void emptyAndUnassessedAreTruthfulAndAttemptRequired() {
        var before=service.learning(learner,session,task);
        assertEquals("NO_ATTEMPT",before.assessmentState());
        assertEquals("ACTIVE",before.taskState());
        code("NO_ATTEMPT",()->service.decide(learner,session,task,
            command(UUID.randomUUID(),"COMPLETE",0,null,UUID.randomUUID())));
        UUID attempt=upload();
        var view=service.learning(learner,session,task);
        assertEquals("NO_ASSESSMENT",view.assessmentState());
        assertNull(view.latestAssessmentId());
        assertFalse(uploads.list(learner,session,task).getFirst().assessmentAvailable());
        assertTrue(service.assessments(learner,session,task,attempt,20,null).items().isEmpty());
        assertEquals(3,learningRepo.criteria(task).size());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM learner_skill_signal_history "
            +"WHERE instrument_profile_id=?",Integer.class,profile));
    }

    @Test void completionWithoutScoreHasReplayAndTerminalUploadGuard() {
        UUID attempt=upload(), requestId=UUID.randomUUID();
        var cmd=command(attempt,"COMPLETE",0,null,requestId);
        var first=service.decide(learner,session,task,cmd);
        assertFalse(first.replay());
        assertEquals("COMPLETED_BY_LEARNER",first.result().taskState());
        assertEquals("NOT_IMPLEMENTED",first.result().nextStepAvailability());
        assertEquals(1,first.result().version());
        var replay=service.decide(learner,session,task,cmd);
        assertTrue(replay.replay());
        assertEquals(first.result(),replay.result());
        code("IDEMPOTENCY_CONFLICT",()->service.decide(learner,session,task,
            command(attempt,"CONTINUE_PRACTICING",0,null,requestId)));
        code("TASK_COMPLETED",()->service.decide(learner,session,task,
            command(attempt,"COMPLETE",1,null,UUID.randomUUID())));
        var e=assertThrows(ResponseStatusException.class,()->upload());
        assertEquals(409,e.getStatusCode().value());
        assertEquals(1,uploads.list(learner,session,task).size());
    }

    @Test void revisionsPreserveReasonsAndLatePassCannotRewriteStudentChoice() {
        UUID attempt=upload();
        var revise=writer.record(learner,session,task,attempt,assessment("NEEDS_WORK"));
        assertEquals("REVISE",revise.recommendation());
        assertEquals(1,revise.revision());
        assertTrue(uploads.list(learner,session,task).getFirst().assessmentAvailable());
        var choice=service.decide(learner,session,task,new DecisionRequest(
            UUID.randomUUID(),attempt,"COMPLETE",0L,revise.id(),"I accept the imperfections"));
        assertEquals("COMPLETED_BY_LEARNER",choice.result().taskState());
        markProcessed(attempt);
        var pass=writer.record(learner,session,task,attempt,assessment("MEETS"));
        assertEquals("PASS",pass.recommendation());
        assertEquals(2,pass.revision());
        var versions=service.assessments(learner,session,task,attempt,1,null);
        assertEquals(pass.id(),versions.items().getFirst().id());
        assertNotNull(versions.nextCursor());
        assertEquals(revise.id(),service.assessments(learner,session,task,
            attempt,1,versions.nextCursor()).items().getFirst().id());
        assertEquals("COMPLETED_BY_LEARNER",service.learning(learner,session,task).taskState());
        assertEquals(revise.id(),service.decisions(learner,session,task,20,null)
            .items().getFirst().assessmentId());
        assertEquals(3,service.evidence(learner,session,task,20,null).items().stream()
            .filter(e->e.assessmentId().equals(pass.id())).count());
        assertTrue(service.evidence(learner,session,task,20,null).items().stream()
            .allMatch(e->e.confirmedSkillLevel()==null));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM learner_skill_signal_history "
            +"WHERE instrument_profile_id=?",Integer.class,profile));
    }

    @Test void passedOnlyByValidatedFullCriteria() {
        UUID attempt=upload();
        markProcessed(attempt);
        var pass=writer.record(learner,session,task,attempt,assessment("MEETS"));
        var result=service.decide(learner,session,task,
            command(attempt,"COMPLETE",0,pass.id(),UUID.randomUUID()));
        assertEquals("COMPLETED_PASSED",result.result().taskState());
        assertEquals("ASSESSMENT_PASSED",service.decisions(learner,session,task,20,null)
            .items().getFirst().reasonCode());
    }

    @Test void staleStateAssessmentAndForeignObjectsFailWithoutWrites() {
        UUID attempt=upload();
        var first=service.decide(learner,session,task,
            command(attempt,"CONTINUE_PRACTICING",0,null,UUID.randomUUID()));
        assertEquals("ACTIVE",first.result().taskState());
        code("VERSION_CONFLICT",()->service.decide(learner,session,task,
            command(attempt,"COMPLETE",0,null,UUID.randomUUID())));
        var revise=writer.record(learner,session,task,attempt,assessment("NEEDS_WORK"));
        code("ASSESSMENT_CHANGED",()->service.decide(learner,session,task,
            command(attempt,"COMPLETE",1,null,UUID.randomUUID())));
        code("RESOURCE_NOT_FOUND",()->service.decide(learner,session,task,
            command(UUID.randomUUID(),"COMPLETE",1,revise.id(),UUID.randomUUID())));
        code("RESOURCE_NOT_FOUND",()->service.learning(UUID.randomUUID(),session,task));
        code("RESOURCE_NOT_FOUND",()->service.assessments(learner,session,task,
            UUID.randomUUID(),20,null));
        code("INVALID_PAGINATION",()->service.decisions(learner,session,task,101,null));
        code("INVALID_PAGINATION",()->service.assessments(learner,session,task,
            attempt,20,"invalid"));
        assertEquals(1,service.decisions(learner,session,task,20,null).items().size());
    }

    @Test void invalidSourceAndCriterionCannotIssuePass() {
        UUID attempt=upload();
        code("INVALID_ASSESSMENT",()->writer.record(learner,session,task,attempt,
            new Input(UUID.randomUUID(),"DEMO","fake","1",
                "QA_TEST_REVIEWER","qa-only-secret-not-for-production-18",List.of())));
        code("INVALID_ASSESSMENT",()->writer.record(learner,session,task,attempt,
            new Input(UUID.randomUUID(),"HUMAN_REVIEW","INTERNAL_REVIEW","v1",
                "FAKE_REVIEWER","invalid",List.of())));
        code("INVALID_ASSESSMENT",()->writer.record(learner,session,task,attempt,
            new Input(UUID.randomUUID(),"VALIDATED_MEASUREMENT","unapproved","1",
                "QA_TEST_REVIEWER","qa-only-secret-not-for-production-18",
                List.of(new CriterionInput("PULSE_STEADY","ASSESSED","MEETS",null,null)))));
        assertTrue(service.assessments(learner,session,task,attempt,20,null).items().isEmpty());
        assertEquals("UNCERTAIN",writer.record(learner,session,task,attempt,
            new Input(UUID.randomUUID(),"HUMAN_REVIEW","INTERNAL_REVIEW","v1",
                "QA_TEST_REVIEWER","qa-only-secret-not-for-production-18",
                List.of(new CriterionInput("PULSE_STEADY","ASSESSED","MEETS",null,null))))
            .recommendation());
    }

    @Test void assessmentOperationIsIdempotentAndDoesNotOverwriteOldRevision() {
        UUID attempt=upload();
        var input=assessment("NEEDS_WORK");
        var first=writer.record(learner,session,task,attempt,input);
        var second=writer.record(learner,session,task,attempt,input);
        assertEquals(first.id(),second.id());
        assertEquals(1,service.assessments(learner,session,task,attempt,20,null).items().size());
        code("IDEMPOTENCY_CONFLICT",()->writer.record(learner,session,task,attempt,
            new Input(input.sourceOperationId(),input.sourceKind(),input.methodId(),
                input.methodVersion(),input.sourcePrincipal(),input.sourceProof(),assessment("MEETS").criteria())));
    }

    @Test void twoConcurrentTransitionsOfOneVersionOnlyOneSucceeds() throws Exception {
        UUID attempt=upload();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var latch=new CountDownLatch(1);
            var a=pool.submit(()->{latch.await();return service.decide(learner,session,task,
                command(attempt,"CONTINUE_PRACTICING",0,null,UUID.randomUUID()));});
            var b=pool.submit(()->{latch.await();try {
                return service.decide(learner,session,task,
                    command(attempt,"CONTINUE_PRACTICING",0,null,UUID.randomUUID()));
            } catch(LearningProblem problem) {
                assertEquals("VERSION_CONFLICT",problem.code());return null;
            }});
            latch.countDown();
            int successes=0,conflicts=0;
            try {if(a.get(15,TimeUnit.SECONDS)!=null) successes++;}
            catch(java.util.concurrent.ExecutionException ex) {
                if(ex.getCause() instanceof LearningProblem p
                    && "VERSION_CONFLICT".equals(p.code())) conflicts++;
                else throw ex;
            }
            if(b.get(15,TimeUnit.SECONDS)!=null) successes++; else conflicts++;
            assertEquals(1,successes);
            assertEquals(1,conflicts);
        }
        assertEquals(1,service.learning(learner,session,task).version());
    }

    @Test void terminalUploadReplayIsStillAvailableAndNewRequestRejected() {
        var wav=new MockMultipartFile("file","replay.wav","audio/wav",
            new byte[]{'R','I','F','F',4,0,0,0,'W','A','V','E',0,0,0,0});
        UUID uploadId=UUID.randomUUID();
        var original=uploads.submit(learner,session,task,uploadId,null,wav);
        service.decide(learner,session,task,
            command(original.id(),"COMPLETE",0,null,UUID.randomUUID()));
        var replay=uploads.submit(learner,session,task,uploadId,null,wav);
        assertEquals(original.id(),replay.id());
        assertEquals(1,uploads.list(learner,session,task).size());
        assertEquals(409,assertThrows(ResponseStatusException.class,
            ()->uploads.submit(learner,session,task,UUID.randomUUID(),null,wav))
            .getStatusCode().value());
    }

    @Test void failingStateUpdateRollsBackDecisionInsertion() {
        UUID attempt=upload();
        jdbc.execute("ALTER TABLE task_learning_state "
            +"ADD CONSTRAINT r4_test_forbid_completion CHECK (state='ACTIVE')");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class,
                ()->service.decide(learner,session,task,
                    command(attempt,"COMPLETE",0,null,UUID.randomUUID())));
            assertEquals(0,jdbc.queryForObject(
                "SELECT COUNT(*) FROM learner_decisions WHERE task_id=?",Integer.class,task));
            assertEquals("ACTIVE",service.learning(learner,session,task).taskState());
            assertEquals(0,service.learning(learner,session,task).version());
        } finally {
            jdbc.execute("ALTER TABLE task_learning_state "
                +"DROP CONSTRAINT IF EXISTS r4_test_forbid_completion");
        }
    }

    @Test void disabledLearningRoutesDoNotDisableOldUploads() {
        var disabled=new LearningService(learningRepo,
            // A disabled instance never uses session locking, but still has valid collaborators.
            sessionsRepository,false);
        code("LEARNING_DISABLED",()->disabled.learning(learner,session,task));
        assertNotNull(upload());
        assertEquals("ACTIVE",service.learning(learner,session,task).taskState());
    }

}
