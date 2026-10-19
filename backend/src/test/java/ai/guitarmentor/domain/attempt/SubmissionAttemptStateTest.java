package ai.guitarmentor.domain.attempt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.guitarmentor.domain.diagnostic.DiagnosticTask;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SubmissionAttemptStateTest {
    private SubmissionAttempt newAttempt() {
        return new SubmissionAttempt(Mockito.mock(DiagnosticTask.class), UUID.randomUUID(),
                1, "test", UUID.randomUUID() + ".wav", "wav", 100L);
    }

    @Test
    void uploadsAreNotGradedAndTransitionToTechnicalValidation() {
        var attempt = newAttempt();
        assertEquals(AttemptStatus.UPLOADED, attempt.getStatus());
        assertThrows(IllegalStateException.class,
                () -> attempt.finishTechnicalValidation("a".repeat(64), 2.5));
        attempt.startProcessing();
        assertEquals(AttemptStatus.PROCESSING, attempt.getStatus());
        assertNotNull(attempt.getProcessingStartedAt());
        attempt.finishTechnicalValidation("a".repeat(64), 2.5);
        assertEquals(AttemptStatus.PROCESSED, attempt.getStatus());
        assertEquals("a".repeat(64), attempt.getTechnicalSha256());
        assertNotNull(attempt.getProcessingFinishedAt());
        assertEquals(0, attempt.getAudioDurationSeconds().compareTo(
                new java.math.BigDecimal("2.500")));
        assertEquals(1, attempt.getProcessingAttempts());
        assertThrows(IllegalStateException.class, attempt::startProcessing);
    }

    @Test
    void failuresHaveSafeCodeButNoChecksum() {
        var attempt = newAttempt();
        attempt.startProcessing();
        attempt.failTechnicalValidation("MEDIA_MISSING");
        assertEquals(AttemptStatus.FAILED, attempt.getStatus());
        assertEquals("MEDIA_MISSING", attempt.getProcessingErrorCode());
        assertNotNull(attempt.getProcessingFinishedAt());
        assertFalse(attempt.getTechnicalSha256() != null);
    }

    @Test
    void interruptedJobFailsAfterThreeClaimsInsteadOfRetryingForever() {
        var attempt = newAttempt();
        for (int i = 0; i < 2; i++) {
            attempt.startProcessing();
            attempt.requeueAfterInterruptedProcessing();
            assertEquals(AttemptStatus.UPLOADED, attempt.getStatus());
        }
        attempt.startProcessing();
        attempt.requeueAfterInterruptedProcessing();
        assertEquals(AttemptStatus.FAILED, attempt.getStatus());
        assertEquals("PROCESSING_INTERRUPTED", attempt.getProcessingErrorCode());
        assertEquals(3, attempt.getProcessingAttempts());
    }

    @Test
    void finalizationIsIdempotentAndPendingDeletionDoesNotEraseHistory() {
        var attempt=newAttempt();
        assertThrows(IllegalStateException.class,
            ()->attempt.finalizeProcessing(java.time.Instant.now(),"TECHNICAL_ONLY_V1"));
        attempt.startProcessing();
        attempt.finishTechnicalValidation("a".repeat(64),2.0);
        var first=attempt.getProcessingFinalizedAt();
        attempt.finalizeProcessing(first.plusSeconds(500),"TECHNICAL_ONLY_V1");
        assertEquals(first,attempt.getProcessingFinalizedAt());
        attempt.markDeletePending("PROCESSED_TTL");
        attempt.markDeleteFailure("DELETE_IO_ERROR");
        assertEquals(MediaState.DELETE_PENDING,attempt.getMediaState());
        assertEquals(null,attempt.getMediaDeletedAt());
        attempt.markDeleted(first.plusSeconds(172800),false);
        assertEquals(MediaState.DELETED,attempt.getMediaState());
        assertEquals(2,attempt.getMediaDeleteAttempts());
        assertEquals(AttemptStatus.PROCESSED,attempt.getStatus());
        assertEquals("test",attempt.getStudentComment());
        assertEquals("PROCESSED_TTL",attempt.getMediaDeleteReason());
        assertEquals("DELETED",attempt.getMediaDeleteOutcome());
    }

    @Test
    void alreadyAbsentDoesNotOverwriteOriginalRetentionReason() {
        var attempt=newAttempt();
        assertThrows(IllegalStateException.class,()->attempt.markDeletePending("UNPROCESSED_TTL"));
        attempt.expireUnprocessed(java.time.Instant.now());
        attempt.markDeletePending("UNPROCESSED_TTL");
        attempt.markDeleted(java.time.Instant.now(),true);
        assertEquals("UNPROCESSED_TTL",attempt.getMediaDeleteReason());
        assertEquals("ALREADY_ABSENT",attempt.getMediaDeleteOutcome());
    }

    @Test
    void unprocessedExpiryTerminatesWithoutInventingAssessment() {
        var attempt=newAttempt();
        attempt.expireUnprocessed(java.time.Instant.now());
        assertEquals(AttemptStatus.FAILED,attempt.getStatus());
        assertEquals("RETENTION_EXPIRED",attempt.getProcessingErrorCode());
        assertNotNull(attempt.getProcessingFinalizedAt());
    }

    @Test
    void interruptedJobCanBeQueuedAgain() {
        var attempt = newAttempt();
        attempt.startProcessing();
        attempt.requeueAfterInterruptedProcessing();
        assertEquals(AttemptStatus.UPLOADED, attempt.getStatus());
        assertEquals(null, attempt.getProcessingStartedAt());
    }
}
