package ai.guitarmentor.domain.diagnostic;

/**
 * READY_FOR_TASK means no exercise assigned. TASK_ASSIGNED means a single persisted task exists.
 * Task selection and submissions are implemented in later increments.
 */
public enum DiagnosticStatus {
    COLLECTING_EVIDENCE,
    READY_FOR_TASK,
    TASK_ASSIGNED
}
