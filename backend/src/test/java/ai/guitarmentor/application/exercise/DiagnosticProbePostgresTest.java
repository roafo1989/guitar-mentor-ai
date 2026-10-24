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
import org.springframework.core.io.ClassPathResource;

/** Real Flyway-migrated PostgreSQL: hidden draft cannot leak through exact APIs. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named="GUITAR_MENTOR_TEST_POSTGRES", matches="true")
class DiagnosticProbePostgresTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ExerciseCatalogService catalog;
    @Autowired ExerciseRepository exercises;
    @Autowired ExerciseVersionResourceService media;

    @Test void v25PublishesAcceptedProbeWithoutChangingExistingRepertoire() throws Exception {
        var original = catalog.find("GM-EB01");
        assertEquals("LEARNING_REPERTOIRE", original.contentKind());
        assertEquals("quarter", original.tempoUnit());

        var probe = exercises.findByCodeAndVersion("GM-DIAG-PULSE-3-8-01","0.1.0").orElseThrow();
        assertEquals("DIAGNOSTIC_PROBE", probe.getContentKind());
        assertEquals("eighth", probe.getTempoUnit());
        assertEquals(100, probe.getBpm());
        assertEquals(22, probe.getDurationTargetSeconds());
        assertEquals("3/8", probe.getMeter());
        assertTrue(probe.isCurrent());
        assertEquals("ORIGINAL_OWNED", probe.getLicense());
        assertEquals("APPROVED", probe.getReviewStatus());
        assertTrue(ExercisePublicationPolicy.eligible(probe));
        assertEquals(1L, jdbc.queryForObject(
            "SELECT count(*) FROM exercises WHERE code='GM-DIAG-PULSE-3-8-01' AND is_current",
            Long.class));
        assertTrue(catalog.available("ACOUSTIC_GUITAR","FINGERSTYLE")
            .stream().anyMatch(e -> e.code().equals(probe.getCode())));
        var exact = catalog.findVersion("GM-DIAG-PULSE-3-8-01","0.1.0");
        assertEquals("eighth", exact.tempoUnit());
        assertEquals("3/8", probe.getMeter());
        byte[] pdf=media.read(probe.getCode(),probe.getVersion(),
            ExerciseVersionResourceService.Kind.PDF);
        byte[] audio=media.read(probe.getCode(),probe.getVersion(),
            ExerciseVersionResourceService.Kind.AUDIO);
        assertTrue(pdf.length>3000 && pdf[0]==37 && pdf[1]==80);
        assertTrue(audio.length>3000 && (audio[0]==73 || (audio[0]&0xff)==0xff));
        assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM exercise_criteria"
            +" WHERE exercise_id=? AND criterion_code='TRIPLE_PULSE'",Long.class,probe.getId()));
        assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM exercise_skills"
            +" WHERE exercise_id=? AND target_role='PRIMARY'",Long.class,probe.getId()));
        assertEquals("LEARNING_REPERTOIRE", catalog.find("GM-EB01").contentKind());
    }
    @Test
    @org.springframework.transaction.annotation.Transactional
    void reviewerApprovedVersionWouldExposeItsOwnPlayableMediaWithoutLegacyRegression()
            throws Exception {
        var draftId = java.util.UUID.fromString("58430000-0000-4000-8000-000000001451");
        // This transaction rolls back: not a publication operation.
        jdbc.update("UPDATE exercises SET is_current=true,license='ORIGINAL_OWNED',"
                + " review_status='APPROVED' WHERE id=?", draftId);
        var version = catalog.findVersion("GM-DIAG-PULSE-3-8-01", "0.1.0");
        assertEquals("DIAGNOSTIC_PROBE", version.contentKind());
        assertEquals("eighth", version.tempoUnit());
        assertEquals(22, version.durationTargetSeconds());
        assertTrue(catalog.available("ACOUSTIC_GUITAR", "FINGERSTYLE")
                .stream().anyMatch(e -> e.code().equals("GM-DIAG-PULSE-3-8-01")));
        assertTrue(media.read("GM-DIAG-PULSE-3-8-01", "0.1.0",
                ExerciseVersionResourceService.Kind.PDF).length > 3000);
        assertTrue(media.read("GM-DIAG-PULSE-3-8-01", "0.1.0",
                ExerciseVersionResourceService.Kind.AUDIO).length > 3000);
        assertEquals("LEARNING_REPERTOIRE", catalog.find("GM-EB01").contentKind());
    }

}
