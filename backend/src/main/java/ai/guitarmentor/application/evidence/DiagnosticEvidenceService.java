package ai.guitarmentor.application.evidence;

import ai.guitarmentor.application.attempt.AttemptMediaStore;
import ai.guitarmentor.application.attempt.MediaTransactionGuard;
import ai.guitarmentor.application.attempt.MediaUseCoordinator;
import ai.guitarmentor.application.diagnostic.DiagnosticSessionService;
import ai.guitarmentor.application.evidence.EvidenceDtos.Capabilities;
import ai.guitarmentor.application.evidence.EvidenceDtos.EvidenceView;
import ai.guitarmentor.domain.diagnostic.DiagnosticStatus;
import ai.guitarmentor.infrastructure.media.DocumentMediaStore;
import ai.guitarmentor.infrastructure.persistence.DiagnosticEvidenceRepository;
import ai.guitarmentor.infrastructure.persistence.DiagnosticSessionRepository;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DiagnosticEvidenceService {
    private static final Logger log = LoggerFactory.getLogger(DiagnosticEvidenceService.class);
    private static final int MAX_ITEMS = 10;
    private final DiagnosticSessionService diagnostics;
    private final DiagnosticSessionRepository sessions;
    private final DiagnosticEvidenceRepository evidence;
    private final AttemptMediaStore audio;
    private final DocumentMediaStore scores;
    private final MediaTransactionGuard audioRollback;
    private final MediaUseCoordinator coordinator;
    private final boolean enabled;

    public DiagnosticEvidenceService(DiagnosticSessionService diagnostics,
            DiagnosticSessionRepository sessions, DiagnosticEvidenceRepository evidence,
            AttemptMediaStore audio, DocumentMediaStore scores, MediaTransactionGuard audioRollback,
            MediaUseCoordinator coordinator,
            @Value("$"+"{guitar-mentor.submissions.enabled:false}") boolean enabled) {
        this.diagnostics = diagnostics;
        this.sessions = sessions;
        this.evidence = evidence;
        this.audio = audio;
        this.scores = scores;
        this.audioRollback = audioRollback;
        this.coordinator = coordinator;
        this.enabled = enabled;
    }

    public Capabilities capabilities(UUID learnerId, UUID sessionId) {
        diagnostics.get(learnerId, sessionId);
        return new Capabilities(enabled, List.of("mp3","wav","ogg","webm"),
                AttemptMediaStore.MAX_BYTES, 120, List.of("pdf","jpg","jpeg","png"),
                DocumentMediaStore.MAX_BYTES, MAX_ITEMS);
    }

    @Transactional(readOnly = true)
    public List<EvidenceView> list(UUID learnerId, UUID sessionId) {
        diagnostics.get(learnerId, sessionId);
        return evidence.list(sessionId).stream().map(evidence::view).toList();
    }

    public record SubmitResult(EvidenceView evidence, boolean created) {}

    @Transactional(readOnly = true)
    public EvidenceView get(UUID learnerId, UUID sessionId, UUID evidenceId) {
        return list(learnerId, sessionId).stream().filter(e -> e.id().equals(evidenceId))
                .findFirst().orElseThrow(() -> rejected(HttpStatus.NOT_FOUND, "EVIDENCE_NOT_FOUND"));
    }

    @Transactional
    public SubmitResult submit(UUID learnerId, UUID sessionId, UUID requestId,
            String title, String composer, String comment,
            MultipartFile file, MultipartFile score) {
        if (!enabled) throw rejected(HttpStatus.FORBIDDEN, "UPLOAD_DISABLED");
        if (requestId == null) throw rejected(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_ID");

        // Hold the same local-file guard as #21 until transaction completion, not just method return.
        coordinator.acquire();
        try {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    coordinator.release();
                }
            });
        } catch (RuntimeException failure) {
            coordinator.release();
            throw failure;
        }
        diagnostics.get(learnerId, sessionId);
        var session = sessions.findLockedById(sessionId)
                .orElseThrow(() -> rejected(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND"));

        // Idempotency precedes the state check, so committed retries after continue are safe.
        var existing = evidence.byRequest(sessionId, requestId);
        if (existing.isPresent()) return new SubmitResult(evidence.view(existing.get()), false);
        if (session.getStatus() != DiagnosticStatus.COLLECTING_EVIDENCE)
            throw rejected(HttpStatus.CONFLICT, "INVALID_SESSION_STATE");
        if (evidence.count(sessionId) >= MAX_ITEMS)
            throw rejected(HttpStatus.CONFLICT, "MAX_ITEMS_REACHED");

        String cleanTitle = sanitize(title, 200, true, "INVALID_TITLE");
        String cleanComposer = sanitize(composer, 200, false, "INVALID_COMPOSER");
        String cleanComment = sanitize(comment, 2000, false, "INVALID_COMMENT");
        if (file == null || file.isEmpty())
            throw rejected(HttpStatus.BAD_REQUEST, "MISSING_AUDIO");

        var stored = audio.store(file);
        // Rollback compensation handles an invalid second file, flush failure and DB commit failure.
        audioRollback.register(stored.key(), requestId);
        DocumentMediaStore.Stored storedScore = null;
        if (score != null && !score.isEmpty()) {
            storedScore = scores.store(score);
            var key = storedScore.key();
            try {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        if (status != STATUS_ROLLED_BACK) return;
                        try { scores.delete(key); }
                        catch (IOException | RuntimeException failure) {
                            log.error("event=EVIDENCE_SCORE_ROLLBACK_FAILED requestId={} code=DELETE_IO_ERROR",
                                    requestId);
                        }
                    }
                });
            } catch (RuntimeException registrationFailure) {
                try { scores.delete(key); }
                catch (IOException ignored) {
                    log.error("event=EVIDENCE_SCORE_REGISTRATION_FAILED code=DELETE_IO_ERROR");
                }
                throw registrationFailure;
            }
        } else if (score != null) {
            throw rejected(HttpStatus.BAD_REQUEST, "INVALID_SCORE_SIZE");
        }

        UUID id = UUID.randomUUID();
        evidence.addEvidence(id, sessionId, requestId, cleanTitle, cleanComposer, cleanComment);
        evidence.addMedia(id, "AUDIO", stored.key(), stored.format(), stored.size(), "UPLOADED");
        if (storedScore != null) evidence.addMedia(id, "SCORE",
                storedScore.key(), storedScore.format(), storedScore.bytes(), "PROCESSED");
        return new SubmitResult(evidence.view(evidence.byRequest(sessionId, requestId).orElseThrow()), true);
    }

    private static ResponseStatusException rejected(HttpStatus status, String code) {
        return new ResponseStatusException(status, code);
    }
    private static String sanitize(String value, int max, boolean required, String errorCode) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.length() > max || (required && normalized.isBlank()))
            throw rejected(HttpStatus.BAD_REQUEST, errorCode);
        return normalized.isEmpty() ? null : normalized;
    }
}
