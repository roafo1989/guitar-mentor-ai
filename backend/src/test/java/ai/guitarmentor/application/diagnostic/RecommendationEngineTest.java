package ai.guitarmentor.application.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.guitarmentor.domain.learner.PreferenceRating;
import ai.guitarmentor.domain.onboarding.ExperienceLevel;
import ai.guitarmentor.domain.onboarding.GoalCode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecommendationEngineTest {
    private final RecommendationEngine engine = new RecommendationEngine();

    private static RecommendationEngine.Candidate candidate(String code, String style,
            String difficulty, int minutes, String skill) {
        return new RecommendationEngine.Candidate(code, "1.0", code, style, difficulty,
                "CC BY 4.0", "APPROVED", minutes, Map.of(skill, "PRIMARY"));
    }

    private static RecommendationEngine.Context context(ExperienceLevel level, int minutes,
            Set<GoalCode> goals, Map<String, PreferenceRating> preferences) {
        return new RecommendationEngine.Context(level, minutes, goals, preferences);
    }

    @Test
    void excludedStyleIsNeverAutomaticallyRecommended() {
        var result = engine.select(context(ExperienceLevel.INTERMEDIATE, 45,
                Set.of(), Map.of("BLUES", PreferenceRating.EXCLUDE)),
                List.of(candidate("BLUES-01", "BLUES", "INTERMEDIATE", 30, "PULSE"),
                        candidate("ROCK-01", "ROCK", "INTERMEDIATE", 30, "PULSE")), 3);
        assertEquals("RECOMMENDED", result.status());
        assertEquals(List.of("ROCK-01"),
                result.items().stream().map(RecommendationEngine.Item::exerciseCode).toList());
    }

    @Test
    void allExcludedReturnsNoMatchWithoutOverride() {
        var r = engine.select(context(ExperienceLevel.INTERMEDIATE, 45, Set.of(),
                Map.of("BLUES", PreferenceRating.EXCLUDE)),
                List.of(candidate("B", "BLUES", "INTERMEDIATE", 20, "PULSE")), 3);
        assertEquals("NO_MATCH", r.status());
        assertEquals("ALL_STYLES_EXCLUDED", r.reasonCode());
    }

    @Test
    void noviceCannotReceiveIntermediateCatalogItem() {
        var r = engine.select(context(ExperienceLevel.NONE, 30, Set.of(), Map.of()),
                List.of(candidate("B", "BLUES", "INTERMEDIATE", 20, "PULSE")), 3);
        assertEquals("NO_BEGINNER_EXERCISE", r.reasonCode());
        assertTrue(r.items().isEmpty());
    }

    @Test
    void noviceWithBeginnerFixtureGetsAccessibleExercise() {
        var r = engine.select(context(null, 10, Set.of(), Map.of()),
                List.of(candidate("BEGIN-1", "ROCK", "BEGINNER", 10, "RH_HAND")), 3);
        assertEquals("RECOMMENDED", r.status());
        assertEquals("BEGIN-1", r.items().getFirst().exerciseCode());
    }

    @Test
    void durationFilterDoesNotSilentlyIncreasePracticeTime() {
        var r = engine.select(context(ExperienceLevel.INTERMEDIATE, 10, Set.of(), Map.of()),
                List.of(candidate("B", "BLUES", "INTERMEDIATE", 30, "MELODY")), 3);
        assertEquals("NO_TIME_FIT", r.reasonCode());
    }

    @Test
    void goalAndStyleRankWithStableTieBreak() {
        var c = context(ExperienceLevel.INTERMEDIATE, 45, Set.of(GoalCode.RHYTHM),
                Map.of("BLUES", PreferenceRating.LIKE));
        var a = candidate("AAA", "ROCK", "INTERMEDIATE", 30, "MELODY");
        var b = candidate("BBB", "BLUES", "INTERMEDIATE", 30, "PULSE");
        var d = candidate("DDD", "BLUES", "INTERMEDIATE", 30, "PULSE");
        var r1 = engine.select(c, List.of(d, a, b), 3);
        var r2 = engine.select(c, List.of(a, b, d), 3);
        assertEquals(r1.items(), r2.items());
        assertEquals("BBB", r1.items().getFirst().exerciseCode());
        assertTrue(r1.items().getFirst().reasonCodes().contains("GOAL_MATCH"));
        assertTrue(r1.items().getFirst().reasonCodes().contains("PREFERRED_STYLE"));
    }

    @Test
    void noCatalogOrUnreviewedMaterialNeverCreatesRecommendation() {
        var empty = engine.select(context(ExperienceLevel.INTERMEDIATE, 45,
                Set.of(), Map.of()), List.of(), 3);
        assertEquals("NO_COMPATIBLE_EXERCISE", empty.reasonCode());
        var unreviewed = new RecommendationEngine.Candidate("X", "1", "X", "ROCK",
                "BEGINNER", "CC BY 4.0", "DRAFT", 10, Map.of("MELODY", "PRIMARY"));
        assertEquals("NO_MATCH", engine.select(context(ExperienceLevel.NONE, 30,
                Set.of(), Map.of()), List.of(unreviewed), 3).status());
    }

    @Test
    void unknownDifficultyIsNotDowngradedToBeginner() {
        var r = engine.select(context(ExperienceLevel.NONE, 30, Set.of(), Map.of()),
                List.of(candidate("X", "ROCK", "TBD", 10, "MELODY")), 3);
        assertEquals("NO_BEGINNER_EXERCISE", r.reasonCode());
    }

    @Test
    void limitsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> engine.select(
                context(null, 10, Set.of(), Map.of()), List.of(), 4));
        assertThrows(IllegalArgumentException.class, () -> engine.select(
                context(null, 10, Set.of(), Map.of()), List.of(), 0));
    }

    @Test
    void intermediateCannotReceiveAdvancedOrChallengeExercises() {
        var c = context(ExperienceLevel.INTERMEDIATE, 60, Set.of(), Map.of());
        assertEquals("NO_COMPATIBLE_EXERCISE", engine.select(c, List.of(
                candidate("ADV", "BLUES", "ADVANCED", 30, "MELODY"),
                candidate("CH", "BLUES", "CHALLENGE", 30, "MELODY")), 3).reasonCode());
        assertEquals("ADV", engine.select(context(ExperienceLevel.ADVANCED, 60,
                Set.of(), Map.of()), List.of(
                candidate("ADV", "BLUES", "ADVANCED", 30, "MELODY"),
                candidate("CH", "BLUES", "CHALLENGE", 30, "MELODY")), 3)
                .items().getFirst().exerciseCode());
    }

    @Test
    void unlicensedOrUnsupportedLicensesCannotBeRecommended() {
        for (String license : List.of("ALL_RIGHTS_RESERVED", "UNLICENSED", "CC BY-NC 4.0")) {
            var candidate = new RecommendationEngine.Candidate("X", "1.0", "X",
                    "BLUES", "BEGINNER", license, "APPROVED", 20,
                    Map.of("MELODY", "PRIMARY"));
            assertEquals("NO_COMPATIBLE_EXERCISE", engine.select(
                    context(null, 30, Set.of(), Map.of()), List.of(candidate), 3).reasonCode());
        }
    }

    @Test
    void assessedSkillChangesRankingAndUnknownExplanation() {
        var unknown = new RecommendationEngine.SkillState(0, false, List.of());
        var mastered = new RecommendationEngine.SkillState(3, false, List.of());
        var a = candidate("A", "BLUES", "INTERMEDIATE", 30, "PULSE");
        var b = candidate("B", "BLUES", "INTERMEDIATE", 30, "MELODY");
        var novice = new RecommendationEngine.Context(ExperienceLevel.INTERMEDIATE, 60,
                Set.of(), Map.of(), Map.of("PULSE", unknown, "MELODY", mastered));
        var practiced = new RecommendationEngine.Context(ExperienceLevel.INTERMEDIATE, 60,
                Set.of(), Map.of(), Map.of("PULSE", mastered, "MELODY", unknown));
        var first = engine.select(novice, List.of(a, b), 3);
        var second = engine.select(practiced, List.of(a, b), 3);
        assertEquals("A", first.items().getFirst().exerciseCode());
        assertEquals("B", second.items().getFirst().exerciseCode());
        assertTrue(first.items().getFirst().reasonCodes().contains("UNKNOWN_SKILL_DIAGNOSTIC"));
        assertFalse(first.items().get(1).reasonCodes().contains("UNKNOWN_SKILL_DIAGNOSTIC"));
        assertEquals("initial-diagnostic-v2", RecommendationEngine.VERSION);
    }

    @Test
    void prerequisiteAndTargetLevelInfluenceRankingWithoutInventingEvidence() {
        var c = new RecommendationEngine.Context(ExperienceLevel.INTERMEDIATE, 40,
                Set.of(), Map.of(), Map.of(
                    "PULSE", new RecommendationEngine.SkillState(2, false, List.of()),
                    "MELODY", new RecommendationEngine.SkillState(2, false, List.of()),
                    "BASS_PULSE", new RecommendationEngine.SkillState(0, false, List.of("PULSE"))));
        var a = new RecommendationEngine.Candidate("A","1","A","ROCK","INTERMEDIATE",
                "CC BY 4.0","APPROVED",20,Map.of("PULSE","PRIMARY"),Map.of("PULSE",3));
        var b = candidate("B","ROCK","INTERMEDIATE",20,"MELODY");
        var result = engine.select(c, List.of(b,a),3);
        assertEquals("A",result.items().getFirst().exerciseCode());
        assertTrue(result.items().getFirst().reasonCodes().contains("PREREQUISITE_TO_PRACTICE"));
    }
}
