package ai.guitarmentor.application.diagnostic;

import ai.guitarmentor.domain.diagnostic.DiagnosticStatus;
import ai.guitarmentor.domain.learner.PreferenceRating;
import ai.guitarmentor.domain.onboarding.GoalCode;
import ai.guitarmentor.infrastructure.persistence.DiagnosticSessionRepository;
import ai.guitarmentor.infrastructure.persistence.ExerciseRepository;
import ai.guitarmentor.infrastructure.persistence.LearnerRepository;
import ai.guitarmentor.infrastructure.persistence.LearningBackgroundRepository;
import ai.guitarmentor.infrastructure.persistence.SkillMapRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DiagnosticRecommendationService {
    private final DiagnosticSessionRepository sessions;
    private final LearnerRepository learners;
    private final LearningBackgroundRepository backgrounds;
    private final ExerciseRepository exercises;
    private final SkillMapRepository skills;
    private final SkillMapService skillMap;
    private final RecommendationEngine engine = new RecommendationEngine();

    public DiagnosticRecommendationService(DiagnosticSessionRepository sessions,
            LearnerRepository learners, LearningBackgroundRepository backgrounds,
            ExerciseRepository exercises, SkillMapRepository skills,
            SkillMapService skillMap) {
        this.sessions = sessions;
        this.learners = learners;
        this.backgrounds = backgrounds;
        this.exercises = exercises;
        this.skills = skills;
        this.skillMap = skillMap;
    }

    private static boolean safeAndAvailable(String resource) {
        return resource != null && resource.startsWith("exercises/")
                && !resource.contains("..") && !resource.contains("\\")
                && new ClassPathResource(resource).exists();
    }

    public record RecommendationView(UUID sessionId, String algorithmVersion,
            String status, String reasonCode, java.util.List<RecommendationEngine.Item> items) {}

    @Transactional(readOnly = true)
    public RecommendationView recommend(UUID learnerId, UUID sessionId, int limit) {
        if (limit < 1 || limit > 3) throw new IllegalArgumentException("INVALID_LIMIT");
        var session = sessions.findById(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("Diagnostic session not found"));
        var learner = learners.findById(learnerId)
                .orElseThrow(() -> new EntityNotFoundException("Diagnostic session not found"));
        var profile = session.getInstrumentProfile();
        if (learner.getInstrumentProfiles().stream().noneMatch(p -> p.getId().equals(profile.getId()))) {
            throw new EntityNotFoundException("Diagnostic session not found");
        }
        if (session.getStatus() != DiagnosticStatus.READY_FOR_TASK) {
            throw new RecommendationStateException(session.getStatus() == DiagnosticStatus.TASK_ASSIGNED
                    ? "TASK_ALREADY_ASSIGNED" : "DIAGNOSTIC_NOT_READY");
        }

        // The same validated snapshot is used for explanations and for ranking.
        // A corrupt/cyclic skill graph must stop recommendations (AC-16-02).
        var snapshots = skillMap.map(learnerId, profile.getInstrumentCode());
        var skillStates = new HashMap<String, RecommendationEngine.SkillState>();
        for (var snapshot : snapshots) {
            skillStates.put(snapshot.skillCode(), new RecommendationEngine.SkillState(
                    snapshot.assessedLevel(), snapshot.selfReported(), snapshot.prerequisites()));
        }
        var background = backgrounds.findByInstrumentProfile_Id(profile.getId());
        var minutes = background.map(b -> b.getPracticeMinutes()).orElse(10);
        var experience = background.map(b -> b.getExperienceLevel()).orElse(null);
        var goals = background.<Set<GoalCode>>map(b -> b.getGoals()).orElse(Set.of());
        var preferences = new HashMap<String, PreferenceRating>();
        profile.getPreferences().forEach(p -> preferences.put(p.getStyleCode(), p.getRating()));
        var candidates = exercises
                .findByInstrumentCodeAndTechniqueCodeAndCurrentTrueOrderByCode(
                        profile.getInstrumentCode(), profile.getTechniqueCode()).stream()
                .filter(e -> e.getRecommendedPracticeMinutes() != null
                        && safeAndAvailable(e.getScoreResource())
                        && safeAndAvailable(e.getEventsResource()))
                .map(e -> new RecommendationEngine.Candidate(e.getCode(), e.getVersion(),
                        e.getTitle(), e.getStyleCode(), e.getDifficulty(),
                        e.getLicense(), e.getReviewStatus(),
                        e.getRecommendedPracticeMinutes(), skills.exerciseRoles(e.getId()),
                        skills.exerciseTargetLevels(e.getId())))
                .toList();
        var selected = engine.select(new RecommendationEngine.Context(experience, minutes,
                goals, Map.copyOf(preferences), Map.copyOf(skillStates)), candidates, limit);
        return new RecommendationView(sessionId, RecommendationEngine.VERSION,
                selected.status(), selected.reasonCode(), selected.items());
    }
}
