package ai.guitarmentor.application.onboarding;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.any;

import ai.guitarmentor.domain.learner.InstrumentProfile;
import ai.guitarmentor.domain.learner.Learner;
import ai.guitarmentor.domain.onboarding.ExperienceLevel;
import ai.guitarmentor.domain.onboarding.GoalCode;
import ai.guitarmentor.domain.onboarding.LearningBackground;
import ai.guitarmentor.domain.onboarding.SkillTag;
import ai.guitarmentor.infrastructure.persistence.LearnerRepository;
import ai.guitarmentor.infrastructure.persistence.LearningBackgroundRepository;
import ai.guitarmentor.infrastructure.persistence.SkillMapRepository;
import ai.guitarmentor.interfaces.rest.onboarding.OnboardingDtos.BackgroundRequest;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OnboardingSkillSignalTest {
    @Mock LearnerRepository learners;
    @Mock LearningBackgroundRepository backgrounds;
    @Mock SkillMapRepository skills;

    private static BackgroundRequest request(Set<SkillTag> tags) {
        return new BackgroundRequest(ExperienceLevel.BEGINNER, 0, tags, List.of(),
                Set.of(GoalCode.FINGERSTYLE), 30, 3, "");
    }

    @Test
    void onlyChangesCreateHistoricalClaimEvents() {
        var learner = new Learner("Student");
        var instrument = new InstrumentProfile("ACOUSTIC_GUITAR", "FINGERSTYLE");
        learner.addInstrumentProfile(instrument);
        var background = new LearningBackground(instrument);
        when(learners.findLockedById(learner.getId())).thenReturn(Optional.of(learner));
        when(backgrounds.findByInstrumentProfile_Id(instrument.getId()))
                .thenReturn(Optional.of(background));
        when(backgrounds.saveAndFlush(any(LearningBackground.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        var service = new OnboardingService(learners, backgrounds, skills);

        service.upsert(learner.getId(), "ACOUSTIC_GUITAR", request(Set.of(SkillTag.FINGERSTYLE_MELODY)));
        service.upsert(learner.getId(), "ACOUSTIC_GUITAR", request(Set.of(SkillTag.FINGERSTYLE_MELODY)));
        service.upsert(learner.getId(), "ACOUSTIC_GUITAR", request(Set.of()));

        verify(skills, times(1)).appendOnboardingClaim(instrument.getId(), "MELODY", true);
        verify(skills, times(1)).appendOnboardingClaim(instrument.getId(), "MELODY", false);
    }
}
