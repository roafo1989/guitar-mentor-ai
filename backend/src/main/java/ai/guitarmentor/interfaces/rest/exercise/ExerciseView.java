package ai.guitarmentor.interfaces.rest.exercise;

import ai.guitarmentor.domain.exercise.Exercise;
import java.util.Set;

public record ExerciseView(
        String code, String version, String title, String instrumentCode,
        String techniqueCode, String styleCode, String difficulty,
        int bpm, int measures, String meter, String author, String license,
        String reviewStatus, String description, Set<String> skillCodes,
        String scoreUrl, String eventsUrl) {

    public static ExerciseView from(Exercise item) {
        var prefix = "/api/v1/exercises/" + item.getCode();
        return new ExerciseView(item.getCode(), item.getVersion(), item.getTitle(),
                item.getInstrumentCode(), item.getTechniqueCode(), item.getStyleCode(),
                item.getDifficulty(), item.getBpm(), item.getMeasures(), item.getMeter(),
                item.getAuthor(), item.getLicense(), item.getReviewStatus(),
                item.getDescription(), item.getSkillCodes(), prefix + "/score",
                prefix + "/events");
    }
}
