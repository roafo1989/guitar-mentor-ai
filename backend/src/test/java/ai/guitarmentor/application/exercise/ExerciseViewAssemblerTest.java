package ai.guitarmentor.application.exercise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import ai.guitarmentor.domain.exercise.Exercise;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Verifies batching, UUID isolation, metadata nullability and stable ordering. */
class ExerciseViewAssemblerTest {
    @Test
    void catalogLoadsCriteriaOnceAndKeepsThemBoundToExerciseId() throws Exception {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var first = mock(Exercise.class);
        var second = mock(Exercise.class);
        UUID firstId = UUID.randomUUID(), secondId = UUID.randomUUID();
        when(first.getId()).thenReturn(firstId);
        when(second.getId()).thenReturn(secondId);
        when(first.getCode()).thenReturn("ONE");
        when(second.getCode()).thenReturn("TWO");
        when(first.getLearningGoal()).thenReturn("Practice steady pulse");
        when(first.getRecommendedPracticeMinutes()).thenReturn(30);
        when(first.getSkillCodes()).thenReturn(java.util.Set.of());
        when(second.getSkillCodes()).thenReturn(java.util.Set.of());
        doAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(2);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject("exercise_id", UUID.class)).thenReturn(firstId, secondId, firstId);
            when(rs.getString("observable_result")).thenReturn("First", "Other", "Second");
            handler.processRow(rs);
            handler.processRow(rs);
            handler.processRow(rs);
            return null;
        }).when(jdbc).query(anyString(), any(java.util.Map.class), any(RowCallbackHandler.class));

        var views = new ExerciseViewAssembler(jdbc).many(List.of(first, second));
        assertEquals(List.of("First", "Second"), views.get(0).successCriteria());
        assertEquals(List.of("Other"), views.get(1).successCriteria());
        assertEquals("Practice steady pulse", views.get(0).learningGoal());
        assertEquals(30, views.get(0).recommendedPracticeMinutes());
        assertNull(views.get(1).learningGoal());
        verify(jdbc, times(1)).query(anyString(), any(java.util.Map.class), any(RowCallbackHandler.class));
    }

    @Test
    void emptyCatalogDoesNotHitDatabase() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        assertEquals(List.of(), new ExerciseViewAssembler(jdbc).many(List.of()));
        verifyNoInteractions(jdbc);
    }
}
