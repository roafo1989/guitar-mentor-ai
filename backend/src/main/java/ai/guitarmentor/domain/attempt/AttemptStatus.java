package ai.guitarmentor.domain.attempt;

/**
 * Transport/technical processing, NOT a music assessment.
 * PROCESSED only means that stored file bytes passed basic integrity checks.
 */
public enum AttemptStatus {
    UPLOADED,
    PROCESSING,
    PROCESSED,
    FAILED
}
