package ai.guitarmentor.interfaces.rest.learning;

import ai.guitarmentor.application.learning.LearningDtos.*;
import ai.guitarmentor.application.learning.LearningProblem;
import ai.guitarmentor.application.learning.LearningService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestController
@RequestMapping("/api/v1/learners/{learnerId}/diagnostic-sessions/{sessionId}/tasks/{taskId}")
public class LearningController {
    private final LearningService service;
    public LearningController(LearningService service) { this.service=service; }

    @GetMapping("/learning")
    public LearningView learning(@PathVariable UUID learnerId,@PathVariable UUID sessionId,
            @PathVariable UUID taskId) {
        return service.learning(learnerId,sessionId,taskId);
    }

    @GetMapping("/attempts/{attemptId}/assessments")
    public Page<AssessmentView> assessments(@PathVariable UUID learnerId,
            @PathVariable UUID sessionId,@PathVariable UUID taskId,
            @PathVariable UUID attemptId,@RequestParam(required=false) Integer limit,
            @RequestParam(required=false) String cursor) {
        return service.assessments(learnerId,sessionId,taskId,attemptId,limit,cursor);
    }

    @GetMapping("/decisions")
    public Page<DecisionView> decisions(@PathVariable UUID learnerId,
            @PathVariable UUID sessionId,@PathVariable UUID taskId,
            @RequestParam(required=false) Integer limit,
            @RequestParam(required=false) String cursor) {
        return service.decisions(learnerId,sessionId,taskId,limit,cursor);
    }

    @GetMapping("/learning/evidence")
    public Page<SkillEvidenceView> evidence(@PathVariable UUID learnerId,
            @PathVariable UUID sessionId,@PathVariable UUID taskId,
            @RequestParam(required=false) Integer limit,
            @RequestParam(required=false) String cursor) {
        return service.evidence(learnerId,sessionId,taskId,limit,cursor);
    }

    @PostMapping("/decisions")
    public ResponseEntity<DecisionResult> decide(@PathVariable UUID learnerId,
            @PathVariable UUID sessionId,@PathVariable UUID taskId,
            @RequestBody DecisionRequest command) {
        var created=service.decide(learnerId,sessionId,taskId,command);
        return ResponseEntity.status(created.replay()?HttpStatus.OK:HttpStatus.CREATED)
                .body(created.result());
    }

    @RestControllerAdvice(assignableTypes=LearningController.class)
    public static class LearningExceptionHandler {
        @ExceptionHandler(LearningProblem.class)
        public ResponseEntity<Map<String,String>> problem(LearningProblem ex) {
            return ResponseEntity.status(ex.status()).body(Map.of("code",ex.code()));
        }
        @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
        public ResponseEntity<Map<String,String>> invalidId(
                org.springframework.web.method.annotation.MethodArgumentTypeMismatchException error) {
            return ResponseEntity.badRequest().body(Map.of("code",
                "limit".equals(error.getName()) ? "INVALID_PAGINATION" : "INVALID_DECISION"));
        }
        @ExceptionHandler(Exception.class)
        public ResponseEntity<Map<String,String>> internalError(Exception error) {
            // Never echo SQL errors, audio paths, comments or stack traces to a client.
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code","LEARNING_INTERNAL_ERROR"));
        }
        @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
        public ResponseEntity<Map<String,String>> invalidBody() {
            return ResponseEntity.badRequest().body(Map.of("code","INVALID_DECISION"));
        }
    }
}
