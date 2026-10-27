package ai.guitarmentor.application.diagnostic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import ai.guitarmentor.application.learning.LearningProblem;
import ai.guitarmentor.domain.learner.InstrumentProfile;
import ai.guitarmentor.domain.learner.Learner;
import ai.guitarmentor.infrastructure.persistence.LearnerRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InitialLessonHistoryTest {
    @Test void bareLearnerHistoryIsEmptyAndPaginationIsValidatedBeforeFallback() {
        var learners = mock(LearnerRepository.class);
        var learner = new Learner("New student");
        when(learners.findById(learner.getId())).thenReturn(Optional.of(learner));
        var service = new NextLessonService(learners, null, null, null, null, null, null, true);
        var page = service.history(learner.getId(), "ACOUSTIC_GUITAR", 20, null);
        assertTrue(page.items().isEmpty()); assertNull(page.nextCursor());
        assertThrows(LearningProblem.class, () -> service.history(learner.getId(), "ACOUSTIC_GUITAR", 0, null));
        assertThrows(LearningProblem.class, () -> service.history(learner.getId(), "ACOUSTIC_GUITAR", 51, null));
        assertThrows(LearningProblem.class, () -> service.history(learner.getId(), "ACOUSTIC_GUITAR", 20, ""));
        assertThrows(LearningProblem.class, () -> service.history(learner.getId(), "ACOUSTIC_GUITAR", 20, "broken"));
        assertThrows(LearningProblem.class, () -> service.history(learner.getId(), "UNKNOWN", 20, null));
        assertThrows(LearningProblem.class, () -> service.history(UUID.randomUUID(), "ACOUSTIC_GUITAR", 20, null));
        learner.addInstrumentProfile(new InstrumentProfile("PIANO", "KEYBOARD"));
        assertThrows(LearningProblem.class, () -> service.history(learner.getId(), "ACOUSTIC_GUITAR", 20, null));
        verify(learners, never()).save(any());
    }
}
