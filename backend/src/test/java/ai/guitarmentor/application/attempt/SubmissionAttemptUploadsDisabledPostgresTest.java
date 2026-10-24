package ai.guitarmentor.application.attempt;

import static org.junit.jupiter.api.Assertions.*;

import ai.guitarmentor.interfaces.rest.attempt.SubmissionAttemptController;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Explicit CORE-B6 regression with uploads OFF and a real PostgreSQL fixture.
 * The enabled-upload integration suite has a different Spring context.
 */
@SpringBootTest(properties = {"guitar-mentor.submissions.enabled=false",
        "guitar-mentor.submissions.poll-delay-ms=999999"})
@EnabledIfEnvironmentVariable(named = "GUITAR_MENTOR_TEST_POSTGRES", matches = "true")
class SubmissionAttemptUploadsDisabledPostgresTest {
    private static final UUID EXERCISE_ID =
            UUID.fromString("58430000-0000-4000-8000-000000000801");
    @Autowired JdbcTemplate jdbc;
    @Autowired SubmissionAttemptService service;

    private UUID learner, profile, session, task, attempt;

    @BeforeEach
    void setUp() {
        learner = UUID.randomUUID();
        profile = UUID.randomUUID();
        session = UUID.randomUUID();
        task = UUID.randomUUID();
        attempt = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO learners(id,display_name,created_at) VALUES(?,?,?)",
                learner,"Uploads disabled student",now);
        jdbc.update("INSERT INTO instrument_profiles(id,learner_id,instrument_code,technique_code)"
                + " VALUES(?,?,?,?)",profile,learner,"ACOUSTIC_GUITAR","FINGERSTYLE");
        jdbc.update("INSERT INTO diagnostic_sessions"
                + "(id,instrument_profile_id,status,created_at,updated_at,version)"
                + " VALUES(?,?,?,?,?,?)",session,profile,"TASK_ASSIGNED",now,now,0L);
        jdbc.update("INSERT INTO diagnostic_tasks(id,diagnostic_session_id,exercise_id,assigned_at)"
                + " VALUES(?,?,?,?)",task,session,EXERCISE_ID,now);
        jdbc.update("""
                INSERT INTO submission_attempts
                  (id,diagnostic_task_id,request_id,attempt_number,submitted_at,status,
                   media_key,media_format,size_bytes)
                VALUES (?,?,?,1,?,'UPLOADED','disabled-fixture.wav','wav',12)
                """,attempt,task,UUID.randomUUID(),now);
    }

    @AfterEach
    void tearDown() {
        if (task == null) return;
        jdbc.update("DELETE FROM submission_attempts WHERE diagnostic_task_id=?",task);
        jdbc.update("DELETE FROM diagnostic_tasks WHERE id=?",task);
        jdbc.update("DELETE FROM diagnostic_sessions WHERE id=?",session);
        jdbc.update("DELETE FROM instrument_profiles WHERE id=?",profile);
        jdbc.update("DELETE FROM learners WHERE id=?",learner);
    }

    @Test
    void readsStayAvailableWhileNewUploadsAreForbidden() throws Exception {
        assertFalse(service.isEnabled());
        var page = service.page(learner,session,task,20,null);
        assertEquals(1,page.items().size());
        assertEquals(attempt,page.items().get(0).id());
        assertEquals(attempt,service.detail(learner,session,task,attempt).id());
        assertEquals(1,service.list(learner,session,task).size());

        var mvc = MockMvcBuilders.standaloneSetup(new SubmissionAttemptController(service)).build();
        String base = "/api/v1/learners/" + learner
                + "/diagnostic-sessions/" + session + "/tasks/" + task + "/attempts";
        mvc.perform(get(base + "/page"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.items[0].id").value(attempt.toString()));
        mvc.perform(get(base + "/" + attempt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(attempt.toString()));
        mvc.perform(get(base + "/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        var rejection = assertThrows(ResponseStatusException.class,
                () -> service.submit(learner,session,task,UUID.randomUUID(),
                        "must not upload",new MockMultipartFile("file","test.wav",
                                "audio/wav",new byte[]{1,2,3})));
        assertEquals(HttpStatus.FORBIDDEN,rejection.getStatusCode());
        assertEquals(1,jdbc.queryForObject(
                "SELECT count(*) FROM submission_attempts WHERE diagnostic_task_id=?",
                Integer.class,task));
    }
}
