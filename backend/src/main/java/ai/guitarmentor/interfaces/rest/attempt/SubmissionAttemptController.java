package ai.guitarmentor.interfaces.rest.attempt;

import ai.guitarmentor.application.attempt.SubmissionAttemptService;
import ai.guitarmentor.infrastructure.media.LocalAttemptMediaStore;
import ai.guitarmentor.interfaces.rest.attempt.AttemptDtos.AttemptView;
import ai.guitarmentor.interfaces.rest.attempt.AttemptDtos.UploadCapabilities;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
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
