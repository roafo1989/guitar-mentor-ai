package ai.guitarmentor.application.exercise;

import ai.guitarmentor.domain.exercise.Exercise;
import ai.guitarmentor.interfaces.rest.exercise.ExerciseView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Read-only, UUID-scoped criteria projection shared by catalog and assignments. */
@Component
public class ExerciseViewAssembler {
    private final NamedParameterJdbcTemplate jdbc;

    public ExerciseViewAssembler(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ExerciseView one(Exercise exercise) {
        return many(List.of(exercise)).getFirst();
    }

    public List<ExerciseView> many(List<Exercise> exercises) {
        if (exercises.isEmpty()) return List.of();
        var ids = exercises.stream().map(Exercise::getId).toList();
        Map<UUID, List<String>> criteria = new HashMap<>();
        jdbc.query("SELECT exercise_id, observable_result FROM exercise_criteria"
                        + " WHERE exercise_id IN (:ids) ORDER BY exercise_id,display_order,criterion_code",
                Map.of("ids", ids), rs -> {
                    UUID id = rs.getObject("exercise_id", UUID.class);
                    criteria.computeIfAbsent(id, ignored -> new ArrayList<>())
                            .add(rs.getString("observable_result"));
                });
        return exercises.stream()
                .map(exercise -> ExerciseView.from(exercise,
                        criteria.getOrDefault(exercise.getId(), List.of())))
                .toList();
    }
}
