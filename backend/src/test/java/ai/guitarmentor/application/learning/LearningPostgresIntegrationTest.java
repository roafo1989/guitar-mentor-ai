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
    @Autowired ai.guitarmentor.application.attempt.MediaCleanupService mediaCleanup;
    @Autowired org.springframework.web.context.WebApplicationContext webContext;
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
        assertEquals("MANUAL_SELECTION",first.result().nextStepAvailability());
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


    @Test void prematurePassAndLateFailureNeverGrantCompletion() {
        UUID attempt=upload();
        code("ASSESSMENT_NOT_READY",()->writer.record(learner,session,task,attempt,
            assessment("MEETS"))); // UPLOADED: no trustworthy finished technical result.
        jdbc.update("UPDATE submission_attempts SET status='FAILED',"
            +" processing_finalized_at=CURRENT_TIMESTAMP WHERE id=?",attempt);
        code("ASSESSMENT_NOT_READY",()->writer.record(learner,session,task,attempt,
            assessment("MEETS"))); // Failed source can never yield PASS.
        assertTrue(service.assessments(learner,session,task,attempt,20,null).items().isEmpty());
        // Simulate an incorrectly persisted PASS by pre-fix code (V15) and a later FAILED.
        markProcessed(attempt);
        var historicPass=writer.record(learner,session,task,attempt,assessment("MEETS"));
        jdbc.update("UPDATE submission_attempts SET status='FAILED' WHERE id=?",attempt);
        var result=service.decide(learner,session,task,
            command(attempt,"COMPLETE",0,historicPass.id(),UUID.randomUUID()));
        assertEquals("COMPLETED_BY_LEARNER",result.result().taskState());
        assertEquals("LEARNER_CHOICE",service.decisions(learner,session,task,20,null)
            .items().getFirst().reasonCode());
    }

    @Test void forgedReviewPrincipalAndProofAreDeniedAndNeverSaved() {
        UUID attempt=upload();
        var permitted=assessment("NEEDS_WORK");
        code("INVALID_ASSESSMENT",()->writer.record(learner,session,task,attempt,
            new Input(permitted.sourceOperationId(),"HUMAN_REVIEW","INTERNAL_REVIEW","v1",
                "QA_TEST_REVIEWER","wrong-private-proof-0000000000",permitted.criteria())));
        code("INVALID_ASSESSMENT",()->writer.record(learner,session,task,attempt,
            new Input(permitted.sourceOperationId(),"HUMAN_REVIEW","INTERNAL_REVIEW","v1",
                "IMPOSTOR","qa-only-secret-not-for-production-18",permitted.criteria())));
        assertEquals(0,service.assessments(learner,session,task,attempt,20,null).items().size());
        var saved=writer.record(learner,session,task,attempt,permitted);
        assertEquals("QA_TEST_REVIEWER",saved.sourcePrincipal());
        assertEquals("QA_TEST_REVIEWER",jdbc.queryForObject(
            "SELECT source_principal FROM attempt_assessments WHERE id=?",
            String.class,saved.id()));
    }

    @Test void evidencePagesReachAllRowsWithoutTruncationOrCrossResourceCursors() {
        UUID attempt=upload();
        for(int n=0;n<36;n++) writer.record(learner,session,task,attempt,assessment("NEEDS_WORK"));
        int total=0;String cursor=null;
        var seen=new java.util.HashSet<String>();
        do {
            var page=service.evidence(learner,session,task,19,cursor);
            assertTrue(page.items().size()<=19);
            for(var entry:page.items()) {
                assertTrue(seen.add(entry.assessmentId()+"/"+entry.criterionCode()));
                assertNull(entry.confirmedSkillLevel());
            }
            total+=page.items().size();
            cursor=page.nextCursor();
        } while(cursor!=null);
        assertEquals(108,total); // 36 revisions × 3 assessment criteria
        assertEquals(108,seen.size());
        code("INVALID_PAGINATION",()->service.evidence(learner,session,task,10,"not-a-cursor"));
        code("INVALID_PAGINATION",()->service.evidence(learner,session,task,101,null));
    }

    @Test void mediaTtlRemovesFileButNotGradingAndDecisionHistory() throws Exception {
        UUID attempt=upload();
        markProcessed(attempt);
        var review=writer.record(learner,session,task,attempt,assessment("NEEDS_WORK"));
        service.decide(learner,session,task,
            command(attempt,"COMPLETE",0,review.id(),UUID.randomUUID()));
        var earlier=Timestamp.from(Instant.now().minusSeconds(49*3600));
        jdbc.update("UPDATE submission_attempts SET processing_finalized_at=?,"
            +" processing_finished_at=? WHERE id=?",earlier,earlier,attempt);
        mediaCleanup.runCycle(Instant.now(),
            new ai.guitarmentor.application.attempt.MediaRetentionPolicy.Policy(
                java.time.Duration.ofHours(48),java.time.Duration.ofHours(48),
                java.time.Duration.ofHours(1)));
        assertEquals("DELETED",jdbc.queryForObject("SELECT media_state"
            +" FROM submission_attempts WHERE id=?",String.class,attempt));
        assertEquals(review.id(),service.assessments(learner,session,task,attempt,20,null)
            .items().getFirst().id());
        assertEquals(1,service.decisions(learner,session,task,20,null).items().size());
        assertEquals(3,service.evidence(learner,session,task,20,null).items().size());
    }

    @Test void httpStatusesAndDisabledLearningAreSafe() throws Exception {
        String base="/api/v1/learners/"+learner+"/diagnostic-sessions/"+session
            +"/tasks/"+task;
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders
            .webAppContextSetup(webContext).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .get(base+"/learning")).andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .get(base+"/decisions?limit=0")).andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .get(base+"/learning".replace("/learning","/decisions")+"?cursor=garbage")).andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .get("/api/v1/learners/"+UUID.randomUUID()+"/diagnostic-sessions/"
                +session+"/tasks/"+task+"/learning")).andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        var disabled=new LearningService(learningRepo,sessionsRepository,false);
        var off=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
            new ai.guitarmentor.interfaces.rest.learning.LearningController(disabled))
            .setControllerAdvice(new ai.guitarmentor.interfaces.rest.learning.LearningController
                .LearningExceptionHandler()).build();
        off.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .get(base+"/learning")).andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isServiceUnavailable());
    }


    @Test void legacyUnverifiedPassCannotGrantVerifiedCompletion() {
        UUID attempt=upload();
        markProcessed(attempt);
        var pass=writer.record(learner,session,task,attempt,assessment("MEETS"));
        // A V15 PASS had only caller-asserted trust; V16 backfills this marker.
        jdbc.update("UPDATE attempt_assessments SET source_principal='LEGACY_UNVERIFIED'"
            +" WHERE id=?",pass.id());
        var history=service.assessments(learner,session,task,attempt,20,null)
            .items().getFirst();
        assertEquals("PASS",history.recommendation()); // preserve immutable old verdict
        assertEquals("LEGACY_UNVERIFIED",history.sourcePrincipal());
        assertTrue(history.limitations().stream().anyMatch(v->v.contains("unverified")));
        var decision=service.decide(learner,session,task,
            command(attempt,"COMPLETE",0,pass.id(),UUID.randomUUID()));
        assertEquals("COMPLETED_BY_LEARNER",decision.result().taskState());
        assertEquals("LEARNER_CHOICE",service.decisions(learner,session,task,20,null)
            .items().getFirst().reasonCode());
        assertEquals(pass.id(),decision.result().assessmentId());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM learner_skill_signal_history"
            +" WHERE instrument_profile_id=?",Integer.class,profile));
    }

    @Test void humanReviewCannotGradeMeasurementOnlySnapshot() {
        UUID attempt=upload();
        jdbc.update("UPDATE task_criterion_snapshots"
            +" SET assessment_policy='VALIDATED_MEASUREMENT'"
            +" WHERE task_id=? AND criterion_code='PULSE_STEADY'",task);
        code("INVALID_ASSESSMENT",()->writer.record(learner,session,task,attempt,
            assessment("MEETS")));
        code("INVALID_ASSESSMENT",()->writer.record(learner,session,task,attempt,
            assessment("NEEDS_WORK")));
        assertTrue(service.assessments(learner,session,task,attempt,20,null).items().isEmpty());
        var humanMissing=writer.record(learner,session,task,attempt,
            new Input(UUID.randomUUID(),"HUMAN_REVIEW","INTERNAL_REVIEW","v1",
                "QA_TEST_REVIEWER","qa-only-secret-not-for-production-18",List.of(
                new CriterionInput("PULSE_STEADY","NOT_ASSESSED",null,
                    "METHOD_NOT_APPLICABLE",null),
                new CriterionInput("BASS_STEADY","ASSESSED","MEETS",null,null),
                new CriterionInput("MELODY_CLEAR","ASSESSED","MEETS",null,null))));
        assertEquals("UNCERTAIN",humanMissing.recommendation());
        var decision=service.decide(learner,session,task,
            command(attempt,"COMPLETE",0,humanMissing.id(),UUID.randomUUID()));
        assertEquals("COMPLETED_BY_LEARNER",decision.result().taskState());
    }

    // QA coverage of AC18-02: technically PROCESSED is not pedagogical PASS.
    @Test void technicallyProcessedWithoutProviderRemainsNoAssessment() {
        UUID attempt=upload();
        markProcessed(attempt);
        var state=service.learning(learner,session,task);
        assertEquals("NO_ASSESSMENT",state.assessmentState());
        assertNull(state.latestAssessmentId());
        assertEquals("ACTIVE",state.taskState());
        assertFalse(uploads.list(learner,session,task).getFirst().assessmentAvailable());
        assertTrue(service.assessments(learner,session,task,attempt,20,null).items().isEmpty());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM learner_skill_signal_history"
            +" WHERE instrument_profile_id=?",Integer.class,profile));
    }

    // QA coverage of AC18-07: continue permits a distinct new attempt without losing history.
    @Test void continuingAllowsNextAttemptAndPreservesFirstReview() {
        UUID firstAttempt=upload();
        var firstReview=writer.record(learner,session,task,firstAttempt,assessment("NEEDS_WORK"));
        var continuation=service.decide(learner,session,task,
            command(firstAttempt,"CONTINUE_PRACTICING",0,firstReview.id(),UUID.randomUUID()));
        assertEquals("ACTIVE",continuation.result().taskState());
        assertEquals(1,continuation.result().version());

        UUID secondAttempt=upload();
        assertNotEquals(firstAttempt,secondAttempt);
        var attempts=uploads.list(learner,session,task);
        assertEquals(2,attempts.size());
        assertEquals(secondAttempt,attempts.getFirst().id());
        assertEquals(firstAttempt,attempts.getLast().id());
        assertEquals("NO_ASSESSMENT",service.learning(learner,session,task).assessmentState());
        assertEquals(secondAttempt,service.learning(learner,session,task).latestAttemptId());
        assertEquals(firstReview.id(),service.assessments(learner,session,task,firstAttempt,20,null)
            .items().getFirst().id());
        assertTrue(service.assessments(learner,session,task,secondAttempt,20,null).items().isEmpty());
        assertEquals(1,service.decisions(learner,session,task,20,null).items().size());
    }

    // QA coverage of the actual HTTP POST contract, not just service methods.
    @Test void httpDecisionReturns201Then200ReplayAnd409ForConflict() throws Exception {
        UUID attempt=upload(),requestId=UUID.randomUUID();
        String base="/api/v1/learners/"+learner+"/diagnostic-sessions/"+session
            +"/tasks/"+task;
        String payload="{\"requestId\":\""+requestId+"\",\"attemptId\":\""+attempt
            +"\",\"action\":\"COMPLETE\",\"expectedVersion\":0,"
            +"\"baseAssessmentId\":null,\"reason\":null}";
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders
            .webAppContextSetup(webContext).build();
        var create=org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .post(base+"/decisions")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(payload);
        var first=mvc.perform(create)
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isCreated()).andReturn();
        assertTrue(first.getResponse().getContentAsString()
            .contains("\"taskState\":\"COMPLETED_BY_LEARNER\""));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .post(base+"/decisions")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(payload))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        String changed=payload.replace("\"COMPLETE\"","\"CONTINUE_PRACTICING\"");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .post(base+"/decisions")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(changed))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isConflict());
        assertEquals(1,jdbc.queryForObject(
            "SELECT COUNT(*) FROM learner_decisions WHERE task_id=?",Integer.class,task));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .get(base+"/learning"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .jsonPath("$.taskState").value("COMPLETED_BY_LEARNER"));
    }
}
