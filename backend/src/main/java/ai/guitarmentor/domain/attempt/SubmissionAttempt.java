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

    @Column(name = "processing_started_at")
    private Instant processingStartedAt;

    @Column(name = "processing_finished_at")
    private Instant processingFinishedAt;

    @Column(name = "technical_sha256", length = 64)
    private String technicalSha256;

    @Column(name = "processing_error_code", length = 50)
    private String processingErrorCode;

    protected SubmissionAttempt() {}

    public SubmissionAttempt(DiagnosticTask task, UUID requestId, int attemptNumber,
            String studentComment, String mediaKey, String mediaFormat, long sizeBytes) {
        this.id = UUID.randomUUID();
        this.task = task;
        this.requestId = requestId;
        this.attemptNumber = attemptNumber;
        this.submittedAt = Instant.now();
        this.status = AttemptStatus.UPLOADED;
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
    public Instant getProcessingStartedAt() { return processingStartedAt; }
    public Instant getProcessingFinishedAt() { return processingFinishedAt; }
    public String getTechnicalSha256() { return technicalSha256; }
    public String getProcessingErrorCode() { return processingErrorCode; }

    public void startProcessing() {
        if (status != AttemptStatus.UPLOADED) throw new IllegalStateException("Attempt is not queued");
        status = AttemptStatus.PROCESSING;
        processingStartedAt = Instant.now();
        processingFinishedAt = null;
        processingErrorCode = null;
    }

    public void finishTechnicalValidation(String sha256) {
        if (status != AttemptStatus.PROCESSING) throw new IllegalStateException("Attempt not processing");
        if (sha256 == null || !sha256.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid sha256 digest");
        }
        status = AttemptStatus.PROCESSED;
        processingFinishedAt = Instant.now();
        technicalSha256 = sha256;
        processingErrorCode = null;
    }

    public void failTechnicalValidation(String code) {
        if (status != AttemptStatus.PROCESSING) throw new IllegalStateException("Attempt not processing");
        if (code == null || !code.matches("[A-Z_]{3,50}")) {
            throw new IllegalArgumentException("Invalid failure code");
        }
        status = AttemptStatus.FAILED;
        processingFinishedAt = Instant.now();
        processingErrorCode = code;
        technicalSha256 = null;
    }

    public void requeueAfterInterruptedProcessing() {
        if (status != AttemptStatus.PROCESSING) return;
        status = AttemptStatus.UPLOADED;
        processingStartedAt = null;
        processingFinishedAt = null;
        technicalSha256 = null;
        processingErrorCode = null;
    }
}
