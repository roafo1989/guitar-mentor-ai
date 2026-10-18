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

}
