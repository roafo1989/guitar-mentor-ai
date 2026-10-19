package ai.guitarmentor.interfaces.rest.diagnostic;

import ai.guitarmentor.application.diagnostic.DiagnosticRecommendationService;
import ai.guitarmentor.application.diagnostic.SkillMapService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only: never assigns a diagnostic task or confirms a skill. */
@RestController
@RequestMapping("/api/v1/learners/{learnerId}")
public class RecommendationController {
    private final DiagnosticRecommendationService recommendations;
    private final SkillMapService skillMap;

    public RecommendationController(DiagnosticRecommendationService recommendations,
            SkillMapService skillMap) {
        this.recommendations = recommendations;
        this.skillMap = skillMap;
    }

    @GetMapping("/diagnostic-sessions/{sessionId}/exercise-recommendations")
    public DiagnosticRecommendationService.RecommendationView recommend(
            @PathVariable UUID learnerId, @PathVariable UUID sessionId,
            @RequestParam(defaultValue = "3") int limit) {
        return recommendations.recommend(learnerId, sessionId, limit);
    }

    @GetMapping("/instruments/{instrumentCode}/skills")
    public List<SkillMapService.SkillView> skills(@PathVariable UUID learnerId,
            @PathVariable String instrumentCode) {
        return skillMap.map(learnerId, instrumentCode);
    }

    @GetMapping("/instruments/{instrumentCode}/skills/history")
    public List<SkillMapService.SignalView> history(@PathVariable UUID learnerId,
            @PathVariable String instrumentCode, @RequestParam String skillCode,
            @RequestParam(defaultValue = "20") int limit) {
        return skillMap.history(learnerId, instrumentCode, skillCode, limit);
    }
}
