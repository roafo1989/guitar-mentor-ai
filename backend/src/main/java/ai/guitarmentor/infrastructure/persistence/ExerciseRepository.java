package ai.guitarmentor.infrastructure.persistence;

import ai.guitarmentor.domain.exercise.Exercise;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExerciseRepository extends JpaRepository<Exercise, String> {
    List<Exercise> findByInstrumentCodeAndTechniqueCodeOrderByCode(
            String instrumentCode, String techniqueCode);
}
