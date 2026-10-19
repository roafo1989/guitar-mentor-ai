package ai.guitarmentor.interfaces.rest.diagnostic;

import ai.guitarmentor.interfaces.rest.exercise.ExerciseController;
import ai.guitarmentor.application.diagnostic.InvalidSkillGraphException;
import ai.guitarmentor.application.diagnostic.RecommendationStateException;
import jakarta.persistence.EntityNotFoundException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {
        DiagnosticSessionController.class, DiagnosticTaskController.class,
        RecommendationController.class, ExerciseController.class})
public class DiagnosticSessionExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(EntityNotFoundException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", error.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
    }

    @ExceptionHandler(InvalidSkillGraphException.class)
    public ResponseEntity<Map<String, String>> corruptSkillGraph(InvalidSkillGraphException error) {
        // Do not echo graph internals to the client.
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("code", "RECOMMENDATION_INTERNAL_ERROR",
                             "error", "Unable to build skill recommendations"));
    }

    @ExceptionHandler(RecommendationStateException.class)
    public ResponseEntity<Map<String, String>> recommendationState(RecommendationStateException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", error.code(), "error", error.code()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> invalidTransition(IllegalStateException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", error.getMessage()));
    }
}
