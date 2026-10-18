package ai.guitarmentor.interfaces.rest.diagnostic;

import ai.guitarmentor.application.diagnostic.DiagnosticTaskService;
import ai.guitarmentor.interfaces.rest.diagnostic.TaskDtos.AssignRequest;
import ai.guitarmentor.interfaces.rest.diagnostic.TaskDtos.TaskView;
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
@RequestMapping("/api/v1/learners/{learnerId}/diagnostic-sessions/{sessionId}/tasks")
public class DiagnosticTaskController {
    private final DiagnosticTaskService service;

    public DiagnosticTaskController(DiagnosticTaskService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<TaskView> current(@PathVariable UUID learnerId,
            @PathVariable UUID sessionId) {
        return service.current(learnerId, sessionId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping
    public ResponseEntity<TaskView> assign(@PathVariable UUID learnerId,
            @PathVariable UUID sessionId, @Valid @RequestBody AssignRequest request) {
        boolean alreadyAssigned = service.current(learnerId, sessionId).isPresent();
        var task = service.assign(learnerId, sessionId, request.exerciseCode());
        if (alreadyAssigned) return ResponseEntity.ok(task);
        return ResponseEntity.created(URI.create("/api/v1/learners/" + learnerId
                + "/diagnostic-sessions/" + sessionId + "/tasks")).body(task);
    }
}
