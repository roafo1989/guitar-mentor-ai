package ai.guitarmentor.domain.attempt;

import ai.guitarmentor.domain.diagnostic.DiagnosticTask;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "submission_attempts")
public class SubmissionAttempt {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "diagnostic_task_id", nullable = false)
    private DiagnosticTask task;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AttemptStatus status;

    @Column(name = "student_comment", length = 2000)
    private String studentComment;

    @Column(name = "media_key", nullable = false, length = 100)
    private String mediaKey;

    @Column(name = "media_format", nullable = false, length = 10)
    private String mediaFormat;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    protected SubmissionAttempt() {}

    public SubmissionAttempt(DiagnosticTask task, UUID requestId, int attemptNumber,
            String studentComment, String mediaKey, String mediaFormat, long sizeBytes) {
        this.id = UUID.randomUUID();
        this.task = task;
        this.requestId = requestId;
        this.attemptNumber = attemptNumber;
        this.submittedAt = Instant.now();
        this.status = AttemptStatus.RECEIVED_UNASSESSED;
        this.studentComment = studentComment;
        this.mediaKey = mediaKey;
        this.mediaFormat = mediaFormat;
        this.sizeBytes = sizeBytes;
    }

    public UUID getId() { return id; }
    public DiagnosticTask getTask() { return task; }
    public UUID getRequestId() { return requestId; }
    public int getAttemptNumber() { return attemptNumber; }
    public Instant getSubmittedAt() { return submittedAt; }
    public AttemptStatus getStatus() { return status; }
    public String getStudentComment() { return studentComment; }
    public String getMediaKey() { return mediaKey; }
    public String getMediaFormat() { return mediaFormat; }
    public long getSizeBytes() { return sizeBytes; }
}
