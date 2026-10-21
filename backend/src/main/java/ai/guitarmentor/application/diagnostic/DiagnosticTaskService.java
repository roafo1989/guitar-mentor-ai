package ai.guitarmentor.application.diagnostic;

import ai.guitarmentor.domain.diagnostic.DiagnosticSession;
import ai.guitarmentor.domain.diagnostic.DiagnosticStatus;
import ai.guitarmentor.domain.diagnostic.DiagnosticTask;
import ai.guitarmentor.domain.exercise.Exercise;
import ai.guitarmentor.infrastructure.persistence.DiagnosticSessionRepository;
import ai.guitarmentor.infrastructure.persistence.DiagnosticTaskRepository;
import ai.guitarmentor.infrastructure.persistence.ExerciseRepository;
import ai.guitarmentor.infrastructure.persistence.LearnerRepository;
import ai.guitarmentor.infrastructure.persistence.LearningRepository;
import ai.guitarmentor.interfaces.rest.diagnostic.TaskDtos.TaskView;
import jakarta.persistence.EntityNotFoundException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DiagnosticTaskService {
    private final DiagnosticSessionRepository sessions;
    private final DiagnosticTaskRepository tasks;
    private final ExerciseRepository exercises;
    private final LearnerRepository learners;
    private final LearningRepository learning;

    public DiagnosticTaskService(DiagnosticSessionRepository sessions, DiagnosticTaskRepository tasks,
            ExerciseRepository exercises, LearnerRepository learners, LearningRepository learning) {
        this.sessions = sessions;
        this.tasks = tasks;
        this.exercises = exercises;
        this.learners = learners;
        this.learning = learning;
    }

    @Transactional(readOnly = true)
    public Optional<TaskView> current(UUID learnerId, UUID sessionId) {
        ownedSession(learnerId, sessionId, false);
        return tasks.findBySession_Id(sessionId).map(TaskView::from);
    }

    @Transactional
    public TaskView assign(UUID learnerId, UUID sessionId, String exerciseCode) {
        var session = ownedSession(learnerId, sessionId, true);
        // The row lock serializes assignments to this specific session.
        var existing = tasks.findBySession_Id(sessionId);
        if (existing.isPresent()) {
            if (!existing.get().getExercise().getCode().equals(exerciseCode)) {
                throw new IllegalStateException("A different exercise was already assigned");
            }
            return TaskView.from(existing.get()); // Idempotent retries.
        }
        if (session.getStatus() != DiagnosticStatus.READY_FOR_TASK) {
            throw new IllegalStateException("Finish intake before assigning an exercise");
        }
        var exercise = exercises.findByCodeAndCurrentTrue(exerciseCode)
                .orElseThrow(() -> new EntityNotFoundException("Exercise not found"));
        if (!exercise.getInstrumentCode().equals(session.getInstrumentProfile().getInstrumentCode())
                || !exercise.getTechniqueCode().equals(session.getInstrumentProfile().getTechniqueCode())) {
            throw new IllegalStateException("Exercise is not suitable for this instrument and technique");
        }
        var task = tasks.saveAndFlush(new DiagnosticTask(session, exercise));
        learning.initialize(task.getId()); // Same transaction as assignment.
        session.markTaskAssigned();
        return TaskView.from(task);
    }

    private DiagnosticSession ownedSession(UUID learnerId, UUID sessionId, boolean lock) {
        var session = (lock ? sessions.findLockedById(sessionId) : sessions.findById(sessionId))
                .orElseThrow(() -> new EntityNotFoundException("Diagnostic session not found"));
        var learner = learners.findById(learnerId)
                .orElseThrow(() -> new EntityNotFoundException("Learner not found"));
        boolean owned = learner.getInstrumentProfiles().stream()
                .anyMatch(p -> p.getId().equals(session.getInstrumentProfile().getId()));
        if (!owned) throw new EntityNotFoundException("Diagnostic session not found");
        return session;
    }
}
