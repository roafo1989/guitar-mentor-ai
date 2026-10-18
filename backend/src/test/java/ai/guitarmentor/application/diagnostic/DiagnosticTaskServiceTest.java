package ai.guitarmentor.application.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import ai.guitarmentor.domain.diagnostic.DiagnosticSession;
import ai.guitarmentor.domain.diagnostic.DiagnosticStatus;
import ai.guitarmentor.domain.diagnostic.DiagnosticTask;
import ai.guitarmentor.domain.exercise.Exercise;
import ai.guitarmentor.domain.learner.InstrumentProfile;
import ai.guitarmentor.domain.learner.Learner;
import ai.guitarmentor.infrastructure.persistence.DiagnosticSessionRepository;
import ai.guitarmentor.infrastructure.persistence.DiagnosticTaskRepository;
import ai.guitarmentor.infrastructure.persistence.ExerciseRepository;
import ai.guitarmentor.infrastructure.persistence.LearnerRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DiagnosticTaskServiceTest {
    @Mock DiagnosticSessionRepository sessions;
    @Mock DiagnosticTaskRepository tasks;
    @Mock ExerciseRepository exercises;
    @Mock LearnerRepository learners;
    @Mock Exercise exercise;

    DiagnosticTaskService service;
    Learner learner;
    InstrumentProfile profile;
    DiagnosticSession session;

    @BeforeEach
    void setUp() {
        service = new DiagnosticTaskService(sessions, tasks, exercises, learners);
        learner = new Learner("Student");
        profile = new InstrumentProfile("ACOUSTIC_GUITAR", "FINGERSTYLE");
        learner.addInstrumentProfile(profile);
        session = new DiagnosticSession(profile);
    }

    @Test
    void cannotAssignBeforeFinishingIntake() {
        when(sessions.findLockedById(session.getId())).thenReturn(Optional.of(session));
        when(learners.findById(learner.getId())).thenReturn(Optional.of(learner));
        assertThrows(IllegalStateException.class,
                () -> service.assign(learner.getId(), session.getId(), "GM-EB01"));
    }

    @Test
    void repeatsSameAssignmentWithoutCreatingAnotherTask() {
        session.finishIntake();
        when(sessions.findLockedById(session.getId())).thenReturn(Optional.of(session));
        when(learners.findById(learner.getId())).thenReturn(Optional.of(learner));
        when(exercises.findByCodeAndCurrentTrue("GM-EB01")).thenReturn(Optional.of(exercise));
        when(exercise.getInstrumentCode()).thenReturn("ACOUSTIC_GUITAR");
        when(exercise.getTechniqueCode()).thenReturn("FINGERSTYLE");
        when(exercise.getCode()).thenReturn("GM-EB01");
        var saved = new AtomicReference<DiagnosticTask>();
        when(tasks.findBySession_Id(session.getId()))
                .thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        when(tasks.saveAndFlush(any(DiagnosticTask.class))).thenAnswer(invocation -> {
            var task = invocation.<DiagnosticTask>getArgument(0);
            saved.set(task);
            return task;
        });

        var first = service.assign(learner.getId(), session.getId(), "GM-EB01");
        var second = service.assign(learner.getId(), session.getId(), "GM-EB01");

        assertNotNull(first.id());
        assertEquals(first.id(), second.id());
        assertEquals(DiagnosticStatus.TASK_ASSIGNED, session.getStatus());
    }

    @Test
    void wrongLearnerCannotReadOtherLearnersTask() {
        var other = new Learner("Other");
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        when(learners.findById(other.getId())).thenReturn(Optional.of(other));
        assertThrows(EntityNotFoundException.class,
                () -> service.current(other.getId(), session.getId()));
    }

    @Test
    void mismatchedTechniqueIsRejected() {
        session.finishIntake();
        when(sessions.findLockedById(session.getId())).thenReturn(Optional.of(session));
        when(learners.findById(learner.getId())).thenReturn(Optional.of(learner));
        when(exercises.findByCodeAndCurrentTrue("GM-EB01")).thenReturn(Optional.of(exercise));
        when(exercise.getInstrumentCode()).thenReturn("ACOUSTIC_GUITAR");
        when(exercise.getTechniqueCode()).thenReturn("PICK");
        assertThrows(IllegalStateException.class,
                () -> service.assign(learner.getId(), session.getId(), "GM-EB01"));
    }
}
