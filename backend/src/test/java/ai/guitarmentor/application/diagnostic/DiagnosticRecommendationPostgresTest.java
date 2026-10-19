package ai.guitarmentor.application.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real PostgreSQL schema and recommendation behavior, including a nonempty seed catalog. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "GUITAR_MENTOR_TEST_POSTGRES", matches = "true")
class DiagnosticRecommendationPostgresTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired DiagnosticRecommendationService recommendations;

    UUID learnerId;
    UUID profileId;
    UUID sessionId;
    UUID backgroundId;

    @BeforeEach
    void fixtures() {
        learnerId = UUID.randomUUID();
        profileId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        backgroundId = UUID.randomUUID();
        var now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO learners(id,display_name,created_at) VALUES(?,?,?)",
                learnerId, "R3 integration learner", now);
        jdbc.update("INSERT INTO instrument_profiles"
                + "(id,learner_id,instrument_code,technique_code) VALUES(?,?,?,?)",
                profileId, learnerId, "ACOUSTIC_GUITAR", "FINGERSTYLE");
        jdbc.update("INSERT INTO diagnostic_sessions"
                + "(id,instrument_profile_id,status,created_at,updated_at,version)"
                + " VALUES(?,?,'READY_FOR_TASK',?,?,0)", sessionId, profileId, now, now);
    }

    @AfterEach
    void clean() {
        if (profileId == null) return;
        jdbc.update("DELETE FROM diagnostic_tasks WHERE diagnostic_session_id=?", sessionId);
        jdbc.update("DELETE FROM diagnostic_sessions WHERE id=?", sessionId);
        jdbc.update("DELETE FROM learner_skill_signal_history WHERE instrument_profile_id=?", profileId);
        jdbc.update("DELETE FROM instrument_music_preferences WHERE instrument_profile_id=?", profileId);
        jdbc.update("DELETE FROM learning_backgrounds WHERE instrument_profile_id=?", profileId);
        jdbc.update("DELETE FROM instrument_profiles WHERE id=?", profileId);
        jdbc.update("DELETE FROM learners WHERE id=?", learnerId);
    }

    private void background(String level, int minutes) {
        jdbc.update("INSERT INTO learning_backgrounds"
                + "(id,instrument_profile_id,experience_level,years_playing,"
                + "practice_minutes,sessions_per_week,notes) VALUES(?,?,?,0,?,3,'')",
                backgroundId, profileId, level, minutes);
    }

    private int assignedCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM diagnostic_tasks "
                + "WHERE diagnostic_session_id=?", Integer.class, sessionId);
    }

    @Test
    void noBackgroundMeansUnknownAndNoIntermediateAssignment() {
        var result = recommendations.recommend(learnerId, sessionId, 3);
        assertEquals("NO_MATCH", result.status());
        assertEquals("NO_BEGINNER_EXERCISE", result.reasonCode());
        assertEquals(0, assignedCount());
    }

    @Test
    void intermediateProfileGetsVersionedPrototypeWithoutCreatingTask() {
        background("INTERMEDIATE", 45);
        var result = recommendations.recommend(learnerId, sessionId, 3);
        assertEquals("RECOMMENDED", result.status());
        assertEquals("GM-EB01", result.items().getFirst().exerciseCode());
        assertEquals("0.8B", result.items().getFirst().exerciseVersion());
        assertTrue(result.items().getFirst().skillCodes().contains("PULSE"));
        assertEquals(0, assignedCount());
    }

    @Test
    void excludingTheOnlyStyleProducesNoMatchAndNoTask() {
        background("INTERMEDIATE", 45);
        jdbc.update("INSERT INTO instrument_music_preferences"
                + "(instrument_profile_id,style_code,rating) VALUES(?,'BLUES','EXCLUDE')",
                profileId);
        var result = recommendations.recommend(learnerId, sessionId, 3);
        assertEquals("NO_MATCH", result.status());
        assertEquals("ALL_STYLES_EXCLUDED", result.reasonCode());
        assertEquals(0, assignedCount());
    }
}
