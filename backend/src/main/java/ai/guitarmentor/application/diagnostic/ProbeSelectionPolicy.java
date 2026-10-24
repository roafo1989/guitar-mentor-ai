package ai.guitarmentor.application.diagnostic;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Pure, conservative policy: ask for one short probe only to reduce unknown evidence. */
public final class ProbeSelectionPolicy {
    private ProbeSelectionPolicy() {}
    public record Candidate(String code, String version, String title,
            int durationSeconds, List<String> primarySkills) {}

    public static Optional<Candidate> next(List<Candidate> options,
            Map<String, RecommendationEngine.SkillState> assessed) {
        return options.stream()
                .filter(c -> c.durationSeconds() >= 5 && c.durationSeconds() <= 60)
                .filter(c -> !c.primarySkills().isEmpty())
                .filter(c -> c.primarySkills().stream().anyMatch(skill ->
                        assessed.containsKey(skill)
                        && assessed.get(skill).assessedLevel() == 0))
                .sorted(Comparator.comparingInt(Candidate::durationSeconds)
                        .thenComparing(Candidate::code)
                        .thenComparing(Candidate::version))
                .findFirst();
    }
}
