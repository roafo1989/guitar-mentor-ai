package ai.guitarmentor.application.learner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import ai.guitarmentor.domain.learner.Learner;
import ai.guitarmentor.domain.learner.PreferenceRating;
import ai.guitarmentor.infrastructure.persistence.LearnerRepository;
import ai.guitarmentor.interfaces.rest.learner.LearnerDtos.CreateLearnerRequest;
import ai.guitarmentor.interfaces.rest.learner.LearnerDtos.PreferenceInput;
import ai.guitarmentor.interfaces.rest.learner.LearnerDtos.UpdatePreferencesRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LearnerServiceTest {

    @Mock
    private LearnerRepository learnerRepository;

    private LearnerService service;

    @BeforeEach
    void setUp() {
        service = new LearnerService(learnerRepository);
    }

    @Test
    void createsLearnerWithInstrumentAndExcludedStyle() {
        when(learnerRepository.saveAndFlush(any(Learner.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.create(new CreateLearnerRequest(
                "Student", "ACOUSTIC_GUITAR", "FINGERSTYLE",
                List.of(new PreferenceInput("FUNK", PreferenceRating.EXCLUDE))));

        assertEquals("Student", result.displayName());
        assertEquals("ACOUSTIC_GUITAR", result.instruments().getFirst().instrumentCode());
        assertEquals(PreferenceRating.EXCLUDE,
                result.instruments().getFirst().preferences().getFirst().rating());
    }

    @Test
    void rejectsDuplicateStyles() {
        var preferences = List.of(
                new PreferenceInput("BLUES", PreferenceRating.LIKE),
                new PreferenceInput("BLUES", PreferenceRating.EXCLUDE));

        assertThrows(IllegalArgumentException.class,
                () -> service.create(new CreateLearnerRequest(
                        "Student", "ACOUSTIC_GUITAR", "FINGERSTYLE", preferences)));
    }

    @Test
    void replacesPreferencesWithoutLosingInstrumentProfile() {
        var learner = new Learner("Student");
        var instrument = new ai.guitarmentor.domain.learner.InstrumentProfile(
                "ACOUSTIC_GUITAR", "FINGERSTYLE");
        instrument.replacePreferences(List.of(
                new ai.guitarmentor.domain.learner.MusicPreference(
                        "BLUES", PreferenceRating.LIKE)));
        learner.addInstrumentProfile(instrument);
        when(learnerRepository.findLockedById(learner.getId())).thenReturn(Optional.of(learner));
        when(learnerRepository.saveAndFlush(any(Learner.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.replacePreferences(learner.getId(),
                new UpdatePreferencesRequest("ACOUSTIC_GUITAR",
                        List.of(new PreferenceInput("JAZZ", PreferenceRating.NEUTRAL))));

        assertEquals("FINGERSTYLE", result.instruments().getFirst().techniqueCode());
        assertEquals("JAZZ", result.instruments().getFirst().preferences().getFirst().styleCode());
        assertEquals(1, result.instruments().getFirst().preferences().size());
    }
    @Test
    void publicSignupBareLearnerCanAddFirstInstrumentWithoutCreatingAnotherLearner() {
        var learner = new Learner("new-student");
        when(learnerRepository.findLockedById(learner.getId())).thenReturn(Optional.of(learner));
        when(learnerRepository.saveAndFlush(any(Learner.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        var request = new UpdatePreferencesRequest("ACOUSTIC_GUITAR",
                List.of(new PreferenceInput("BLUES", PreferenceRating.LIKE)));
        var result = service.replacePreferences(learner.getId(), request);
        assertEquals(learner.getId(), result.id());
        assertEquals(1, result.instruments().size());
        assertEquals("FINGERSTYLE", result.instruments().getFirst().techniqueCode());
        assertEquals("BLUES", result.instruments().getFirst().preferences().getFirst().styleCode());
        var repeated = service.replacePreferences(learner.getId(), request);
        assertEquals(1, repeated.instruments().size());
    }

    @Test
    void bareLearnerRejectsUnsupportedFirstInstrument() {
        var learner = new Learner("new-student");
        when(learnerRepository.findLockedById(learner.getId())).thenReturn(Optional.of(learner));
        assertThrows(jakarta.persistence.EntityNotFoundException.class,
                () -> service.replacePreferences(learner.getId(),
                    new UpdatePreferencesRequest("DRUMS", List.of())));
    }

}
