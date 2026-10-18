package ai.guitarmentor.interfaces.rest.onboarding;

import ai.guitarmentor.application.onboarding.OnboardingService;
import ai.guitarmentor.interfaces.rest.onboarding.OnboardingDtos.BackgroundRequest;
import ai.guitarmentor.interfaces.rest.onboarding.OnboardingDtos.BackgroundView;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/learners/{learnerId}/instruments/{instrumentCode}/background")
public class OnboardingController {

    private final OnboardingService service;

    public OnboardingController(OnboardingService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<BackgroundView> get(
            @PathVariable UUID learnerId, @PathVariable String instrumentCode) {
        return service.get(learnerId, instrumentCode)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PutMapping
    public BackgroundView upsert(
            @PathVariable UUID learnerId, @PathVariable String instrumentCode,
            @Valid @RequestBody BackgroundRequest request) {
        return service.upsert(learnerId, instrumentCode, request);
    }
}
