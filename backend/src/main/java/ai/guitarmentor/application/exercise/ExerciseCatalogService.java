package ai.guitarmentor.application.exercise;

import ai.guitarmentor.domain.exercise.Exercise;
import ai.guitarmentor.infrastructure.persistence.ExerciseRepository;
import ai.guitarmentor.interfaces.rest.exercise.ExerciseView;
import jakarta.persistence.EntityNotFoundException;
import java.io.IOException;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExerciseCatalogService {
    private final ExerciseRepository exercises;
    private final ExerciseViewAssembler assembler;

    public ExerciseCatalogService(ExerciseRepository exercises, ExerciseViewAssembler assembler) {
        this.exercises = exercises;
        this.assembler = assembler;
    }

    @Transactional(readOnly = true)
    public List<ExerciseView> available(String instrumentCode, String techniqueCode) {
        return assembler.many(exercises.findByInstrumentCodeAndTechniqueCodeAndCurrentTrueOrderByCode(
                instrumentCode, techniqueCode).stream()
                .filter(ExercisePublicationPolicy::eligible).toList());
    }

    @Transactional(readOnly = true)
    public ExerciseView find(String code) {
        var exercise = exercises.findByCodeAndCurrentTrue(code)
                .orElseThrow(() -> new EntityNotFoundException("Exercise not found"));
        if (!ExercisePublicationPolicy.eligible(exercise))
            throw new EntityNotFoundException("Exercise not found");
        return assembler.one(exercise);
    }

    @Transactional(readOnly = true)
    public ExerciseView findVersion(String code, String version) {
        var exercise = exercises.findByCodeAndVersion(code, version)
                .orElseThrow(() -> new ai.guitarmentor.application.exercise.ExerciseReferenceException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "EXERCISE_NOT_FOUND", "Exercise not found"));
        if ("DIAGNOSTIC_PROBE".equals(exercise.getContentKind())
                && !ExercisePublicationPolicy.eligible(exercise))
            throw new ai.guitarmentor.application.exercise.ExerciseReferenceException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "EXERCISE_NOT_FOUND", "Exercise not found");
        return assembler.one(exercise);
    }

    @Transactional(readOnly = true)
    public byte[] resource(String code, boolean score) throws IOException {
        Exercise exercise = exercises.findByCodeAndCurrentTrue(code)
                .orElseThrow(() -> new EntityNotFoundException("Exercise not found"));
        // Only trusted resource paths stored by migration, never client-supplied paths.
        var path = score ? exercise.getScoreResource() : exercise.getEventsResource();
        if (!path.startsWith("exercises/") || path.contains("..")) {
            throw new IllegalStateException("Invalid exercise resource path");
        }
        return new ClassPathResource(path).getContentAsByteArray();
    }
}
