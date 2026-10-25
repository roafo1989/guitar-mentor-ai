package ai.guitarmentor.domain.diagnostic;

import ai.guitarmentor.domain.learner.InstrumentProfile;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "diagnostic_sessions")
public class DiagnosticSession {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instrument_profile_id", nullable = false)
    private InstrumentProfile instrumentProfile;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private DiagnosticStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected DiagnosticSession() {
        // Required by JPA
    }

    public DiagnosticSession(InstrumentProfile instrumentProfile) {
        this.id = UUID.randomUUID();
        this.instrumentProfile = Objects.requireNonNull(instrumentProfile);
        this.status = DiagnosticStatus.COLLECTING_EVIDENCE;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public void finishIntake() {
        if (status == DiagnosticStatus.READY_FOR_TASK || status == DiagnosticStatus.TASK_ASSIGNED) {
            return; // Repeating the command is safe.
        }
        if (status != DiagnosticStatus.COLLECTING_EVIDENCE) {
            throw new IllegalStateException("Cannot finish intake in status " + status);
        }
        status = DiagnosticStatus.READY_FOR_TASK;
        updatedAt = Instant.now();
    }

    public void markTaskAssigned() {
        if (status != DiagnosticStatus.READY_FOR_TASK) {
            throw new IllegalStateException("Cannot assign exercise in status " + status);
        }
        status = DiagnosticStatus.TASK_ASSIGNED;
        updatedAt = Instant.now();
    }

    /** Technical history state; never changes the old task's pedagogical outcome. */
    public void archive() {
        if (status != DiagnosticStatus.TASK_ASSIGNED)
            throw new IllegalStateException("Only assigned sessions can be archived");
        status = DiagnosticStatus.ARCHIVED;
        updatedAt = Instant.now();
    }

    /** Internal successor creation; bypasses intake but preserves one task per session. */
    public void prepareNextLearningTask() {
        if (status != DiagnosticStatus.COLLECTING_EVIDENCE)
            throw new IllegalStateException("New session already initialized");
        status = DiagnosticStatus.READY_FOR_TASK;
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public InstrumentProfile getInstrumentProfile() { return instrumentProfile; }
    public DiagnosticStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
