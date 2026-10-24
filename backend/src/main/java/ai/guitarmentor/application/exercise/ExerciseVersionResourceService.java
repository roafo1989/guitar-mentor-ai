package ai.guitarmentor.application.exercise;

import ai.guitarmentor.domain.exercise.Exercise;
import ai.guitarmentor.infrastructure.persistence.ExerciseRepository;
import java.io.IOException;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Version-pinned, classpath-only resource resolution; never falls back to current. */
@Service
public class ExerciseVersionResourceService {
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,49}");
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,19}");
    private final ExerciseRepository repository;

    public ExerciseVersionResourceService(ExerciseRepository repository) {
        this.repository = repository;
    }

    public static void validate(String code, String version) {
        if (code == null || version == null || !CODE.matcher(code).matches()
                || !VERSION.matcher(version).matches()) {
            throw failure(HttpStatus.BAD_REQUEST, "INVALID_EXERCISE_REFERENCE",
                    "Invalid exercise reference");
        }
    }

    private static ExerciseReferenceException failure(HttpStatus status, String code, String text) {
        return new ExerciseReferenceException(status, code, text);
    }

    @Transactional(readOnly = true)
    public Exercise find(String code, String version) {
        validate(code, version);
        return repository.findByCodeAndVersion(code, version)
                .orElseThrow(() -> failure(HttpStatus.NOT_FOUND, "EXERCISE_NOT_FOUND",
                        "Exercise not found"));
    }

    /** Resource existence is checked for HEAD without opening a stream. */
    @Transactional(readOnly = true)
    public ClassPathResource locate(String code, String version, Kind kind) {
        var exercise = find(code, version);
        if ("DIAGNOSTIC_PROBE".equals(exercise.getContentKind())
                && !ExercisePublicationPolicy.eligible(exercise)) {
            throw failure(HttpStatus.NOT_FOUND, "EXERCISE_RESOURCE_NOT_FOUND",
                    "Exercise resource not found");
        }
        String path = switch (kind) {
            case SCORE -> exercise.getScoreResource();
            case EVENTS -> exercise.getEventsResource();
            case AUDIO -> exercise.getPreviewAudioResource();
            case PDF -> exercise.getPreviewPdfResource();
        };
        // Paths are migration-controlled but still treated as untrusted at the IO boundary.
        if (path == null || !path.startsWith("exercises/") || path.length() <= 10
                || path.indexOf('\\') >= 0 || path.indexOf('%') >= 0
                || path.indexOf(':') >= 0 || path.indexOf('?') >= 0
                || path.indexOf('#') >= 0 || path.indexOf('\0') >= 0
                || java.util.Arrays.stream(path.split("/", -1))
                        .anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."))) {
            throw failure(HttpStatus.NOT_FOUND, "EXERCISE_RESOURCE_NOT_FOUND",
                    "Exercise resource not found");
        }
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists() || !resource.isReadable()) {
            throw failure(HttpStatus.NOT_FOUND, "EXERCISE_RESOURCE_NOT_FOUND",
                    "Exercise resource not found");
        }
        return resource;
    }

    public byte[] read(String code, String version, Kind kind) {
        var resource = locate(code, version, kind);
        try {
            return readBytes(resource);
        } catch (IOException e) {
            throw failure(HttpStatus.INTERNAL_SERVER_ERROR, "EXERCISE_RESOURCE_READ_FAILED",
                    "Unable to read exercise resource");
        }
    }

    // Narrow IO seam: allows a deterministic IOException regression without a real broken filesystem.
    byte[] readBytes(ClassPathResource resource) throws IOException {
        return resource.getContentAsByteArray();
    }

    public enum Kind { SCORE, EVENTS, AUDIO, PDF }
}
