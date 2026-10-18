package ai.guitarmentor.application.exercise;

import ai.guitarmentor.infrastructure.persistence.ExerciseRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Local MVP media. Exercise database rows store a version-specific resource path,
 * not the PDF/MP3 bytes. Swap this adapter for object storage later.
 */
@Service
public class ExerciseMediaService {
    private final ExerciseRepository exercises;

    public ExerciseMediaService(ExerciseRepository exercises) {
        this.exercises = exercises;
    }

    @Transactional(readOnly = true)
    public Resource get(String code, boolean audio) {
        var exercise = exercises.findByCodeAndCurrentTrue(code)
                .orElseThrow(() -> new EntityNotFoundException("Exercise not found"));
        String path = audio
                ? exercise.getPreviewAudioResource() : exercise.getPreviewPdfResource();
        if (path == null || !path.startsWith("exercises/")
                || path.contains("..") || path.startsWith("/")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Preview media is not configured");
        }
        var resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Preview media is not installed locally");
        }
        return resource;
    }
}
