package ai.guitarmentor.application.attempt;

import ai.guitarmentor.application.diagnostic.DiagnosticTaskService;
import ai.guitarmentor.domain.attempt.SubmissionAttempt;
import ai.guitarmentor.application.attempt.AttemptMediaStore;
import ai.guitarmentor.infrastructure.persistence.DiagnosticSessionRepository;
import ai.guitarmentor.infrastructure.persistence.DiagnosticTaskRepository;
import ai.guitarmentor.infrastructure.persistence.SubmissionAttemptRepository;
import ai.guitarmentor.interfaces.rest.attempt.AttemptDtos.AttemptView;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SubmissionAttemptService {
    private final DiagnosticTaskService taskService;
    private final DiagnosticTaskRepository tasks;
    private final DiagnosticSessionRepository sessions;
    private final SubmissionAttemptRepository attempts;
    private final AttemptMediaStore media;
    private final MediaTransactionGuard transactions;
    private final boolean enabled;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MediaUseCoordinator mediaCoordinator;

    public SubmissionAttemptService(DiagnosticTaskService taskService, DiagnosticTaskRepository tasks,
            DiagnosticSessionRepository sessions, SubmissionAttemptRepository attempts,
            AttemptMediaStore media, MediaTransactionGuard transactions,
            @Value("${guitar-mentor.submissions.enabled:false}") boolean enabled) {
        this.taskService = taskService;
        this.tasks = tasks;
        this.sessions = sessions;
        this.attempts = attempts;
        this.media = media;
        this.transactions = transactions;
        this.enabled = enabled;
    }

    public boolean isEnabled() { return enabled; }

    @Transactional(readOnly = true)
    public List<AttemptView> list(UUID learnerId, UUID sessionId, UUID taskId) {
        checkOwnership(learnerId, sessionId, taskId);
        return attempts.findByTask_IdOrderByAttemptNumberDesc(taskId).stream()
                .map(AttemptView::from).toList();
    }

    @Transactional
    public AttemptView submit(UUID learnerId, UUID sessionId, UUID taskId,
            UUID requestId, String comment, MultipartFile file) {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Uploads are disabled. Enable them only for local test files.");
        }
        if (mediaCoordinator != null) {
            mediaCoordinator.acquire();
            try {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCompletion(int status) {
                            mediaCoordinator.release();
                        }
                    });
            } catch (RuntimeException failure) {
                mediaCoordinator.release();
                throw failure;
            }
        }
        checkOwnership(learnerId, sessionId, taskId);
        // Lock the session to serialize attempt numbers and idempotent upload keys.
        sessions.findLockedById(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("Session not found"));
        var prior = attempts.findByTask_IdAndRequestId(taskId, requestId);
        if (prior.isPresent()) return AttemptView.from(prior.get());
        String cleanComment = comment == null ? null : comment.trim();
        if (cleanComment != null && cleanComment.length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Comment too long");
        }
        var task = tasks.findById(taskId)
                .orElseThrow(() -> new EntityNotFoundException("Diagnostic task not found"));
        var stored = media.store(file);
        // Registration before saveAndFlush also covers rollback at the later commit boundary.
        transactions.register(stored.key(), requestId);
        var attempt = new SubmissionAttempt(task, requestId,
                Math.toIntExact(attempts.countByTask_Id(taskId) + 1),
                cleanComment, stored.key(), stored.format(), stored.size());
        return AttemptView.from(attempts.saveAndFlush(attempt));
    }

    private void checkOwnership(UUID learnerId, UUID sessionId, UUID taskId) {
        var current = taskService.current(learnerId, sessionId)
                .orElseThrow(() -> new EntityNotFoundException("Task not found"));
        if (!current.id().equals(taskId)) {
            throw new EntityNotFoundException("Task not found");
        }
    }
}
