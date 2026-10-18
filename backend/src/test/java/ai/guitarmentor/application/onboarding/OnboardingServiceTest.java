package ai.guitarmentor.application.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import ai.guitarmentor.domain.learner.InstrumentProfile;
import ai.guitarmentor.domain.learner.Learner;
import ai.guitarmentor.domain.onboarding.ExperienceLevel;
import ai.guitarmentor.domain.onboarding.GoalCode;
import ai.guitarmentor.domain.onboarding.LearningBackground;
import ai.guitarmentor.domain.onboarding.SkillTag;
import ai.guitarmentor.infrastructure.persistence.LearnerRepository;
import ai.guitarmentor.infrastructure.persistence.LearningBackgroundRepository;
import ai.guitarmentor.interfaces.rest.onboarding.OnboardingDtos.BackgroundRequest;
import ai.guitarmentor.interfaces.rest.onboarding.OnboardingDtos.KnownPieceInput;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OnboardingServiceTest {

    @Mock LearnerRepository learners;
    @Mock LearningBackgroundRepository backgrounds;

    private OnboardingService service;
    private Learner learner;
    private InstrumentProfile instrument;

    @BeforeEach
    void setUp() {
        service = new OnboardingService(learners, backgrounds);
        learner = new Learner("Student");
        instrument = new InstrumentProfile("ACOUSTIC_GUITAR", "FINGERSTYLE");
        learner.addInstrumentProfile(instrument);
        when(learners.findById(learner.getId())).thenReturn(Optional.of(learner));
    }

    private BackgroundRequest noviceRequest() {
        return new BackgroundRequest(
                ExperienceLevel.NONE, 0, Set.of(), List.of(),
                Set.of(GoalCode.FINGERSTYLE), 30, 3, "");
    }

    @Test
    void noviceCanSaveBackgroundWithoutPiecesOrKnownTechniques() {
        when(backgrounds.findByInstrumentProfile_Id(instrument.getId()))
                .thenReturn(Optional.empty());
        when(backgrounds.saveAndFlush(any(LearningBackground.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        var view = service.upsert(learner.getId(), "ACOUSTIC_GUITAR", noviceRequest());
        assertEquals(ExperienceLevel.NONE, view.experienceLevel());
        assertEquals(0, view.yearsPlaying());
        assertTrue(view.knownPieces().isEmpty());
    }

    @Test
    void existingBackgroundIsUpdatedWithoutCreatingAnother() {
        var existing = new LearningBackground(instrument);
        existing.update(ExperienceLevel.BEGINNER, 1, Set.of(), List.of(),
                Set.of(GoalCode.RHYTHM), 30, 2, "");
        when(backgrounds.findByInstrumentProfile_Id(instrument.getId()))
                .thenReturn(Optional.of(existing));
        when(backgrounds.saveAndFlush(any(LearningBackground.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new BackgroundRequest(
                ExperienceLevel.INTERMEDIATE, 4, Set.of(SkillTag.ARPEGGIOS),
                List.of(new KnownPieceInput("Yesterday", "The Beatles")),
                Set.of(GoalCode.FINGERSTYLE, GoalCode.RHYTHM), 45, 4, "Play by fingers");
        var result = service.upsert(learner.getId(), "ACOUSTIC_GUITAR", request);

        assertEquals(ExperienceLevel.INTERMEDIATE, result.experienceLevel());
        assertEquals(1, result.knownPieces().size());
        assertEquals(45, result.practiceMinutes());
    }
}
