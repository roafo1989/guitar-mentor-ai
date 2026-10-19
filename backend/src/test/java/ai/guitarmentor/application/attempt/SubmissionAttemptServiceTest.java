package ai.guitarmentor.application.attempt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import ai.guitarmentor.application.diagnostic.DiagnosticTaskService;
import ai.guitarmentor.domain.attempt.SubmissionAttempt;
import ai.guitarmentor.domain.diagnostic.DiagnosticSession;
import ai.guitarmentor.domain.diagnostic.DiagnosticTask;
import ai.guitarmentor.application.attempt.AttemptMediaStore;
import ai.guitarmentor.infrastructure.persistence.DiagnosticSessionRepository;
import ai.guitarmentor.infrastructure.persistence.DiagnosticTaskRepository;
import ai.guitarmentor.infrastructure.persistence.SubmissionAttemptRepository;
import ai.guitarmentor.interfaces.rest.diagnostic.TaskDtos.TaskView;
import jakarta.persistence.EntityNotFoundException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class SubmissionAttemptServiceTest {
    @Mock DiagnosticTaskService taskService;
    @Mock DiagnosticTaskRepository tasks;
    @Mock DiagnosticSessionRepository sessions;
    @Mock SubmissionAttemptRepository attempts;
    @Mock AttemptMediaStore media;
    @Mock MediaTransactionGuard guard;
    @Mock DiagnosticTask task;
    @Mock DiagnosticSession session;

    private UUID learnerId;
    private UUID sessionId;
    private UUID taskId;
    private MockMultipartFile file;

    @BeforeEach
    void setup() {
        learnerId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        taskId = UUID.randomUUID();
        file = new MockMultipartFile("file", "recording.wav", "audio/wav",
                new byte[] {'R','I','F','F', 4, 0, 0, 0, 'W','A','V','E'});
    }

    private SubmissionAttemptService create(boolean enabled) {
        return new SubmissionAttemptService(taskService, tasks, sessions, attempts, media, guard, enabled);
    }

    @Test
    void uploadIsDisabledByDefaultAndDoesNotAccessMedia() {
        assertThrows(ResponseStatusException.class,
                () -> create(false).submit(learnerId, sessionId, taskId, UUID.randomUUID(), "", file));
        verifyNoInteractions(media);
    }

    @Test
    void rejectsForeignTask() {
        when(taskService.current(learnerId, sessionId)).thenReturn(Optional.empty());
        assertThrows(EntityNotFoundException.class,
                () -> create(true).submit(learnerId, sessionId, taskId, UUID.randomUUID(), "", file));
        verifyNoInteractions(media);
    }

    @Test
    void retryWithSameRequestIdDoesNotDuplicateSubmission() {
        when(taskService.current(learnerId, sessionId)).thenReturn(
                Optional.of(new TaskView(taskId, sessionId, Instant.now(), null)));
        when(sessions.findLockedById(sessionId)).thenReturn(Optional.of(session));
        when(tasks.findById(taskId)).thenReturn(Optional.of(task));
        when(task.getId()).thenReturn(taskId);
        UUID requestId = UUID.randomUUID();
        var created = new AtomicReference<SubmissionAttempt>();
        when(attempts.findByTask_IdAndRequestId(taskId, requestId))
                .thenAnswer(i -> Optional.ofNullable(created.get()));
        when(attempts.countByTask_Id(taskId)).thenReturn(0L);
        when(media.store(file)).thenReturn(new AttemptMediaStore.StoredMedia(
                "generated.wav", "wav", file.getSize()));
        when(attempts.saveAndFlush(any(SubmissionAttempt.class))).thenAnswer(invocation -> {
            var attempt = invocation.<SubmissionAttempt>getArgument(0);
            created.set(attempt);
            return attempt;
        });
        var service = create(true);
        var first = service.submit(learnerId, sessionId, taskId, requestId, " first try ", file);
        var repeat = service.submit(learnerId, sessionId, taskId, requestId, " first try ", file);
        assertNotNull(first.id());
        assertEquals(first.id(), repeat.id());
        assertEquals(1, first.number());
        assertEquals("first try", first.comment());
        assertEquals(false, first.assessmentAvailable());
    }
}
