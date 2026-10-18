package ai.guitarmentor.infrastructure.persistence;

import ai.guitarmentor.domain.diagnostic.DiagnosticTask;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiagnosticTaskRepository extends JpaRepository<DiagnosticTask, UUID> {
    Optional<DiagnosticTask> findBySession_Id(UUID sessionId);
}
