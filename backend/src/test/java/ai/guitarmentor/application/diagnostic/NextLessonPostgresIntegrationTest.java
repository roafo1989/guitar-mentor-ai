package ai.guitarmentor.application.diagnostic;

import ai.guitarmentor.application.learning.LearningProblem;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL proof of durable next-lesson semantics. */
@SpringBootTest(properties={"guitarmentor.learning.enabled=true"})
@EnabledIfEnvironmentVariable(named="GUITAR_MENTOR_TEST_POSTGRES",matches="true")
class NextLessonPostgresIntegrationTest {
    private static final UUID EXERCISE=UUID.fromString("58430000-0000-4000-8000-000000000801");
    @Autowired JdbcTemplate jdbc;
    @Autowired NextLessonService service;
    UUID learner, profile, session, task;

    @BeforeEach void init() {
        learner=UUID.randomUUID(); profile=UUID.randomUUID();
        session=UUID.randomUUID(); task=UUID.randomUUID();
        Timestamp time=Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO learners(id,display_name,created_at) VALUES(?,?,?)",learner,"Next lesson QA",time);
        jdbc.update("INSERT INTO instrument_profiles(id,learner_id,instrument_code,technique_code) VALUES(?,?,'ACOUSTIC_GUITAR','FINGERSTYLE')",profile,learner);
        jdbc.update("INSERT INTO diagnostic_sessions(id,instrument_profile_id,status,created_at,updated_at,version) VALUES(?,?,'TASK_ASSIGNED',?,?,0)",session,profile,time,time);
        jdbc.update("INSERT INTO diagnostic_tasks(id,diagnostic_session_id,exercise_id,assigned_at) VALUES(?,?,?,?)",task,session,EXERCISE,time);
        jdbc.update("INSERT INTO task_learning_state(task_id,state,version,updated_at,completed_at) VALUES(?,'COMPLETED_BY_LEARNER',2,?,?)",task,time,time);
    }
    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM lesson_transitions WHERE learner_id=?",learner);
        jdbc.update("DELETE FROM task_criterion_snapshots WHERE task_id IN (SELECT t.id FROM diagnostic_tasks t JOIN diagnostic_sessions s ON s.id=t.diagnostic_session_id WHERE s.instrument_profile_id=?)",profile);
        jdbc.update("DELETE FROM task_learning_state WHERE task_id IN (SELECT t.id FROM diagnostic_tasks t JOIN diagnostic_sessions s ON s.id=t.diagnostic_session_id WHERE s.instrument_profile_id=?)",profile);
        jdbc.update("DELETE FROM diagnostic_tasks WHERE diagnostic_session_id IN (SELECT id FROM diagnostic_sessions WHERE instrument_profile_id=?)",profile);
        jdbc.update("DELETE FROM diagnostic_sessions WHERE instrument_profile_id=?",profile);
        jdbc.update("DELETE FROM instrument_profiles WHERE id=?",profile);
        jdbc.update("DELETE FROM learners WHERE id=?",learner);
    }
    NextLessonService.Request command(UUID key) {
        return new NextLessonService.Request(key,2L,"GM-EB01","0.8B");
    }
    static void code(String expected,Runnable action) {
        assertEquals(expected,assertThrows(LearningProblem.class,action::run).code());
    }
    @Test void archiveCreatesNewVersionPinnedTaskAndSurvivesReplay() {
        UUID key=UUID.randomUUID();
        var created=service.start(learner,session,task,command(key));
        assertFalse(created.replay());
        assertNotEquals(task,created.result().task().id());
        assertEquals("TASK_ASSIGNED",created.result().session().status().name());
        assertEquals("ARCHIVED",jdbc.queryForObject("SELECT status FROM diagnostic_sessions WHERE id=?",String.class,session));
        assertEquals("COMPLETED_BY_LEARNER",jdbc.queryForObject("SELECT state FROM task_learning_state WHERE task_id=?",String.class,task));
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT state FROM task_learning_state WHERE task_id=?",String.class,created.result().task().id()));
        var again=service.start(learner,session,task,command(key));
        assertTrue(again.replay());
        assertEquals(created.result().transitionId(),again.result().transitionId());
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM diagnostic_tasks t JOIN diagnostic_sessions s ON s.id=t.diagnostic_session_id WHERE s.instrument_profile_id=?",Integer.class,profile));
        var history=service.history(learner,"ACOUSTIC_GUITAR",1,null);
        assertEquals(1,history.items().size());
        assertNotNull(history.nextCursor());
        var older=service.history(learner,"ACOUSTIC_GUITAR",1,history.nextCursor());
        assertEquals(task,older.items().getFirst().taskId());
        assertFalse(older.items().getFirst().isCurrent());
    }
    @Test void conflictsDoNotArchiveOrCreateAnyTask() {
        code("LEARNING_VERSION_CONFLICT",()->service.start(learner,session,task,
            new NextLessonService.Request(UUID.randomUUID(),1L,"GM-EB01","0.8B")));
        code("EXERCISE_VERSION_CHANGED",()->service.start(learner,session,task,
            new NextLessonService.Request(UUID.randomUUID(),2L,"GM-EB01","old")));
        code("RESOURCE_NOT_FOUND",()->service.start(UUID.randomUUID(),session,task,command(UUID.randomUUID())));
        assertEquals("TASK_ASSIGNED",jdbc.queryForObject("SELECT status FROM diagnostic_sessions WHERE id=?",String.class,session));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM diagnostic_tasks WHERE diagnostic_session_id=?",Integer.class,session));
    }
    @Test void activeTaskCannotAdvance() {
        jdbc.update("UPDATE task_learning_state SET state='ACTIVE',version=0,completed_at=NULL WHERE task_id=?",task);
        code("SOURCE_TASK_NOT_COMPLETED",()->service.start(learner,session,task,
            new NextLessonService.Request(UUID.randomUUID(),0L,"GM-EB01","0.8B")));
    }
    @Test void concurrentTabsCreateExactlyOneSuccessor() throws Exception {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready=new java.util.concurrent.CountDownLatch(2);
        var go=new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<NextLessonService.Created> work=()->{
                ready.countDown();
                if (!go.await(10,java.util.concurrent.TimeUnit.SECONDS))
                    throw new IllegalStateException("barrier timeout");
                return service.start(learner,session,task,command(UUID.randomUUID()));
            };
            var first=pool.submit(work);
            var second=pool.submit(work);
            assertTrue(ready.await(10,java.util.concurrent.TimeUnit.SECONDS));
            go.countDown();
            var a=first.get(20,java.util.concurrent.TimeUnit.SECONDS);
            var b=second.get(20,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(a.result().transitionId(),b.result().transitionId());
            assertNotEquals(a.replay(),b.replay());
            assertEquals(1,jdbc.queryForObject(
                "SELECT count(*) FROM lesson_transitions WHERE source_task_id=?",Integer.class,task));
        } finally { go.countDown(); pool.shutdownNow(); }
    }

    @Test void databaseFailureRollsBackSourceArchiveAndTargetTask() {
        // Fail only THIS source's insert after archive/target flush.
        String id=task.toString();
        String function="next_lesson_injected_failure";
        jdbc.execute("CREATE OR REPLACE FUNCTION "+function+"() RETURNS trigger LANGUAGE plpgsql AS $inject$ BEGIN "
            +"IF NEW.source_task_id='"+id+"'::uuid THEN RAISE EXCEPTION 'test injection'; END IF; "
            +"RETURN NEW; END $inject$");
        jdbc.execute("CREATE TRIGGER next_lesson_injected_failure_trigger BEFORE INSERT ON lesson_transitions "
            +"FOR EACH ROW EXECUTE FUNCTION "+function+"()");
        try {
            assertThrows(RuntimeException.class,
                ()->service.start(learner,session,task,command(UUID.randomUUID())));
            assertEquals("TASK_ASSIGNED",jdbc.queryForObject(
                "SELECT status FROM diagnostic_sessions WHERE id=?",String.class,session));
            assertEquals(1,jdbc.queryForObject(
                "SELECT count(*) FROM diagnostic_tasks t JOIN diagnostic_sessions s ON s.id=t.diagnostic_session_id "
                +"WHERE s.instrument_profile_id=?",Integer.class,profile));
            assertEquals(0,jdbc.queryForObject(
                "SELECT count(*) FROM lesson_transitions WHERE source_task_id=?",Integer.class,task));
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS next_lesson_injected_failure_trigger ON lesson_transitions");
            jdbc.execute("DROP FUNCTION IF EXISTS "+function+"()");
        }
    }

    @Test void idempotencyKeyCannotBeReusedForDifferentPayload() {
        UUID key=UUID.randomUUID();
        service.start(learner,session,task,command(key));
        code("IDEMPOTENCY_KEY_REUSED",()->service.start(learner,session,task,
            new NextLessonService.Request(key,2L,"OTHER","1")));
        code("NEXT_LESSON_ALREADY_EXISTS",()->service.start(learner,session,task,
            new NextLessonService.Request(UUID.randomUUID(),2L,"OTHER","1")));
    }

    @Test void initialHistoryAndStaleOrForeignCursorPreserveIsolation() {
        var second=UUID.randomUUID();
        var now=Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO learners(id,display_name,created_at) VALUES(?,?,?)",
            second,"Other learner",now);
        try {
            // #237: a persisted bare learner has a valid empty initial history.
            // Ownership is enforced at the HTTP boundary; this service receives the resolved learner.
            var empty = service.history(second,"ACOUSTIC_GUITAR",20,null);
            assertTrue(empty.items().isEmpty()); assertNull(empty.nextCursor());
            assertEquals(0,jdbc.queryForObject(
                "SELECT count(*) FROM instrument_profiles WHERE learner_id=?",Integer.class,second));
            code("RESOURCE_NOT_FOUND",()->service.history(UUID.randomUUID(),"ACOUSTIC_GUITAR",20,null));
            code("INVALID_PAGINATION",()->service.history(second,"ACOUSTIC_GUITAR",20,"!"));
            code("INVALID_PAGINATION",()->service.history(learner,"ACOUSTIC_GUITAR",0,null));
            code("INVALID_PAGINATION",()->service.history(learner,"ACOUSTIC_GUITAR",51,null));
            code("INVALID_PAGINATION",()->service.history(learner,"ACOUSTIC_GUITAR",20,"!"));
            var forged=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                (second+"|"+profile+"|"+Instant.now()+"|"+task)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            code("INVALID_PAGINATION",()->service.history(learner,"ACOUSTIC_GUITAR",20,forged));
            code("INVALID_PAGINATION",()->service.history(second,"ACOUSTIC_GUITAR",20,forged));
        } finally {
            jdbc.update("DELETE FROM learners WHERE id=?",second);
        }
    }

    @Test void sourceTaskMustBelongToThePathSession() {
        code("RESOURCE_NOT_FOUND",()->service.start(learner,session,UUID.randomUUID(),
            command(UUID.randomUUID())));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM lesson_transitions WHERE learner_id=?",
            Integer.class,learner));
    }

    @Test void replayAfterAnotherSessionBecomesCurrentReturnsOriginalIds() {
        var a=service.start(learner,session,task,command(UUID.randomUUID()));
        var currentTask=a.result().task().id();
        var currentSession=a.result().session().id();
        var now=Timestamp.from(Instant.now());
        jdbc.update("UPDATE task_learning_state SET state='COMPLETED_BY_LEARNER',version=1,completed_at=? WHERE task_id=?",
            now,currentTask);
        var b=service.start(learner,currentSession,currentTask,
            new NextLessonService.Request(UUID.randomUUID(),1L,"GM-EB01","0.8B"));
        assertNotEquals(currentSession,b.result().session().id());
        // Replaying the original creation must not misrepresent B as the result of A.
        var fromA=service.start(learner,session,task,
            new NextLessonService.Request(
                jdbc.queryForObject("SELECT request_id FROM lesson_transitions WHERE source_task_id=?",
                    UUID.class,task),
                2L,"GM-EB01","0.8B"));
        assertTrue(fromA.replay());
        assertEquals(currentSession,fromA.result().session().id());
        assertEquals(b.result().session().id(),
            service.history(learner,"ACOUSTIC_GUITAR",1,null).items().getFirst().sessionId());
    }

    @Test void historyKeysetHandlesMoreThanFortyTiedTimestampsWithoutDuplicates() {
        var assigned=Timestamp.from(Instant.parse("2026-10-01T12:00:00Z"));
        for(int i=0;i<45;i++){
            UUID historicalSession=UUID.randomUUID(),historicalTask=UUID.randomUUID();
            jdbc.update("INSERT INTO diagnostic_sessions(id,instrument_profile_id,status,created_at,updated_at,version) VALUES(?,?,'ARCHIVED',?,?,0)",
                historicalSession,profile,assigned,assigned);
            jdbc.update("INSERT INTO diagnostic_tasks(id,diagnostic_session_id,exercise_id,assigned_at) VALUES(?,?,?,?)",
                historicalTask,historicalSession,EXERCISE,assigned);
        }
        java.util.Set<UUID> seen=new java.util.HashSet<>();
        String cursor=null;int page=0;
        do{
            var rows=service.history(learner,"ACOUSTIC_GUITAR",20,cursor);
            assertTrue(rows.items().size()<=20);
            for(var item:rows.items()) assertTrue(seen.add(item.taskId()),"Duplicate across keyset pages");
            cursor=rows.nextCursor();page++;
            assertTrue(page<=4,"Keyset cursor did not advance");
        }while(cursor!=null);
        assertEquals(46,seen.size());
        assertEquals(3,page);
    }

    @Test void diagnosticProbeCannotBeSelectedAsNextLearningRepertoire() {
        var codes=jdbc.query("""
            SELECT code,version FROM exercises
            WHERE is_current=true AND content_kind='DIAGNOSTIC_PROBE' LIMIT 1
            """,(rs,n)->new String[]{rs.getString(1),rs.getString(2)});
        org.junit.jupiter.api.Assumptions.assumeFalse(codes.isEmpty(),"No published probe fixture");
        String[] probe=codes.getFirst();
        code("RESOURCE_NOT_FOUND",()->service.start(learner,session,task,
            new NextLessonService.Request(UUID.randomUUID(),2L,probe[0],probe[1])));
        assertEquals("TASK_ASSIGNED",jdbc.queryForObject(
            "SELECT status FROM diagnostic_sessions WHERE id=?",String.class,session));
    }

    @Test void featureDisabledAndMalformedRequestFailWithoutWrites() {
        var disabled=new NextLessonService(null,null,null,null,null,null,null,false);
        code("LEARNING_DISABLED",()->disabled.start(learner,session,task,command(UUID.randomUUID())));
        code("INVALID_NEXT_LESSON_REQUEST",()->service.start(learner,session,task,
            new NextLessonService.Request(null,2L,"GM-EB01","0.8B")));
        code("INVALID_NEXT_LESSON_REQUEST",()->service.start(learner,session,task,
            new NextLessonService.Request(UUID.randomUUID(),-1L,"GM-EB01","0.8B")));
        assertEquals(0,jdbc.queryForObject(
            "SELECT count(*) FROM lesson_transitions WHERE learner_id=?",Integer.class,learner));
    }

}
