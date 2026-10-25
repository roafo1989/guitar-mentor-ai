package ai.guitarmentor.application.diagnostic;

import ai.guitarmentor.domain.diagnostic.DiagnosticSession;
import ai.guitarmentor.domain.diagnostic.DiagnosticStatus;
import ai.guitarmentor.domain.learner.InstrumentProfile;
import ai.guitarmentor.infrastructure.persistence.DiagnosticSessionRepository;
import ai.guitarmentor.infrastructure.persistence.LearnerRepository;
import ai.guitarmentor.interfaces.rest.diagnostic.DiagnosticDtos.SessionView;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DiagnosticSessionService {

    private static final List<DiagnosticStatus> ACTIVE_STATUSES =
            List.of(DiagnosticStatus.COLLECTING_EVIDENCE, DiagnosticStatus.READY_FOR_TASK,
                    DiagnosticStatus.TASK_ASSIGNED);

    private final LearnerRepository learners;
    private final DiagnosticSessionRepository sessions;

    public DiagnosticSessionService(LearnerRepository learners, DiagnosticSessionRepository sessions) {
        this.learners = learners;
        this.sessions = sessions;
    }

    public record StartResult(SessionView session, boolean created) {}

    @Transactional
    public StartResult start(UUID learnerId, String instrumentCode) {
        // Lock the learner row to serialize concurrent starts for the same owner.
        var learner = learners.findLockedById(learnerId)
                .orElseThrow(() -> new EntityNotFoundException("Learner not found"));
        var profile = learner.getInstrumentProfiles().stream()
                .filter(p -> p.getInstrumentCode().equals(instrumentCode))
                .findFirst()
                .orElseThrow(() -> new EntityNotFoundException("Instrument profile not found"));

        var existing = sessions.findFirstByInstrumentProfile_IdAndStatusInOrderByCreatedAtDesc(
                profile.getId(), ACTIVE_STATUSES);
        if (existing.isPresent()) {
            return new StartResult(toView(existing.get()), false);
        }

        var saved = sessions.saveAndFlush(new DiagnosticSession(profile));
        return new StartResult(toView(saved), true);
    }

    /** Read-only lookup matching the idempotent start selection policy. */
    @Transactional(readOnly = true)
    public java.util.Optional<SessionView> current(UUID learnerId, String instrumentCode) {
        var learner = learners.findById(learnerId)
                .orElseThrow(() -> new EntityNotFoundException("Learner not found"));
        var profile = learner.getInstrumentProfiles().stream()
                .filter(p -> p.getInstrumentCode().equals(instrumentCode))
                .findFirst();
        if (profile.isEmpty()) return java.util.Optional.empty();
        return sessions.findFirstByInstrumentProfile_IdAndStatusInOrderByCreatedAtDesc(
                profile.get().getId(), ACTIVE_STATUSES).map(DiagnosticSessionService::toView);
    }

    @Transactional(readOnly = true)
    public SessionView get(UUID learnerId, UUID sessionId) {
        return toView(ownedSession(learnerId, sessionId, false));
    }

    @Transactional
    public SessionView finishIntake(UUID learnerId, UUID sessionId) {
        var session = ownedSession(learnerId, sessionId, true);
        session.finishIntake();
        return toView(session);
    }

    private DiagnosticSession ownedSession(UUID learnerId, UUID sessionId, boolean lock) {
        var learner = learners.findById(learnerId)
                .orElseThrow(() -> new EntityNotFoundException("Learner not found"));
        var session = (lock ? sessions.findLockedById(sessionId) : sessions.findById(sessionId))
                .orElseThrow(() -> new EntityNotFoundException("Diagnostic session not found"));
        var profileId = session.getInstrumentProfile().getId();
        boolean owned = learner.getInstrumentProfiles().stream()
                .map(InstrumentProfile::getId).anyMatch(profileId::equals);
        if (!owned) {
            throw new EntityNotFoundException("Diagnostic session not found");
        }
        return session;
    }

    private static SessionView toView(DiagnosticSession session) {
        var actions = switch (session.getStatus()) {
            case COLLECTING_EVIDENCE -> List.of("CONTINUE_WITHOUT_RECORDING");
            case READY_FOR_TASK -> List.of("ASSIGN_TASK");
            case TASK_ASSIGNED -> List.of("VIEW_TASK");
        };
        return new SessionView(
                session.getId(),
                session.getInstrumentProfile().getInstrumentCode(),
                session.getStatus(),
                session.getCreatedAt(),
                session.getUpdatedAt(),
                actions);
    }
}
