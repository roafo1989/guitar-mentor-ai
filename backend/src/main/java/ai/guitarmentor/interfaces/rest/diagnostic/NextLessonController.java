package ai.guitarmentor.interfaces.rest.diagnostic;

import ai.guitarmentor.application.diagnostic.NextLessonService;
import ai.guitarmentor.application.learning.LearningProblem;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/learners/{learnerId}")
public class NextLessonController {
    private final NextLessonService service;
    public NextLessonController(NextLessonService service) { this.service=service; }

    @PostMapping("/diagnostic-sessions/{sessionId}/tasks/{taskId}/next-lesson")
    public ResponseEntity<NextLessonService.Result> next(
            @PathVariable UUID learnerId,@PathVariable UUID sessionId,@PathVariable UUID taskId,
            @RequestBody NextLessonService.Request request) {
        var created=service.start(learnerId,sessionId,taskId,request);
        return ResponseEntity.status(created.replay()?HttpStatus.OK:HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore())
            .location(URI.create("/api/v1/learners/"+learnerId+
                "/diagnostic-sessions/"+created.result().session().id()))
            .body(created.result());
    }

    @GetMapping("/lessons")
    public ResponseEntity<NextLessonService.HistoryPage> history(@PathVariable UUID learnerId,
            @RequestParam String instrumentCode,@RequestParam(required=false) Integer limit,
            @RequestParam(required=false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.history(learnerId,instrumentCode,limit,cursor));
    }

    @RestControllerAdvice(assignableTypes=NextLessonController.class)
    public static class Handler {
        @ExceptionHandler(LearningProblem.class)
        public ResponseEntity<Map<String,String>> failure(LearningProblem p) {
            return ResponseEntity.status(p.status()).body(Map.of("code",p.code()));
        }
    }
}
