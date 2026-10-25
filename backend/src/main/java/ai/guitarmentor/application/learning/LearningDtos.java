package ai.guitarmentor.application.learning;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Typed REST/read contract for #18. None of these records implies a graded audio recording. */
public final class LearningDtos {
    private LearningDtos() {}

    public record NextLessonLink(UUID sessionId, UUID taskId) {}

    public record LearningView(UUID taskId, String taskState, long version,
            List<String> availableActions, UUID latestAttemptId, String assessmentState,
            UUID latestAssessmentId, DecisionView latestDecision,
            String nextStepAvailability, NextLessonLink nextLesson) {}

    public record CriterionResultView(String criterionCode, String skillCode, String label,
            String observableResult, String status, String outcome, String reasonCode,
            String evidenceText, Integer confirmedSkillLevel) {}

    public record AssessmentView(UUID id, UUID attemptId, int revision,
            String sourceKind, String sourcePrincipal, String methodId, String methodVersion, String recommendation,
            Instant createdAt, List<CriterionResultView> criteria, List<String> limitations) {}

    public record DecisionView(UUID decisionId, UUID taskId, UUID attemptId,
            UUID assessmentId, String action, String resultingState, long resultingVersion,
            String reasonCode, String reasonText, Instant recordedAt) {}

    public record DecisionResult(UUID decisionId, UUID taskId, String taskState,
            long version, UUID assessmentId, Instant recordedAt,
            String nextStepAvailability, NextLessonLink nextLesson) {}

    public record DecisionRequest(UUID requestId, UUID attemptId, String action,
            Long expectedVersion, UUID baseAssessmentId, String reason) {}

    public record CreatedDecision(DecisionResult result, boolean replay) {}

    public record Page<T>(List<T> items, String nextCursor) {}

    public record SkillEvidenceView(UUID assessmentId, UUID attemptId, String criterionCode,
            String skillCode, String sourceKind, String sourcePrincipal, String methodId, String methodVersion,
            Instant createdAt, String status, String outcome, String reasonCode,
            String evidenceText, Integer confirmedSkillLevel) {}
}
