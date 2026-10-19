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

    @Column(name = "audio_duration_seconds", precision = 9, scale = 3)
    private java.math.BigDecimal audioDurationSeconds;

    @Column(name = "processing_attempts", nullable = false)
    private int processingAttempts;
    @Column(name = "processing_plan", nullable = false)
    private String processingPlan = "TECHNICAL_ONLY_V1";
    @Column(name = "processing_finalized_at")
    private Instant processingFinalizedAt;
    @Enumerated(EnumType.STRING)
    @Column(name = "media_state", nullable = false)
    private MediaState mediaState = MediaState.AVAILABLE;
    @Column(name = "media_deleted_at")
    private Instant mediaDeletedAt;
    @Column(name = "media_delete_attempts", nullable = false)
    private int mediaDeleteAttempts;
    @Column(name = "media_delete_error_code")
    private String mediaDeleteErrorCode;
    @Column(name = "media_delete_reason")
    private String mediaDeleteReason;

    protected SubmissionAttempt() {}

    public SubmissionAttempt(DiagnosticTask task, UUID requestId, int attemptNumber,
            String studentComment, String mediaKey, String mediaFormat, long sizeBytes) {
        this.id = UUID.randomUUID();
        this.task = task;
        this.requestId = requestId;
        this.attemptNumber = attemptNumber;
        this.submittedAt = Instant.now();
        this.status = AttemptStatus.UPLOADED;
        this.processingPlan = "TECHNICAL_ONLY_V1";
        this.mediaState = MediaState.AVAILABLE;
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
    public java.math.BigDecimal getAudioDurationSeconds() { return audioDurationSeconds; }
    public int getProcessingAttempts() { return processingAttempts; }
    public String getProcessingPlan() { return processingPlan; }
    public Instant getProcessingFinalizedAt() { return processingFinalizedAt; }
    public MediaState getMediaState() { return mediaState; }
    public Instant getMediaDeletedAt() { return mediaDeletedAt; }
    public int getMediaDeleteAttempts() { return mediaDeleteAttempts; }
    public void finalizeProcessing(Instant now, String expectedPlan) {
        if (!processingPlan.equals(expectedPlan) || !"TECHNICAL_ONLY_V1".equals(expectedPlan))
            throw new IllegalStateException("Unsupported media plan");
        if (status != AttemptStatus.PROCESSED && status != AttemptStatus.FAILED)
            throw new IllegalStateException("Not terminal");
        if (processingFinalizedAt == null) processingFinalizedAt = now;
    }
    public void expireUnprocessed(Instant now) {
        if (status == AttemptStatus.UPLOADED) {
            status = AttemptStatus.FAILED;
            processingFinishedAt = now;
            processingErrorCode = "RETENTION_EXPIRED";
            finalizeProcessing(now, "TECHNICAL_ONLY_V1");
        } else if (status == AttemptStatus.PROCESSING) {
            failTechnicalValidation("RETENTION_EXPIRED");
        }
    }
    public void markDeletePending(String reason) {
        if (mediaState == MediaState.AVAILABLE) {
            mediaState = MediaState.DELETE_PENDING;
            mediaDeleteReason = reason;
        }
    }
    public void markDeleteFailure(String code) {
        if (mediaState != MediaState.DELETE_PENDING) throw new IllegalStateException("Not pending");
        mediaDeleteAttempts++;
        mediaDeleteErrorCode = code;
    }
    public void markDeleted(Instant at, boolean alreadyAbsent) {
        if (mediaState != MediaState.DELETE_PENDING) throw new IllegalStateException("Not pending");
        mediaDeleteAttempts++;
        mediaState = MediaState.DELETED;
        mediaDeletedAt = at;
        mediaDeleteErrorCode = null;
        if (alreadyAbsent) mediaDeleteReason = "MISSING_FILE";
    }

    public void startProcessing() {
        if (status != AttemptStatus.UPLOADED) throw new IllegalStateException("Attempt is not queued");
        status = AttemptStatus.PROCESSING;
        processingAttempts++;
        processingStartedAt = Instant.now();
        processingFinishedAt = null;
        processingErrorCode = null;
    }

    public void finishTechnicalValidation(String sha256, double audioDuration) {
        if (status != AttemptStatus.PROCESSING) throw new IllegalStateException("Attempt not processing");
        if (sha256 == null || !sha256.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid sha256 digest");
        }
        if (!Double.isFinite(audioDuration) || audioDuration <= 0 || audioDuration > 120.0) {
            throw new IllegalArgumentException("Invalid media duration");
        }
        audioDurationSeconds = java.math.BigDecimal.valueOf(audioDuration).setScale(
                3, java.math.RoundingMode.HALF_UP);
        status = AttemptStatus.PROCESSED;
        processingFinishedAt = Instant.now();
        finalizeProcessing(processingFinishedAt, "TECHNICAL_ONLY_V1");
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
        finalizeProcessing(processingFinishedAt, "TECHNICAL_ONLY_V1");
        processingErrorCode = code;
        technicalSha256 = null;
        audioDurationSeconds = null;
    }

    public void requeueAfterInterruptedProcessing() {
        if (status != AttemptStatus.PROCESSING) return;
        if (processingAttempts >= 3) {
            failTechnicalValidation("PROCESSING_INTERRUPTED");
            return;
        }
        status = AttemptStatus.UPLOADED;
        processingStartedAt = null;
        processingFinishedAt = null;
        technicalSha256 = null;
        processingErrorCode = null;
    }
}
