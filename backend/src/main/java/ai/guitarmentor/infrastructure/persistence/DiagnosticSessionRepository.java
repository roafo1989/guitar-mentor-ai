package ai.guitarmentor.infrastructure.persistence;

import ai.guitarmentor.domain.diagnostic.DiagnosticSession;
import ai.guitarmentor.domain.diagnostic.DiagnosticStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DiagnosticSessionRepository extends JpaRepository<DiagnosticSession, UUID> {

    Optional<DiagnosticSession> findFirstByInstrumentProfile_IdAndStatusInOrderByCreatedAtDesc(
            UUID instrumentProfileId, Collection<DiagnosticStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from DiagnosticSession session where session.id = :id")
    Optional<DiagnosticSession> findLockedById(@Param("id") UUID id);
}
