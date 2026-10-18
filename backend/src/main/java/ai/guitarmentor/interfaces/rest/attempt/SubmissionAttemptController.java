package ai.guitarmentor.interfaces.rest.attempt;

import ai.guitarmentor.application.attempt.SubmissionAttemptService;
import ai.guitarmentor.infrastructure.media.LocalAttemptMediaStore;
import ai.guitarmentor.interfaces.rest.attempt.AttemptDtos.AttemptView;
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
    public UploadCapabilities capabilities(@PathVariable UUID learnerId,
            @PathVariable UUID sessionId, @PathVariable UUID taskId) {
        service.list(learnerId, sessionId, taskId); // Verify the task belongs to this learner.
        return new UploadCapabilities(service.isEnabled(), LocalAttemptMediaStore.MAX_BYTES,
                new String[] {"mp3", "wav", "ogg", "webm"});
    }

    @GetMapping
    public List<AttemptView> list(@PathVariable UUID learnerId,
            @PathVariable UUID sessionId, @PathVariable UUID taskId) {
        return service.list(learnerId, sessionId, taskId);
    }

    /**
     * A stable, allowlisted error code for the local attempt UI.
     * Never send internal filesystem paths or exception stack traces to a client.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> uploadError(ResponseStatusException problem) {
        String code = switch (problem.getReason() == null ? "" : problem.getReason()) {
            case "Audio format does not match the file header" -> "INVALID_AUDIO_CONTENT";
            case "Only .mp3, .wav, .ogg or .webm are supported" -> "UNSUPPORTED_AUDIO_FORMAT";
            case "Audio file must be between 1 byte and 20 MiB" -> "INVALID_AUDIO_SIZE";
            case "Audio filename is missing" -> "MISSING_AUDIO_FILENAME";
            case "Comment too long" -> "COMMENT_TOO_LONG";
            case "Uploads are disabled. Enable them only for local test files." -> "UPLOAD_DISABLED";
            default -> "UPLOAD_REJECTED";
        };
        return ResponseEntity.status(problem.getStatusCode()).body(Map.of("code", code));
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
