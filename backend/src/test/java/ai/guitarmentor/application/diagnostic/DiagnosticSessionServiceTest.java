package ai.guitarmentor.application.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import ai.guitarmentor.domain.diagnostic.DiagnosticSession;
import ai.guitarmentor.domain.diagnostic.DiagnosticStatus;
import ai.guitarmentor.domain.learner.InstrumentProfile;
import ai.guitarmentor.domain.learner.Learner;
import ai.guitarmentor.infrastructure.persistence.DiagnosticSessionRepository;
import ai.guitarmentor.infrastructure.persistence.LearnerRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DiagnosticSessionServiceTest {
    @Mock LearnerRepository learners;
    @Mock DiagnosticSessionRepository sessions;

    private DiagnosticSessionService service;
    private Learner learner;
    private InstrumentProfile instrument;

    @BeforeEach
    void setUp() {
        service = new DiagnosticSessionService(learners, sessions);
        learner = new Learner("Student");
        instrument = new InstrumentProfile("ACOUSTIC_GUITAR", "FINGERSTYLE");
        learner.addInstrumentProfile(instrument);
    }

    @Test
    void startsSessionWithCorrectInstrumentAndActions() {
        when(learners.findLockedById(learner.getId())).thenReturn(Optional.of(learner));
        when(sessions.findFirstByInstrumentProfile_IdAndStatusInOrderByCreatedAtDesc(
                instrument.getId(), List.of(DiagnosticStatus.COLLECTING_EVIDENCE,
                        DiagnosticStatus.READY_FOR_TASK))).thenReturn(Optional.empty());
        when(sessions.saveAndFlush(any(DiagnosticSession.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        var result = service.start(learner.getId(), "ACOUSTIC_GUITAR");
        assertTrue(result.created());
        assertEquals(DiagnosticStatus.COLLECTING_EVIDENCE, result.session().status());
        assertEquals(List.of("CONTINUE_WITHOUT_RECORDING"), result.session().availableActions());
    }

    @Test
    void repeatedStartReturnsExistingSessionWithoutCreatingAnother() {
        when(learners.findLockedById(learner.getId())).thenReturn(Optional.of(learner));
        var existing = new DiagnosticSession(instrument);
        when(sessions.findFirstByInstrumentProfile_IdAndStatusInOrderByCreatedAtDesc(
                instrument.getId(), List.of(DiagnosticStatus.COLLECTING_EVIDENCE,
                        DiagnosticStatus.READY_FOR_TASK))).thenReturn(Optional.of(existing));

        var result = service.start(learner.getId(), "ACOUSTIC_GUITAR");
        assertFalse(result.created());
        assertEquals(existing.getId(), result.session().id());
    }

    @Test
    void finishedIntakeIsIdempotent() {
        var session = new DiagnosticSession(instrument);
        when(learners.findById(learner.getId())).thenReturn(Optional.of(learner));
        when(sessions.findLockedById(session.getId())).thenReturn(Optional.of(session));

        assertEquals(DiagnosticStatus.READY_FOR_TASK,
                service.finishIntake(learner.getId(), session.getId()).status());
        assertEquals(DiagnosticStatus.READY_FOR_TASK,
                service.finishIntake(learner.getId(), session.getId()).status());
    }

    @Test
    void cannotReadAnotherLearnersSession() {
        var stranger = new Learner("Other");
        when(learners.findById(stranger.getId())).thenReturn(Optional.of(stranger));
        var session = new DiagnosticSession(instrument);
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));

        assertThrows(EntityNotFoundException.class,
                () -> service.get(stranger.getId(), session.getId()));
    }
}
