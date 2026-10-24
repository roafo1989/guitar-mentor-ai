package ai.guitarmentor.application.exercise;

import static org.junit.jupiter.api.Assertions.*;
import ai.guitarmentor.infrastructure.persistence.ExerciseRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;

/** Real Flyway-migrated PostgreSQL: hidden draft cannot leak through exact APIs. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named="GUITAR_MENTOR_TEST_POSTGRES", matches="true")
class DiagnosticProbePostgresTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ExerciseCatalogService catalog;
    @Autowired ExerciseRepository exercises;
    @Autowired ExerciseVersionResourceService media;

    @Test void v20IsAdditiveAndDraftV21IsNeverAssignableOrPublic() {
        var original=catalog.find("GM-EB01");
        assertEquals("LEARNING_REPERTOIRE",original.contentKind());
        assertEquals("quarter",original.tempoUnit());
        assertNull(original.durationTargetSeconds());

        var draft=exercises.findByCodeAndVersion("GM-DIAG-PULSE-3-8-01","0.1.0").orElseThrow();
        assertEquals("DIAGNOSTIC_PROBE",draft.getContentKind());
        assertEquals("eighth",draft.getTempoUnit());
        assertEquals(22,draft.getDurationTargetSeconds());
        assertEquals("3/8",draft.getMeter());
        assertFalse(draft.isCurrent());
        assertFalse(ExercisePublicationPolicy.eligible(draft));
        assertTrue(catalog.available("ACOUSTIC_GUITAR","FINGERSTYLE")
                .stream().noneMatch(e->e.code().equals(draft.getCode())));
        assertThrows(ExerciseReferenceException.class,
            ()->catalog.findVersion("GM-DIAG-PULSE-3-8-01","0.1.0"));
        var error=assertThrows(ExerciseReferenceException.class,
            ()->media.locate("GM-DIAG-PULSE-3-8-01","0.1.0",
              ExerciseVersionResourceService.Kind.SCORE));
        assertEquals(HttpStatus.NOT_FOUND,error.status());
        assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM exercise_criteria"
            +" WHERE exercise_id=? AND criterion_code='TRIPLE_PULSE'",Long.class,draft.getId()));
        assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM exercise_skills"
            +" WHERE exercise_id=? AND target_role='PRIMARY'",Long.class,draft.getId()));
    }
}
