package ai.guitarmentor.application.exercise;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.guitarmentor.domain.exercise.Exercise;
import ai.guitarmentor.infrastructure.persistence.ExerciseRepository;
import ai.guitarmentor.interfaces.rest.exercise.ExerciseView;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ExerciseVersionResourceServiceTest {
    private final ExerciseRepository repository = mock(ExerciseRepository.class);
    private final ExerciseVersionResourceService service = new ExerciseVersionResourceService(repository);

    @Test
    void exactReferenceDoesNotFallBackToCurrent() {
        when(repository.findByCodeAndVersion("GM-EB01", "v1")).thenReturn(Optional.empty());
        var failure = assertThrows(ExerciseReferenceException.class,
                () -> service.find("GM-EB01", "v1"));
        assertEquals(HttpStatus.NOT_FOUND, failure.status());
        assertEquals("EXERCISE_NOT_FOUND", failure.code());
        verify(repository, never()).findByCodeAndCurrentTrue(anyString());
    }

    @Test
    void validationRejectsUnsafeSegmentsBeforeDatabaseLookup() {
        for (String invalid : List.of("..", ".", "bad/name", "bad\\name", "bad%2Fname",
                "https:", "a b", "X".repeat(51))) {
            var failure = assertThrows(ExerciseReferenceException.class,
                    () -> service.find(invalid, "v1"));
            assertEquals("INVALID_EXERCISE_REFERENCE", failure.code());
        }
        for (String invalid : List.of(".", "..", "v/1", "v%2F1", "v\\1",
                "v 1", "X".repeat(21))) {
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ExerciseReferenceException.class,
                            () -> service.find("GM-EB01", invalid)).status());
        }
        verifyNoInteractions(repository);
        assertDoesNotThrow(() -> ExerciseVersionResourceService.validate("GM-EB01", "0.8B"));
    }

    @Test
    void missingAndUnsafeResourcesFailWithSafe404AndNoCurrentLookup() {
        var old = mock(Exercise.class);
        when(old.getScoreResource()).thenReturn("exercises/../application.yml");
        when(old.getEventsResource()).thenReturn("exercises/GM-EB01/nonexistent.json");
        when(old.getPreviewPdfResource()).thenReturn(null);
        when(repository.findByCodeAndVersion("GM-EB01", "v1")).thenReturn(Optional.of(old));
        for (var kind : List.of(ExerciseVersionResourceService.Kind.SCORE,
                ExerciseVersionResourceService.Kind.EVENTS,
                ExerciseVersionResourceService.Kind.PDF)) {
            var failure = assertThrows(ExerciseReferenceException.class,
                    () -> service.locate("GM-EB01", "v1", kind));
            assertEquals("EXERCISE_RESOURCE_NOT_FOUND", failure.code());
            assertEquals(HttpStatus.NOT_FOUND, failure.status());
        }
        verify(repository, never()).findByCodeAndCurrentTrue(anyString());
    }

    @Test
    void pinnedScoreAndEventsReturnExactClasspathBytes() {
        var assigned = mock(Exercise.class);
        when(assigned.getScoreResource()).thenReturn("exercises/GM-EB01/evening_blues_v08B.musicxml");
        when(assigned.getEventsResource()).thenReturn("exercises/GM-EB01/score_events_v08B.json");
        when(repository.findByCodeAndVersion("GM-EB01", "0.8B")).thenReturn(Optional.of(assigned));
        assertTrue(service.read("GM-EB01", "0.8B",
                ExerciseVersionResourceService.Kind.SCORE).length > 100);
        assertTrue(service.read("GM-EB01", "0.8B",
                ExerciseVersionResourceService.Kind.EVENTS).length > 100);
    }

    @Test
    void dtoPinsAllFourUrlsButPreservesNullablePreviewUrls() {
        var old = mock(Exercise.class);
        when(old.getCode()).thenReturn("GM-EB01");
        when(old.getVersion()).thenReturn("v1");
        when(old.getSkillCodes()).thenReturn(Set.of());
        when(old.getPreviewAudioResource()).thenReturn("exercises/example.mp3");
        var view = ExerciseView.from(old, List.of());
        String prefix = "/api/v1/exercises/GM-EB01/versions/v1";
        assertEquals(prefix + "/score", view.scoreUrl());
        assertEquals(prefix + "/events", view.eventsUrl());
        assertEquals(prefix + "/preview/audio", view.previewAudioUrl());
        assertNull(view.previewPdfUrl());
    }
}
