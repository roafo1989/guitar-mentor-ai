package ai.guitarmentor.interfaces.rest.diagnostic;

import ai.guitarmentor.application.diagnostic.DiagnosticSessionService;
import ai.guitarmentor.interfaces.rest.diagnostic.DiagnosticDtos.SessionView;
import ai.guitarmentor.interfaces.rest.diagnostic.DiagnosticDtos.StartRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/learners/{learnerId}/diagnostic-sessions")
public class DiagnosticSessionController {

    private final DiagnosticSessionService service;

    public DiagnosticSessionController(DiagnosticSessionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<SessionView> start(
            @PathVariable UUID learnerId, @Valid @RequestBody StartRequest request) {
        var result = service.start(learnerId, request.instrumentCode());
        if (result.created()) {
            return ResponseEntity.created(URI.create("/api/v1/learners/" + learnerId
                    + "/diagnostic-sessions/" + result.session().id())).body(result.session());
        }
        return ResponseEntity.ok(result.session());
    }

    @GetMapping("/{sessionId}")
    public SessionView get(@PathVariable UUID learnerId, @PathVariable UUID sessionId) {
        return service.get(learnerId, sessionId);
    }

    @PostMapping("/{sessionId}/continue")
    public SessionView finishIntake(@PathVariable UUID learnerId, @PathVariable UUID sessionId) {
        return service.finishIntake(learnerId, sessionId);
    }
}
