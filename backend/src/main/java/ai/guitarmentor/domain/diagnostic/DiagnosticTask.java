package ai.guitarmentor.domain.diagnostic;

import ai.guitarmentor.domain.exercise.Exercise;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "diagnostic_tasks")
public class DiagnosticTask {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "diagnostic_session_id", nullable = false)
    private DiagnosticSession session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exercise_id", nullable = false)
    private Exercise exercise;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    protected DiagnosticTask() {}

    public DiagnosticTask(DiagnosticSession session, Exercise exercise) {
        this.id = UUID.randomUUID();
        this.session = session;
        this.exercise = exercise;
        this.assignedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public DiagnosticSession getSession() { return session; }
    public Exercise getExercise() { return exercise; }
    public Instant getAssignedAt() { return assignedAt; }
}
