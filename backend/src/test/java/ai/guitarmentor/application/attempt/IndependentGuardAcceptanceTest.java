package ai.guitarmentor.application.attempt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;
import org.springframework.transaction.support.*;

@ExtendWith(OutputCaptureExtension.class)
class IndependentGuardAcceptanceTest {
    @BeforeEach void begin() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }
    @AfterEach void end() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    @Test void unknownOutcomeHasOnlySafeEvent(CapturedOutput output) {
        var media = mock(AttemptMediaStore.class);
        var requestId = UUID.randomUUID();
        new MediaTransactionGuard(media).register("PRIVATE_FILENAME_MARKER.wav", requestId);
        TransactionSynchronizationManager.getSynchronizations().getFirst().afterCompletion(TransactionSynchronization.STATUS_UNKNOWN);
        verifyNoInteractions(media);
        String event = output.getOut().lines().filter(s -> s.contains("MEDIA_TRANSACTION_OUTCOME_UNKNOWN")).findFirst().orElseThrow();
        assertTrue(event.contains(requestId.toString()));
        assertTrue(event.contains("phase=AFTER_COMPLETION"));
        assertFalse(event.contains("PRIVATE_FILENAME_MARKER"));
        assertFalse(event.contains("Exception"));
    }
    @Test void registrationFailureCompensatesWithoutReplacingOriginalFailure(CapturedOutput output) throws Exception {
        var media = mock(AttemptMediaStore.class);
        var requestId = UUID.randomUUID();
        var original = new IllegalStateException("REGISTRATION_TEST_FAILURE");
        doThrow(new IOException("PRIVATE_PATH_MARKER")).when(media).delete("PRIVATE_FILENAME_MARKER.wav");
        try (var mocked = mockStatic(TransactionSynchronizationManager.class, invocation -> {
            if ("registerSynchronization".equals(invocation.getMethod().getName())) throw original;
            return invocation.callRealMethod();
        })) {
            assertSame(original, assertThrows(IllegalStateException.class,
                    () -> new MediaTransactionGuard(media).register("PRIVATE_FILENAME_MARKER.wav", requestId)));
        }
        verify(media).delete("PRIVATE_FILENAME_MARKER.wav");
        String event = output.getOut().lines().filter(s -> s.contains("MEDIA_ROLLBACK_DELETE_FAILED")).findFirst().orElseThrow();
        assertTrue(event.contains(requestId.toString()));
        assertTrue(event.contains("phase=REGISTRATION_FAILED"));
        assertTrue(event.contains("code=DELETE_IO_ERROR"));
        assertFalse(event.contains("PRIVATE_PATH_MARKER"));
        assertFalse(event.contains("PRIVATE_FILENAME_MARKER"));
    }
}
