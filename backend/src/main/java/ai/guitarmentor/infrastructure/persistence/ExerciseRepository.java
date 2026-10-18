package ai.guitarmentor.infrastructure.persistence;

import ai.guitarmentor.domain.exercise.Exercise;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExerciseRepository extends JpaRepository<Exercise, UUID> {
    List<Exercise> findByInstrumentCodeAndTechniqueCodeAndCurrentTrueOrderByCode(
            String instrumentCode, String techniqueCode);

    Optional<Exercise> findByCodeAndCurrentTrue(String code);
}
