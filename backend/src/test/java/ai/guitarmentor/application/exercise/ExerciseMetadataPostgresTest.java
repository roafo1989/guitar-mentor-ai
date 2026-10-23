package ai.guitarmentor.application.exercise;

import static org.junit.jupiter.api.Assertions.*;

import ai.guitarmentor.infrastructure.persistence.ExerciseRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Runs against real Flyway-migrated PostgreSQL, never production. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "GUITAR_MENTOR_TEST_POSTGRES", matches = "true")
class ExerciseMetadataPostgresTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ExerciseCatalogService catalog;
    @Autowired ExerciseRepository repository;
    @Autowired ExerciseViewAssembler assembler;

    private static final UUID ORIGINAL = UUID.fromString("58430000-0000-4000-8000-000000000801");

    @Test
    void seededPrototypePreservesV12CriteriaAndUnknownAuthoringFields() {
        var found = catalog.find("GM-EB01");
        assertNull(found.learningGoal());
        assertNull(found.practiceInstructions());
        assertNull(found.tuning());
        assertEquals(30, found.recommendedPracticeMinutes());
        assertEquals(java.util.List.of(
                "Равномерное ощущение долей без произвольных остановок",
                "Басовая линия не прерывается на смене мелодических фраз",
                "Мелодическая линия слышна поверх баса"), found.successCriteria());
        assertEquals(found.successCriteria(), catalog.available("ACOUSTIC_GUITAR", "FINGERSTYLE")
                .getFirst().successCriteria());
        assertFalse(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.columns"
                        + " WHERE table_name='exercises' AND column_name='success_criteria')",
                Boolean.class));
    }

    @Test
    void metadataChecksRejectWhitespaceAndOverlongStringsWithoutChangingSeed() {
        for (String column : java.util.List.of("learning_goal", "practice_instructions", "tuning")) {
            for (String invalid : java.util.List.of("   ", "\t\n  ")) {
                assertThrows(DataIntegrityViolationException.class,
                        () -> jdbc.update("UPDATE exercises SET " + column + "=? WHERE id=?", invalid, ORIGINAL));
            }
        }
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE exercises SET learning_goal=? WHERE id=?", "X".repeat(4001), ORIGINAL));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE exercises SET practice_instructions=? WHERE id=?", "X".repeat(8001), ORIGINAL));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE exercises SET tuning=? WHERE id=?", "X".repeat(65), ORIGINAL));
    }

    @Test
    void acceptsExactLengthBoundariesAndNullsWithoutBackfillingExistingContent() {
        UUID id = UUID.randomUUID();
        String code = "TEST119-" + id.toString().substring(0, 8);
        try {
            jdbc.update("INSERT INTO exercises (id,code,version,is_current,title,"
                    + "instrument_code,technique_code,style_code,difficulty,bpm,measures,"
                    + "meter,author,license,review_status,description,score_resource,events_resource,"
                    + "learning_goal,practice_instructions,tuning)"
                    + " SELECT ?,?,'v1',false,title,instrument_code,technique_code,"
                    + "style_code,difficulty,bpm,measures,meter,author,license,review_status,"
                    + "description,score_resource,events_resource,?,?,?"
                    + " FROM exercises WHERE id=?", id, code,
                    "G".repeat(4000), "I".repeat(8000), "T".repeat(64), ORIGINAL);
            var exact = assembler.one(repository.findById(id).orElseThrow());
            assertEquals(4000, exact.learningGoal().length());
            assertEquals(8000, exact.practiceInstructions().length());
            assertEquals(64, exact.tuning().length());
            assertEquals(java.util.List.of(), exact.successCriteria());
            assertNull(exact.recommendedPracticeMinutes());
            jdbc.update("UPDATE exercises SET learning_goal=NULL,practice_instructions=NULL,"
                    + "tuning=NULL WHERE id=?", id);
            var nullable = assembler.one(repository.findById(id).orElseThrow());
            assertNull(nullable.learningGoal());
            assertNull(nullable.practiceInstructions());
            assertNull(nullable.tuning());
        } finally {
            jdbc.update("DELETE FROM exercises WHERE id=?", id);
        }
    }

    @Test
    void exactOldVersionReturnsItsOwnCriteriaAndNotCurrentVersionCriteria() {
        UUID old = UUID.randomUUID();
        String code = "GM-EB01";
        try {
            jdbc.update("INSERT INTO exercises (id,code,version,is_current,title,"
                    + "instrument_code,technique_code,style_code,difficulty,bpm,measures,"
                    + "meter,author,license,review_status,description,score_resource,events_resource,"
                    + "learning_goal,recommended_practice_minutes)"
                    + " SELECT ?,?,'v1',false,title,instrument_code,technique_code,"
                    + "style_code,difficulty,bpm,measures,meter,author,license,review_status,"
                    + "description,score_resource,events_resource,'Original goal',20"
                    + " FROM exercises WHERE id=?", old, code, ORIGINAL);
            jdbc.update("INSERT INTO exercise_criteria(exercise_id,criterion_code,skill_code,"
                    + "label,observable_result,display_order)"
                    + " VALUES (?,'ONE','PULSE','Pulse','Old version criterion',1)", old);
            var exact = assembler.one(repository.findById(old).orElseThrow());
            assertEquals("GM-EB01", exact.code());
            assertEquals("v1", exact.version());
            assertEquals("Original goal", exact.learningGoal());
            assertEquals(20, exact.recommendedPracticeMinutes());
            assertEquals(java.util.List.of("Old version criterion"), exact.successCriteria());
            var current = catalog.find("GM-EB01");
            assertEquals("0.8B", current.version());
            assertEquals(3, current.successCriteria().size());
        } finally {
            jdbc.update("DELETE FROM exercise_criteria WHERE exercise_id=?", old);
            jdbc.update("DELETE FROM exercises WHERE id=?", old);
        }
    }
}
