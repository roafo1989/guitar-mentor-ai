package ai.guitarmentor.infrastructure.persistence;

import ai.guitarmentor.domain.learner.Learner;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LearnerRepository extends JpaRepository<Learner, UUID> {
}
