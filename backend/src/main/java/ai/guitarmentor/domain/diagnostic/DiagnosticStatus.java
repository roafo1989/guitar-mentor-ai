package ai.guitarmentor.domain.diagnostic;

/**
 * READY_FOR_TASK does not imply that any exercise has been assigned.
 * Task selection and submissions are implemented in later increments.
 */
public enum DiagnosticStatus {
    COLLECTING_EVIDENCE,
    READY_FOR_TASK
}
