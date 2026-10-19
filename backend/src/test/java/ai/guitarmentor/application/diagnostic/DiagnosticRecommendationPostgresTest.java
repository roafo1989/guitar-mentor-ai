package ai.guitarmentor.application.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import ai.guitarmentor.application.onboarding.OnboardingService;
import ai.guitarmentor.domain.onboarding.SkillTag;
import ai.guitarmentor.domain.onboarding.ExperienceLevel;
import ai.guitarmentor.domain.onboarding.GoalCode;
import ai.guitarmentor.interfaces.rest.onboarding.OnboardingDtos.BackgroundRequest;
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
    @Autowired SkillMapService skillMap;
    @Autowired OnboardingService onboarding;

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
    void skillClaimsCanBeReadInStablePagesWithoutGrantingVerifiedLevel() {
        var older = UUID.randomUUID();
        var newer = UUID.randomUUID();
        var oldTime = Timestamp.from(Instant.now().minusSeconds(120));
        var newTime = Timestamp.from(Instant.now().minusSeconds(30));
        jdbc.update("INSERT INTO learner_skill_signal_history"
                + "(id,instrument_profile_id,skill_code,source_type,claim_state,"
                + "source_reference,recorded_at) VALUES (?,?,'MELODY','SELF_REPORTED',"
                + "'CLAIMED','ONBOARDING',?)", older, profileId, oldTime);
        jdbc.update("INSERT INTO learner_skill_signal_history"
                + "(id,instrument_profile_id,skill_code,source_type,claim_state,"
                + "source_reference,recorded_at) VALUES (?,?,'MELODY','SELF_REPORTED',"
                + "'RETRACTED','ONBOARDING',?)", newer, profileId, newTime);
        var map = skillMap.map(learnerId, "ACOUSTIC_GUITAR");
        var melody = map.stream().filter(v -> v.skillCode().equals("MELODY")).findFirst().orElseThrow();
        assertEquals(0, melody.assessedLevel());
        assertEquals(false, melody.selfReported());
        var first = skillMap.history(learnerId, "ACOUSTIC_GUITAR", "MELODY", 1, null, null);
        assertEquals(newer, first.getFirst().id());
        var next = skillMap.history(learnerId, "ACOUSTIC_GUITAR", "MELODY", 1,
                first.getFirst().recordedAt(), first.getFirst().id());
        assertEquals(older, next.getFirst().id());
        assertThrows(IllegalArgumentException.class,
                () -> skillMap.history(learnerId, "ACOUSTIC_GUITAR", "MELODY",
                        1, first.getFirst().recordedAt(), null));
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

    @Test
    void corruptPrerequisiteGraphFailsClosedForRecommendations() {
        jdbc.update("INSERT INTO skill_prerequisites (skill_code,prerequisite_code)"
                + " VALUES ('PULSE','SUBDIVISION')");
        try {
            assertThrows(InvalidSkillGraphException.class,
                    () -> recommendations.recommend(learnerId, sessionId, 3));
            assertEquals(0, assignedCount());
        } finally {
            jdbc.update("DELETE FROM skill_prerequisites"
                    + " WHERE skill_code='PULSE' AND prerequisite_code='SUBDIVISION'");
        }
    }

    @Test
    void validObservedEvidenceIsReadSeparatelyFromSelfReportWithConfidence() {
        UUID eventId = UUID.randomUUID();
        jdbc.update("INSERT INTO learner_skill_signal_history"
                + "(id,instrument_profile_id,skill_code,source_type,level,confidence,"
                + "source_reference,recorded_at) "
                + "VALUES (?,?,'PULSE','OBSERVED',3,0.850,'TEST_ASSESSMENT',CURRENT_TIMESTAMP)",
                eventId, profileId);
        var pulse = skillMap.map(learnerId, "ACOUSTIC_GUITAR").stream()
                .filter(v -> v.skillCode().equals("PULSE")).findFirst().orElseThrow();
        assertEquals(3, pulse.assessedLevel());
        assertFalse(pulse.selfReported());
        assertEquals("OBSERVED", pulse.evidenceStatus());
        var history = skillMap.history(learnerId,"ACOUSTIC_GUITAR","PULSE",20,null,null);
        assertEquals(eventId, history.getFirst().id());
        assertEquals(new java.math.BigDecimal("0.850"), history.getFirst().confidence());
    }

    @Test
    void concurrentIdenticalOnboardingUpdatesWriteOneClaim() throws Exception {
        var request = new BackgroundRequest(ExperienceLevel.BEGINNER, 0,
                Set.of(SkillTag.FINGERSTYLE_MELODY), List.of(), Set.of(GoalCode.FINGERSTYLE),
                30, 3, "");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var go = new java.util.concurrent.CountDownLatch(1);
            var a = pool.submit(() -> { go.await(); return onboarding.upsert(
                    learnerId, "ACOUSTIC_GUITAR", request); });
            var b = pool.submit(() -> { go.await(); return onboarding.upsert(
                    learnerId, "ACOUSTIC_GUITAR", request); });
            go.countDown();
            a.get(15, TimeUnit.SECONDS);
            b.get(15, TimeUnit.SECONDS);
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*)"
                        + " FROM learner_skill_signal_history WHERE instrument_profile_id=?"
                        + " AND skill_code='MELODY' AND claim_state='CLAIMED'",
                Integer.class, profileId));
    }
}
