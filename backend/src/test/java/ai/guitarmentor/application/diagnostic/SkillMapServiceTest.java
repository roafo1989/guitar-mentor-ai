package ai.guitarmentor.application.diagnostic;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import ai.guitarmentor.domain.learner.InstrumentProfile;
import ai.guitarmentor.domain.learner.Learner;
import ai.guitarmentor.infrastructure.persistence.LearnerRepository;
import ai.guitarmentor.infrastructure.persistence.SkillMapRepository;
import ai.guitarmentor.infrastructure.persistence.SkillMapRepository.SkillRow;
import ai.guitarmentor.infrastructure.persistence.SkillMapRepository.SignalRow;
import jakarta.persistence.EntityNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SkillMapServiceTest {
    @Mock LearnerRepository learners;
    @Mock SkillMapRepository repository;

    @Test
    void prerequisiteGraphMustBeAcyclicAndKnown() {
        assertDoesNotThrow(() -> SkillMapService.validateAcyclic(
                Map.of("BASS", List.of("PULSE"), "MELODY", List.of("PULSE")),
                Set.of("PULSE", "BASS", "MELODY")));
        assertThrows(IllegalStateException.class, () -> SkillMapService.validateAcyclic(
                Map.of("A", List.of("B"), "B", List.of("A")), Set.of("A", "B")));
        assertThrows(IllegalStateException.class, () -> SkillMapService.validateAcyclic(
                Map.of("A", List.of("MISSING")), Set.of("A")));
    }

    @Test
    void selfReportNeverBecomesAssessedLevelAndRetractionWins() {
        var learner = new Learner("Student");
        var instrument = new InstrumentProfile("ACOUSTIC_GUITAR", "FINGERSTYLE");
        learner.addInstrumentProfile(instrument);
        when(learners.findById(learner.getId())).thenReturn(Optional.of(learner));
        when(repository.skills()).thenReturn(List.of(
                new SkillRow("MELODY", "Melody", "Upper voice", "MUSICALITY", 1)));
        when(repository.prerequisites()).thenReturn(Map.of());
        when(repository.signals(instrument.getId())).thenReturn(List.of(
                new SignalRow("MELODY", "SELF_REPORTED", "RETRACTED", null, Instant.now(), null),
                new SignalRow("MELODY", "SELF_REPORTED", "CLAIMED", null,
                        Instant.now().minusSeconds(60), null)));
        var map = new SkillMapService(learners, repository)
                .map(learner.getId(), "ACOUSTIC_GUITAR");
        assertEquals(0, map.getFirst().assessedLevel());
        assertEquals(false, map.getFirst().selfReported());
        assertEquals("NOT_ASSESSED", map.getFirst().evidenceStatus());
    }

    @Test
    void anotherLearnerCannotReadSkillHistory() {
        var learner = new Learner("Other");
        when(learners.findById(learner.getId())).thenReturn(Optional.of(learner));
        assertThrows(EntityNotFoundException.class,
                () -> new SkillMapService(learners, repository)
                        .history(learner.getId(), "ACOUSTIC_GUITAR", "MELODY", 20));
    }
}
