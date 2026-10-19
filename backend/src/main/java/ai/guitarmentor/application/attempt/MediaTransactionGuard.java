package ai.guitarmentor.application.attempt;

import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Compensates a new file only on confirmed rollback; recovery of unknown outcomes is #21. */
@Component
public class MediaTransactionGuard {
    private static final Logger log = LoggerFactory.getLogger(MediaTransactionGuard.class);
    private final AttemptMediaStore media;

    public MediaTransactionGuard(AttemptMediaStore media) {
        this.media = media;
    }

    public void register(String key, UUID requestId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            compensate(key, requestId, "REGISTRATION_FAILED");
            throw new IllegalStateException("Audio submission requires an active transaction");
        }
        try {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                // Rollback compensation must finish before the upload releases its media guard.
                @Override public int getOrder() { return Integer.MAX_VALUE - 1; }
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        compensate(key, requestId, "ROLLED_BACK");
                    } else if (status == STATUS_UNKNOWN) {
                        log.error("event=MEDIA_TRANSACTION_OUTCOME_UNKNOWN requestId={} phase=AFTER_COMPLETION",
                                requestId);
                    }
                }
            });
        } catch (RuntimeException registrationFailure) {
            compensate(key, requestId, "REGISTRATION_FAILED");
            throw registrationFailure;
        }
    }

    private void compensate(String key, UUID requestId, String phase) {
        try {
            media.delete(key);
        } catch (IOException | RuntimeException failure) {
            // Never include file paths, uploaded filenames, comments, bytes or stack traces.
            // Compensation failure must not override the original DB exception.
            log.error("event=MEDIA_ROLLBACK_DELETE_FAILED requestId={} phase={} code=DELETE_IO_ERROR",
                    requestId, phase);
        }
    }
}
