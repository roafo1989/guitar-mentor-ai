package ai.guitarmentor.interfaces.rest.attempt;

import ai.guitarmentor.application.attempt.SubmissionAttemptService;
import ai.guitarmentor.application.attempt.AttemptMediaStore;
import ai.guitarmentor.interfaces.rest.attempt.AttemptDtos.AttemptView;
import ai.guitarmentor.interfaces.rest.attempt.AttemptDtos.AttemptPage;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import ai.guitarmentor.interfaces.rest.attempt.AttemptDtos.UploadCapabilities;
import java.net.URI;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/learners/{learnerId}/diagnostic-sessions/{sessionId}/tasks/{taskId}/attempts")
public class SubmissionAttemptController {
    private final SubmissionAttemptService service;

    public SubmissionAttemptController(SubmissionAttemptService service) {
        this.service = service;
    }

    @GetMapping("/capabilities")
    public ResponseEntity<UploadCapabilities> capabilities(@PathVariable UUID learnerId,
            @PathVariable UUID sessionId, @PathVariable UUID taskId) {
        service.verifyOwnership(learnerId, sessionId, taskId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                new UploadCapabilities(service.isEnabled(), AttemptMediaStore.MAX_BYTES,
                    new String[] {"mp3", "wav", "ogg", "webm", "m4a"}));
    }

    @GetMapping
    public List<AttemptView> list(@PathVariable UUID learnerId,
            @PathVariable UUID sessionId, @PathVariable UUID taskId) {
        return service.list(learnerId, sessionId, taskId);
    }

    @GetMapping("/page")
    public ResponseEntity<?> page(@PathVariable UUID learnerId,
            @PathVariable UUID sessionId, @PathVariable UUID taskId,
            @RequestParam MultiValueMap<String, String> query) {
        final int limit;
        final Integer before;
        try {
            limit = parsePositive(query, "limit", 20, 50);
            before = query.containsKey("beforeNumber")
                    ? parsePositive(query, "beforeNumber", null, Integer.MAX_VALUE) : null;
        } catch (IllegalArgumentException invalid) {
            return safeError(HttpStatus.BAD_REQUEST, "INVALID_ATTEMPT_PAGE");
        }
        try {
            AttemptPage result = service.page(learnerId, sessionId, taskId, limit, before);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
        } catch (jakarta.persistence.EntityNotFoundException missing) {
            return safeError(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        } catch (RuntimeException unexpected) {
            return safeError(HttpStatus.INTERNAL_SERVER_ERROR, "ATTEMPT_READ_FAILED");
        }
    }

    @GetMapping("/{attemptId}")
    public ResponseEntity<?> detail(@PathVariable UUID learnerId,
            @PathVariable UUID sessionId, @PathVariable UUID taskId,
            @PathVariable String attemptId) {
        UUID id;
        try {
            id = UUID.fromString(attemptId);
        } catch (IllegalArgumentException invalid) {
            return safeError(HttpStatus.BAD_REQUEST, "INVALID_ATTEMPT_REFERENCE");
        }
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(service.detail(learnerId, sessionId, taskId, id));
        } catch (jakarta.persistence.EntityNotFoundException missing) {
            return safeError(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        } catch (RuntimeException unexpected) {
            return safeError(HttpStatus.INTERNAL_SERVER_ERROR, "ATTEMPT_READ_FAILED");
        }
    }

    private static Integer parsePositive(MultiValueMap<String, String> params,
            String name, Integer fallback, int max) {
        if (!params.containsKey(name)) return fallback;
        List<String> values = params.get(name);
        if (values == null || values.size() != 1) throw new IllegalArgumentException();
        String value = values.get(0);
        if (value == null || !value.matches("[0-9]+")) throw new IllegalArgumentException();
        try {
            int number = Integer.parseInt(value);
            if (number < 1 || number > max) throw new IllegalArgumentException();
            return number;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(invalid);
        }
    }

    private static ResponseEntity<Map<String, String>> safeError(HttpStatus status, String code) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("code", code));
    }

    /**
     * A stable, allowlisted error code for the local attempt UI.
     * Never send internal filesystem paths or exception stack traces to a client.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> uploadError(ResponseStatusException problem) {
        String code = switch (problem.getReason() == null ? "" : problem.getReason()) {
            case "Audio format does not match the file header" -> "INVALID_AUDIO_CONTENT";
            case "Only .mp3, .wav, .ogg, .webm or .m4a are supported" -> "UNSUPPORTED_AUDIO_FORMAT";
            case "Audio file must be between 1 byte and 20 MiB" -> "INVALID_AUDIO_SIZE";
            case "Audio filename is missing" -> "MISSING_AUDIO_FILENAME";
            case "Comment too long" -> "COMMENT_TOO_LONG";
            case "TASK_COMPLETED" -> "TASK_COMPLETED";
            case "Uploads are disabled. Enable them only for local test files." -> "UPLOAD_DISABLED";
            default -> "UPLOAD_REJECTED";
        };
        return ResponseEntity.status(problem.getStatusCode()).body(Map.of("code", code));
    }

    @ExceptionHandler(jakarta.persistence.EntityNotFoundException.class)
    public ResponseEntity<Map<String,String>> missingTask() {
        return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND)
                .body(Map.of("code","RESOURCE_NOT_FOUND"));
    }

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<AttemptView> upload(@PathVariable UUID learnerId,
            @PathVariable UUID sessionId, @PathVariable UUID taskId,
            @RequestParam UUID requestId, @RequestParam(required = false) String comment,
            @RequestPart("file") MultipartFile file) {
        var result = service.submit(learnerId, sessionId, taskId, requestId, comment, file);
        return ResponseEntity.created(URI.create("/api/v1/learners/" + learnerId
                + "/diagnostic-sessions/" + sessionId + "/tasks/" + taskId
                + "/attempts")).body(result);
    }
}
