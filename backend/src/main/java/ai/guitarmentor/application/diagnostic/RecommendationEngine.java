package ai.guitarmentor.application.diagnostic;

import ai.guitarmentor.domain.learner.PreferenceRating;
import ai.guitarmentor.domain.onboarding.ExperienceLevel;
import ai.guitarmentor.domain.onboarding.GoalCode;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure initial diagnostic recommendation rules; evidence is consumed, not created here. */
public final class RecommendationEngine {
    public static final String VERSION = "initial-diagnostic-v2";
    private static final Set<String> ACCEPTED_LICENSES = Set.of(
            "CC BY 4.0", "CC0 1.0", "PUBLIC_DOMAIN", "ORIGINAL_OWNED");
    private static final Set<String> ACCEPTED_REVIEWS = Set.of(
            "ACCEPTED_FOR_MVP_PROTOTYPE", "APPROVED", "PUBLISHED");
    private static final Map<GoalCode, Set<String>> GOALS = Map.of(
            GoalCode.RHYTHM, Set.of("PULSE", "SUBDIVISION"),
            GoalCode.INDEPENDENT_BASS, Set.of("BASS_PULSE", "VOICE_INDEPENDENCE"),
            GoalCode.FINGERSTYLE, Set.of("RH_HAND", "MELODY"),
            GoalCode.COMPOSITIONS, Set.of("MELODY", "DYNAMICS"),
            GoalCode.THEORY, Set.of("TAB_READING"),
            GoalCode.DIFFERENT_STYLES, Set.of());

    /** Level zero is unknown, not a pedagogical failure. */
    public record SkillState(int assessedLevel, boolean selfReported, List<String> prerequisites) {}
    public record Candidate(String code, String version, String title, String style,
            String difficulty, String license, String reviewStatus, int minutes,
            Map<String, String> skillRoles, Map<String, Integer> targetLevels) {
        public Candidate(String code, String version, String title, String style,
                String difficulty, String license, String reviewStatus, int minutes,
                Map<String, String> skillRoles) {
            this(code, version, title, style, difficulty, license, reviewStatus,
                    minutes, skillRoles, Map.of());
        }
    }
    public record Context(ExperienceLevel experienceLevel, int minutes,
            Set<GoalCode> goals, Map<String, PreferenceRating> preferences,
            Map<String, SkillState> skills) {
        public Context(ExperienceLevel experienceLevel, int minutes,
                Set<GoalCode> goals, Map<String, PreferenceRating> preferences) {
            this(experienceLevel, minutes, goals, preferences, Map.of());
        }
    }
    public record Item(String exerciseCode, String exerciseVersion, String title,
            int recommendedPracticeMinutes, List<String> skillCodes,
            List<String> reasonCodes, String reasonText) {}
    public record Result(String status, String reasonCode, List<Item> items) {}

    public Result select(Context context, List<Candidate> candidates, int limit) {
        if (limit < 1 || limit > 3) throw new IllegalArgumentException("INVALID_LIMIT");
        int ceiling = switch (context.experienceLevel() == null
                ? ExperienceLevel.NONE : context.experienceLevel()) {
            case NONE, BEGINNER -> 1;
            case INTERMEDIATE -> 2;
            case ADVANCED -> 3; // CHALLENGE=4 requires an explicit future opt-in
        };
        var approved = candidates.stream().filter(this::approved).toList();
        var allowedStyles = approved.stream().filter(c ->
                context.preferences().getOrDefault(c.style(), PreferenceRating.NEUTRAL)
                        != PreferenceRating.EXCLUDE).toList();
        var allowedLevels = allowedStyles.stream()
                .filter(c -> difficulty(c.difficulty()) >= 0
                        && difficulty(c.difficulty()) <= ceiling).toList();
        var allowedTime = allowedLevels.stream()
                .filter(c -> c.minutes() >= 10 && c.minutes() <= context.minutes()).toList();
        if (allowedTime.isEmpty()) {
            var reason = approved.isEmpty() ? "NO_COMPATIBLE_EXERCISE"
                    : allowedStyles.isEmpty() ? "ALL_STYLES_EXCLUDED"
                    : allowedLevels.isEmpty() && ceiling == 1 ? "NO_BEGINNER_EXERCISE"
                    : allowedLevels.isEmpty() ? "NO_COMPATIBLE_EXERCISE" : "NO_TIME_FIT";
            return new Result("NO_MATCH", reason, List.of());
        }
        var ranked = allowedTime.stream()
                .sorted(Comparator.comparingInt((Candidate c) -> score(c, context)).reversed()
                        .thenComparingInt(c -> difficulty(c.difficulty()))
                        .thenComparingInt(Candidate::minutes)
                        .thenComparing(Candidate::code).thenComparing(Candidate::version))
                .limit(limit).map(c -> item(c, context)).toList();
        return new Result("RECOMMENDED", null, ranked);
    }

