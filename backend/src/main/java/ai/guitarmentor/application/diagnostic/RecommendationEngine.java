package ai.guitarmentor.application.diagnostic;

import ai.guitarmentor.domain.learner.PreferenceRating;
import ai.guitarmentor.domain.onboarding.ExperienceLevel;
import ai.guitarmentor.domain.onboarding.GoalCode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic, explainable first diagnostic selection. No LLM, no writes. */
public final class RecommendationEngine {
    public static final String VERSION = "initial-diagnostic-v1";
    private static final Map<GoalCode, Set<String>> GOALS = Map.of(
            GoalCode.RHYTHM, Set.of("PULSE", "SUBDIVISION"),
            GoalCode.INDEPENDENT_BASS, Set.of("BASS_PULSE", "VOICE_INDEPENDENCE"),
            GoalCode.FINGERSTYLE, Set.of("RH_HAND", "MELODY"),
            GoalCode.COMPOSITIONS, Set.of("MELODY", "DYNAMICS"),
            GoalCode.THEORY, Set.of("TAB_READING"),
            GoalCode.DIFFERENT_STYLES, Set.of());

    public record Candidate(String code, String version, String title, String style,
            String difficulty, String license, String reviewStatus,
            int minutes, Map<String, String> skillRoles) {}
    public record Context(ExperienceLevel experienceLevel, int minutes,
            Set<GoalCode> goals, Map<String, PreferenceRating> preferences) {}
    public record Item(String exerciseCode, String exerciseVersion, String title,
            int recommendedPracticeMinutes, List<String> skillCodes,
            List<String> reasonCodes, String reasonText) {}
    public record Result(String status, String reasonCode, List<Item> items) {}

    public Result select(Context context, List<Candidate> candidates, int limit) {
        if (limit < 1 || limit > 3) throw new IllegalArgumentException("INVALID_LIMIT");
        boolean novice = context.experienceLevel() == null ||
                context.experienceLevel() == ExperienceLevel.NONE ||
                context.experienceLevel() == ExperienceLevel.BEGINNER;
        var eligible = candidates.stream().filter(this::approved).toList();
        var styleAllowed = eligible.stream().filter(c ->
                context.preferences().getOrDefault(c.style(), PreferenceRating.NEUTRAL)
                        != PreferenceRating.EXCLUDE).toList();
        var levelAllowed = styleAllowed.stream().filter(c ->
                knownDifficulty(c.difficulty()) &&
                (!novice || c.difficulty().equals("BEGINNER")
                        || c.difficulty().equals("NOVICE")
                        || c.difficulty().equals("EASY"))).toList();
        var timeAllowed = levelAllowed.stream()
                .filter(c -> c.minutes() >= 10 && c.minutes() <= context.minutes()).toList();

        if (timeAllowed.isEmpty()) {
            String reason = eligible.isEmpty() ? "NO_COMPATIBLE_EXERCISE"
                    : styleAllowed.isEmpty() ? "ALL_STYLES_EXCLUDED"
                    : levelAllowed.isEmpty() && novice ? "NO_BEGINNER_EXERCISE"
                    : levelAllowed.isEmpty() ? "NO_COMPATIBLE_EXERCISE" : "NO_TIME_FIT";
            return new Result("NO_MATCH", reason, List.of());
        }
        var ranked = timeAllowed.stream().sorted(
                Comparator.comparingInt((Candidate c) -> goalScore(c, context)).reversed()
                        .thenComparing(Comparator.comparingInt((Candidate c) -> primaryCount(c)).reversed())
                        .thenComparing(Comparator.comparingInt((Candidate c) ->
                                context.preferences().getOrDefault(c.style(), PreferenceRating.NEUTRAL)
                                        == PreferenceRating.LIKE ? 1 : 0).reversed())
                        .thenComparingInt(Candidate::minutes)
                        .thenComparing(Candidate::code).thenComparing(Candidate::version))
                .limit(limit).map(c -> item(c, context)).toList();
        return new Result("RECOMMENDED", null, ranked);
    }

    private boolean approved(Candidate c) {
        return c.license() != null && !c.license().isBlank()
                && (c.reviewStatus().equals("ACCEPTED_FOR_MVP_PROTOTYPE")
                    || c.reviewStatus().equals("APPROVED") || c.reviewStatus().equals("PUBLISHED"));
    }

    private static boolean knownDifficulty(String value) {
        return Set.of("NOVICE","EASY","BEGINNER","INTERMEDIATE","ADVANCED","CHALLENGE")
                .contains(value);
    }

    private static int primaryCount(Candidate c) {
        return (int) c.skillRoles().values().stream().filter("PRIMARY"::equals).count();
    }

    private static int goalScore(Candidate c, Context ctx) {
        int total = 0;
        for (GoalCode goal : ctx.goals()) {
            for (String code : GOALS.getOrDefault(goal, Set.of())) {
                if ("PRIMARY".equals(c.skillRoles().get(code))) total++;
            }
        }
        return total;
    }

    private static Item item(Candidate c, Context context) {
        var reasons = new LinkedHashSet<String>();
        if (goalScore(c, context) > 0) reasons.add("GOAL_MATCH");
        if (primaryCount(c) > 0) reasons.add("UNKNOWN_SKILL_DIAGNOSTIC");
        if (context.preferences().getOrDefault(c.style(), PreferenceRating.NEUTRAL)
                == PreferenceRating.LIKE) reasons.add("PREFERRED_STYLE");
        reasons.add("TIME_FIT");
        var mainSkills = c.skillRoles().entrySet().stream()
                .filter(e -> "PRIMARY".equals(e.getValue())).map(Map.Entry::getKey)
                .sorted().toList();
        String explanation = "Диагностическое упражнение для навыков: "
                + String.join(", ", mainSkills) + "; время занятия: " + c.minutes() + " минут.";
        return new Item(c.code(), c.version(), c.title(), c.minutes(),
                mainSkills, List.copyOf(reasons), explanation);
    }
}
