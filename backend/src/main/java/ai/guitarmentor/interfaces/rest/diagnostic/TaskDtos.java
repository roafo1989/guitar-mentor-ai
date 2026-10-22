package ai.guitarmentor.interfaces.rest.diagnostic;

import ai.guitarmentor.domain.diagnostic.DiagnosticTask;
import ai.guitarmentor.interfaces.rest.exercise.ExerciseView;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;

public final class TaskDtos {
    private TaskDtos() {}

    public record AssignRequest(@NotBlank String exerciseCode) {}

    public record TaskView(UUID id, UUID sessionId, Instant assignedAt, ExerciseView exercise) {
        public static TaskView from(DiagnosticTask task, ExerciseView exercise) {
            return new TaskView(task.getId(), task.getSession().getId(), task.getAssignedAt(),
                    exercise);
        }
    }
}
