package ai.guitarmentor.infrastructure.persistence;

import ai.guitarmentor.domain.attempt.SubmissionAttempt;
import ai.guitarmentor.domain.attempt.AttemptStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubmissionAttemptRepository extends JpaRepository<SubmissionAttempt, UUID> {
    List<SubmissionAttempt> findByTask_IdOrderByAttemptNumberDesc(UUID taskId);
    Optional<SubmissionAttempt> findByTask_IdAndRequestId(UUID taskId, UUID requestId);
    long countByTask_Id(UUID taskId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SubmissionAttempt> findFirstByStatusOrderBySubmittedAtAsc(AttemptStatus status);
    List<SubmissionAttempt> findByStatusAndProcessingStartedAtBefore(
            AttemptStatus status, java.time.Instant before);

    @org.springframework.data.jpa.repository.Query(value = """
        SELECT * FROM submission_attempts
        WHERE media_state='AVAILABLE'
          AND ((processing_finalized_at IS NOT NULL AND processing_finalized_at <= :finalCutoff)
            OR (processing_finalized_at IS NULL AND submitted_at <= :unprocessedCutoff))
        ORDER BY submitted_at ASC LIMIT 50
        """,nativeQuery=true)
    java.util.List<SubmissionAttempt> findEligibleForCleanup(
        @org.springframework.data.repository.query.Param("finalCutoff") java.time.Instant finalCutoff,
        @org.springframework.data.repository.query.Param("unprocessedCutoff") java.time.Instant unprocessedCutoff);
    boolean existsByMediaKey(String mediaKey);
    java.util.List<SubmissionAttempt> findTop50ByMediaStateOrderByMediaDeleteAttemptsAscSubmittedAtAsc(
        ai.guitarmentor.domain.attempt.MediaState state);
    java.util.List<SubmissionAttempt> findTop100ByMediaStateAndSubmittedAtBeforeOrderBySubmittedAtAsc(
        ai.guitarmentor.domain.attempt.MediaState state, java.time.Instant cutoff);
}
