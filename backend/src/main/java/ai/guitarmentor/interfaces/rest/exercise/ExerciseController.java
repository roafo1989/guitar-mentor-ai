package ai.guitarmentor.interfaces.rest.exercise;

import ai.guitarmentor.application.exercise.ExerciseCatalogService;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/exercises")
public class ExerciseController {
    private final ExerciseCatalogService service;

    public ExerciseController(ExerciseCatalogService service) {
        this.service = service;
    }

    @GetMapping
    public List<ExerciseView> list(
            @RequestParam @NotBlank String instrumentCode,
            @RequestParam @NotBlank String techniqueCode) {
        return service.available(instrumentCode, techniqueCode);
    }

    @GetMapping("/{code}")
    public ExerciseView one(@PathVariable String code) {
        return service.find(code);
    }

    @GetMapping(value = "/{code}/score", produces = "application/vnd.recordare.musicxml+xml")
    public ResponseEntity<byte[]> score(@PathVariable String code) throws IOException {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.recordare.musicxml+xml"))
                .body(service.resource(code, true));
    }

    @GetMapping(value = "/{code}/events", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> events(@PathVariable String code) throws IOException {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(service.resource(code, false));
    }
}
