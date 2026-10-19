package ai.guitarmentor.application.attempt;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class MediaTransactionGuardTest {
    @Mock AttemptMediaStore media;

    @BeforeEach
    void beginTransactionSynchronization() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private void finish(int status) {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(callback -> callback.afterCompletion(status));
    }

    @Test
    void committedUploadMustNotBeDeleted() {
        new MediaTransactionGuard(media).register("committed.wav", UUID.randomUUID());
        finish(TransactionSynchronization.STATUS_COMMITTED);
        verifyNoInteractions(media);
    }

    @Test
    void confirmedRollbackDeletesOnlyItsNewKey() throws Exception {
        var request = UUID.randomUUID();
        new MediaTransactionGuard(media).register("new-file.wav", request);
        finish(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(media).delete("new-file.wav");
    }

    @Test
    void unknownOutcomeIsLoggedAndDoesNotDeletePotentiallyCommittedFile(
            CapturedOutput output) {
        var id = UUID.randomUUID();
        new MediaTransactionGuard(media).register("unknown-file.wav", id);
        finish(TransactionSynchronization.STATUS_UNKNOWN);
        verifyNoInteractions(media);
        assertTrue(output.getAll().contains("MEDIA_TRANSACTION_OUTCOME_UNKNOWN"));
        assertTrue(output.getAll().contains(id.toString()));
    }

    @Test
    void failedCompensationLogsSafeCodeWithoutOriginalPath(CapturedOutput output)
            throws Exception {
        var id = UUID.randomUUID();
        when(media.delete("rollback.wav")).thenThrow(new IOException("private/secret-path"));
        new MediaTransactionGuard(media).register("rollback.wav", id);
        finish(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertTrue(output.getAll().contains("MEDIA_ROLLBACK_DELETE_FAILED"));
        assertTrue(output.getAll().contains("DELETE_IO_ERROR"));
        assertTrue(!output.getAll().contains("private/secret-path"));
    }

    @Test
    void noTransactionNeverResultsInAnAcceptedUpload() throws Exception {
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThrows(IllegalStateException.class,
                () -> new MediaTransactionGuard(media).register("new-file.wav",
                        UUID.randomUUID()));
        verify(media).delete("new-file.wav");
    }
}
