package ai.guitarmentor.application.onboarding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.guitarmentor.domain.learner.InstrumentProfile;
import ai.guitarmentor.domain.learner.Learner;
import ai.guitarmentor.domain.onboarding.ExperienceLevel;
import ai.guitarmentor.domain.onboarding.GoalCode;
import ai.guitarmentor.domain.onboarding.LearningBackground;
import ai.guitarmentor.infrastructure.persistence.LearnerRepository;
import ai.guitarmentor.infrastructure.persistence.LearningBackgroundRepository;
import ai.guitarmentor.interfaces.rest.onboarding.OnboardingDtos.BackgroundRequest;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** #235: no blanket conversion of missing resources into empty questionnaires. */
class InitialBackgroundTest {
    final LearnerRepository learners = mock(LearnerRepository.class);
    final LearningBackgroundRepository backgrounds = mock(LearningBackgroundRepository.class);
    final OnboardingService service = new OnboardingService(learners, backgrounds);
    final Learner learner = new Learner("New student");
    final BackgroundRequest request = new BackgroundRequest(ExperienceLevel.NONE, 0,
            Set.of(), List.of(), Set.of(GoalCode.FINGERSTYLE), 30, 3, "first save");

    @Test void bareLearnerReadIsEmptyAndDoesNotWrite() {
        when(learners.findById(learner.getId())).thenReturn(Optional.of(learner));
        assertTrue(service.get(learner.getId(), "ACOUSTIC_GUITAR").isEmpty());
        assertTrue(learner.getInstrumentProfiles().isEmpty());
        verify(learners, never()).saveAndFlush(any());
        verifyNoInteractions(backgrounds);
    }

    @Test void missingLearnerRemainsNotFoundForReadAndWrite() {
        UUID missing = UUID.randomUUID();
        assertThrows(EntityNotFoundException.class, () -> service.get(missing, "ACOUSTIC_GUITAR"));
        assertThrows(EntityNotFoundException.class, () -> service.upsert(missing, "ACOUSTIC_GUITAR", request));
        verifyNoInteractions(backgrounds);
        verify(learners, never()).saveAndFlush(any());
    }

    @Test void unknownInstrumentIsNotAnEmptyInitialQuestionnaire() {
        when(learners.findById(learner.getId())).thenReturn(Optional.of(learner));
        when(learners.findLockedById(learner.getId())).thenReturn(Optional.of(learner));
        assertThrows(EntityNotFoundException.class, () -> service.get(learner.getId(), "TYPO"));
        assertThrows(EntityNotFoundException.class, () -> service.upsert(learner.getId(), "TYPO", request));
        assertTrue(learner.getInstrumentProfiles().isEmpty());
        verifyNoInteractions(backgrounds);
    }

    @Test void learnerWithAnotherInstrumentIsNotSilentlyReinitialized() {
        learner.addInstrumentProfile(new InstrumentProfile("PIANO", "CLASSICAL"));
        when(learners.findById(learner.getId())).thenReturn(Optional.of(learner));
        when(learners.findLockedById(learner.getId())).thenReturn(Optional.of(learner));
        assertThrows(EntityNotFoundException.class, () -> service.get(learner.getId(), "ACOUSTIC_GUITAR"));
        assertThrows(EntityNotFoundException.class, () -> service.upsert(learner.getId(), "ACOUSTIC_GUITAR", request));
        assertEquals(1, learner.getInstrumentProfiles().size());
        verifyNoInteractions(backgrounds);
    }

    @Test void firstWritePersistsInstrumentBeforeBackgroundWhileHoldingLearnerLock() {
        when(learners.findLockedById(learner.getId())).thenReturn(Optional.of(learner));
        when(backgrounds.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        assertEquals("first save", service.upsert(learner.getId(), "ACOUSTIC_GUITAR", request).notes());
        assertEquals(1, learner.getInstrumentProfiles().size());
        var profile = learner.getInstrumentProfiles().getFirst();
        assertEquals("ACOUSTIC_GUITAR", profile.getInstrumentCode());
        assertEquals("FINGERSTYLE", profile.getTechniqueCode());
        var order = inOrder(learners, backgrounds);
        order.verify(learners).findLockedById(learner.getId());
        order.verify(learners).saveAndFlush(learner);
        order.verify(backgrounds).findByInstrumentProfile_Id(profile.getId());
        order.verify(backgrounds).saveAndFlush(any(LearningBackground.class));
    }

    @Test void readFailurePropagatesAndNeverCreatesAnInstrument() {
        learner.addInstrumentProfile(new InstrumentProfile("ACOUSTIC_GUITAR", "FINGERSTYLE"));
        when(learners.findById(learner.getId())).thenReturn(Optional.of(learner));
        when(backgrounds.findByInstrumentProfile_Id(any())).thenThrow(new IllegalStateException("storage failed"));
        assertThrows(IllegalStateException.class, () -> service.get(learner.getId(), "ACOUSTIC_GUITAR"));
        verify(learners, never()).saveAndFlush(any());
    }
}
