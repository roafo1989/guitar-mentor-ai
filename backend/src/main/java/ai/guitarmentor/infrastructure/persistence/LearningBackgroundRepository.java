package ai.guitarmentor.infrastructure.persistence;

import ai.guitarmentor.domain.onboarding.LearningBackground;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LearningBackgroundRepository extends JpaRepository<LearningBackground, UUID> {
    Optional<LearningBackground> findByInstrumentProfile_Id(UUID instrumentProfileId);
}
