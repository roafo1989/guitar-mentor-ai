package ai.guitarmentor.interfaces.rest.learner;

import ai.guitarmentor.application.learner.LearnerService;
import ai.guitarmentor.interfaces.rest.learner.LearnerDtos.CreateLearnerRequest;
import ai.guitarmentor.interfaces.rest.learner.LearnerDtos.LearnerView;
import ai.guitarmentor.interfaces.rest.learner.LearnerDtos.UpdatePreferencesRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/learners")
public class LearnerController {

    private final LearnerService learnerService;

    public LearnerController(LearnerService learnerService) {
        this.learnerService = learnerService;
    }

    @PostMapping
    public ResponseEntity<LearnerView> create(@Valid @RequestBody CreateLearnerRequest request) {
        var view = learnerService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/learners/" + view.id())).body(view);
    }

    @GetMapping("/{learnerId}")
    public LearnerView get(@PathVariable UUID learnerId) {
        return learnerService.get(learnerId);
    }

    @PutMapping("/{learnerId}/preferences")
    public LearnerView replacePreferences(
            @PathVariable UUID learnerId, @Valid @RequestBody UpdatePreferencesRequest request) {
        return learnerService.replacePreferences(learnerId, request);
    }
}
