package ai.guitarmentor.infrastructure.persistence;

import ai.guitarmentor.domain.learner.Learner;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LearnerRepository extends JpaRepository<Learner, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select learner from Learner learner where learner.id = :id")
    Optional<Learner> findLockedById(@Param("id") UUID id);
}
