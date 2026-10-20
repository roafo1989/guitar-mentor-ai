package ai.guitarmentor.interfaces.rest.evidence;

import ai.guitarmentor.application.evidence.DiagnosticEvidenceService;
import ai.guitarmentor.application.evidence.EvidenceDtos.Capabilities;
import ai.guitarmentor.application.evidence.EvidenceDtos.EvidenceView;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/learners/{learnerId}/diagnostic-sessions/{sessionId}/evidence")
public class DiagnosticEvidenceController {
    private static final Set<String> SAFE_CODES = Set.of("UPLOAD_DISABLED", "INVALID_REQUEST_ID",
            "SESSION_NOT_FOUND", "EVIDENCE_NOT_FOUND", "INVALID_SESSION_STATE", "MAX_ITEMS_REACHED", "INVALID_TITLE",
            "INVALID_COMPOSER", "INVALID_COMMENT", "MISSING_AUDIO", "INVALID_SCORE_SIZE",
            "UNSUPPORTED_SCORE_FORMAT", "INVALID_SCORE_CONTENT", "STORAGE_UNAVAILABLE",
            "INVALID_AUDIO_SIZE", "INVALID_AUDIO_CONTENT", "UNSUPPORTED_AUDIO_FORMAT");
    private final DiagnosticEvidenceService service;
    public DiagnosticEvidenceController(DiagnosticEvidenceService service) {
        this.service = service;
    }
    @GetMapping("/capabilities")
    public Capabilities capabilities(@PathVariable UUID learnerId, @PathVariable UUID sessionId) {
        return service.capabilities(learnerId, sessionId);
    }
    @GetMapping
    public List<EvidenceView> list(@PathVariable UUID learnerId, @PathVariable UUID sessionId) {
        return service.list(learnerId, sessionId);
    }
    @GetMapping("/{evidenceId}")
    public EvidenceView get(@PathVariable UUID learnerId, @PathVariable UUID sessionId,
            @PathVariable UUID evidenceId) {
        return service.get(learnerId, sessionId, evidenceId);
    }
    @PostMapping(consumes="multipart/form-data")
    public ResponseEntity<EvidenceView> upload(@PathVariable UUID learnerId,
            @PathVariable UUID sessionId, @RequestParam UUID requestId,
            @RequestParam String title,
            @RequestParam(required=false) String composer,
            @RequestParam(required=false) String comment,
            @RequestPart("audio") MultipartFile audio,
            @RequestPart(value="score",required=false) MultipartFile score) {
        var result = service.submit(learnerId, sessionId, requestId, title, composer,
                comment, audio, score);
        if (!result.created()) return ResponseEntity.ok(result.evidence());
        return ResponseEntity.created(URI.create("/api/v1/learners/" + learnerId
                + "/diagnostic-sessions/" + sessionId + "/evidence/" + result.evidence().id()))
                .body(result.evidence());
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> onUploadError(ResponseStatusException error) {
        String reason = error.getReason();
        String code = reason != null && SAFE_CODES.contains(reason) ? reason : switch (reason == null ? "" : reason) {
            case "Audio format does not match the file header" -> "INVALID_AUDIO_CONTENT";
            case "Only .mp3, .wav, .ogg, .webm or .m4a are supported" -> "UNSUPPORTED_AUDIO_FORMAT";
            case "Audio file must be between 1 byte and 20 MiB" -> "INVALID_AUDIO_SIZE";
            default -> "UPLOAD_REJECTED";
        };
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("code",code));
    }
}
