package ai.guitarmentor.interfaces.rest.learner;

import ai.guitarmentor.domain.learner.PreferenceRating;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public final class LearnerDtos {

    private LearnerDtos() {
    }

    public record PreferenceInput(
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,39}") String styleCode,
            @NotNull PreferenceRating rating) {
    }

    public record CreateLearnerRequest(
            @NotBlank @Size(max = 100) String displayName,
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,49}") String instrumentCode,
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,49}") String techniqueCode,
            @NotNull @Size(max = 40) List<@Valid PreferenceInput> preferences) {
    }

    public record UpdatePreferencesRequest(
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,49}") String instrumentCode,
            @NotNull @Size(max = 40) List<@Valid PreferenceInput> preferences) {
    }

    public record PreferenceView(String styleCode, PreferenceRating rating) {
    }

    public record InstrumentView(
            UUID id, String instrumentCode, String techniqueCode, List<PreferenceView> preferences) {
    }

    public record LearnerView(UUID id, String displayName, List<InstrumentView> instruments) {
    }
}
