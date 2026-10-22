package ai.guitarmentor.interfaces.rest.exercise;

import ai.guitarmentor.domain.exercise.Exercise;
import java.util.Set;
import java.util.List;

public record ExerciseView(
        String code, String version, String title, String instrumentCode,
        String techniqueCode, String styleCode, String difficulty,
        int bpm, int measures, String meter, String author, String license,
        String reviewStatus, String description, Set<String> skillCodes,
        String learningGoal, String practiceInstructions, List<String> successCriteria,
        String tuning, Integer recommendedPracticeMinutes,
        String scoreUrl, String eventsUrl, String previewAudioUrl, String previewPdfUrl) {

    public static ExerciseView from(Exercise item, List<String> successCriteria) {
        var prefix = "/api/v1/exercises/" + item.getCode();
        return new ExerciseView(item.getCode(), item.getVersion(), item.getTitle(),
                item.getInstrumentCode(), item.getTechniqueCode(), item.getStyleCode(),
                item.getDifficulty(), item.getBpm(), item.getMeasures(), item.getMeter(),
                item.getAuthor(), item.getLicense(), item.getReviewStatus(),
                item.getDescription(), item.getSkillCodes(),
                item.getLearningGoal(), item.getPracticeInstructions(),
                List.copyOf(successCriteria), item.getTuning(), item.getRecommendedPracticeMinutes(),
                prefix + "/score",
                prefix + "/events",
                item.getPreviewAudioResource() == null ? null : prefix + "/preview/audio",
                item.getPreviewPdfResource() == null ? null : prefix + "/preview/pdf");
    }
}