    private boolean approved(Candidate c) {
        return ACCEPTED_LICENSES.contains(c.license())
                && ACCEPTED_REVIEWS.contains(c.reviewStatus());
    }

    private static int difficulty(String value) {
        if (value == null) return -1;
        return switch (value) {
            case "NOVICE", "EASY", "BEGINNER" -> 1;
            case "INTERMEDIATE" -> 2;
            case "ADVANCED" -> 3;
            case "CHALLENGE" -> 4;
            default -> -1;
        };
    }

    private static List<String> primary(Candidate candidate) {
        return candidate.skillRoles().entrySet().stream()
                .filter(e -> "PRIMARY".equals(e.getValue()))
                .map(Map.Entry::getKey).sorted().toList();
    }

    private static boolean unknownPrimary(Candidate c, Context context) {
        return primary(c).stream().anyMatch(code ->
                context.skills().containsKey(code)
                        && context.skills().get(code).assessedLevel() == 0);
    }

    private static boolean prerequisiteToPractice(Candidate c, Context context) {
        return primary(c).stream().anyMatch(code ->
                context.skills().entrySet().stream()
                        .anyMatch(other -> !other.getKey().equals(code)
                                && other.getValue().assessedLevel() < 3
                                && other.getValue().prerequisites().contains(code)));
    }

    private static int skillNeed(Candidate c, Context context) {
        int score = 0;
        for (String code : primary(c)) {
            SkillState state = context.skills().get(code);
            if (state == null) continue;
            Integer target = c.targetLevels().get(code);
            // Already demonstrated CONSISTENT skills are less urgent to diagnose.
            if (state.assessedLevel() == 0) score += 2;
            else if (target != null && state.assessedLevel() < target) score += 3;
            else if (state.assessedLevel() < 3) score += 1;
        }
        return score;
    }

    private static int goalScore(Candidate c, Context context) {
        int count = 0;
        for (GoalCode goal : context.goals()) {
            for (String code : GOALS.getOrDefault(goal, Set.of())) {
                if ("PRIMARY".equals(c.skillRoles().get(code))) count++;
            }
        }
        return count;
    }

    private static int score(Candidate c, Context context) {
        return goalScore(c, context) * 100
                + skillNeed(c, context) * 20
                + (prerequisiteToPractice(c, context) ? 8 : 0)
                + (context.preferences().getOrDefault(c.style(), PreferenceRating.NEUTRAL)
                   == PreferenceRating.LIKE ? 10 : 0);
    }

    private static Item item(Candidate c, Context context) {
        var reasons = new LinkedHashSet<String>();
        if (goalScore(c, context) > 0) reasons.add("GOAL_MATCH");
        if (unknownPrimary(c, context)) reasons.add("UNKNOWN_SKILL_DIAGNOSTIC");
        if (prerequisiteToPractice(c, context)) reasons.add("PREREQUISITE_TO_PRACTICE");
        if (context.preferences().getOrDefault(c.style(), PreferenceRating.NEUTRAL)
                == PreferenceRating.LIKE) reasons.add("PREFERRED_STYLE");
        reasons.add("TIME_FIT");
        var mainSkills = primary(c);
        return new Item(c.code(), c.version(), c.title(), c.minutes(), mainSkills,
                List.copyOf(reasons),
                "Диагностическое упражнение: " + String.join(", ", mainSkills)
                  + "; рекомендовано " + c.minutes() + " минут.");
    }
}
