package ai.guitarmentor.application.exercise;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real PostgreSQL regression: a superseded assignment keeps exact version resources. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "GUITAR_MENTOR_TEST_POSTGRES", matches = "true")
class ExerciseVersionPostgresTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ExerciseCatalogService catalog;
    @Autowired ExerciseVersionResourceService resources;

    @Test
    void oldVersionKeepsItsDistinctBytesAndMissingPdfDoesNotFallBack() {
        UUID oldId = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO exercises (id,code,version,is_current,title,"
                    + "instrument_code,technique_code,style_code,difficulty,bpm,measures,"
                    + "meter,author,license,review_status,description,score_resource,events_resource)"
                    + " SELECT ?,'GM-EB01','qa-old',false,title,instrument_code,technique_code,"
                    + "style_code,difficulty,bpm,measures,meter,author,license,review_status,"
                    + "description,'exercises/qa120/score.musicxml','exercises/qa120/events.json'"
                    + " FROM exercises WHERE code='GM-EB01' AND version='0.8B'", oldId);
            String prefix = "/api/v1/exercises/GM-EB01/versions/qa-old";
            var old = catalog.findVersion("GM-EB01", "qa-old");
            var current = catalog.find("GM-EB01");
            assertEquals("qa-old", old.version());
            assertEquals("0.8B", current.version());
            assertEquals(prefix + "/score", old.scoreUrl());
            assertEquals(prefix + "/events", old.eventsUrl());
            assertNull(old.previewPdfUrl());
            var oldScore = resources.read("GM-EB01", "qa-old",
                    ExerciseVersionResourceService.Kind.SCORE);
            var currentScore = resources.read("GM-EB01", "0.8B",
                    ExerciseVersionResourceService.Kind.SCORE);
            assertNotEquals(new String(oldScore, java.nio.charset.StandardCharsets.UTF_8),
                    new String(currentScore, java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("{\"version\":\"qa-old\"}", new String(
                    resources.read("GM-EB01", "qa-old",
                            ExerciseVersionResourceService.Kind.EVENTS),
                    java.nio.charset.StandardCharsets.UTF_8).trim());
            var failure = assertThrows(ExerciseReferenceException.class,
                    () -> resources.locate("GM-EB01", "qa-old",
                            ExerciseVersionResourceService.Kind.PDF));
            assertEquals("EXERCISE_RESOURCE_NOT_FOUND", failure.code());
            assertEquals("EXERCISE_NOT_FOUND", assertThrows(
                    ExerciseReferenceException.class,
                    () -> catalog.findVersion("GM-EB01", "does-not-exist")).code());
            assertEquals(oldId, jdbc.queryForObject(
                    "SELECT id FROM exercises WHERE code='GM-EB01' AND version='qa-old'",
                    UUID.class));
        } finally {
            jdbc.update("DELETE FROM exercises WHERE id=?", oldId);
        }
    }
}
