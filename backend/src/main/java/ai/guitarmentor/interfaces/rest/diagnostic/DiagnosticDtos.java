package ai.guitarmentor.interfaces.rest.diagnostic;

import ai.guitarmentor.domain.diagnostic.DiagnosticStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DiagnosticDtos {
    private DiagnosticDtos() {}

    public record StartRequest(
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,49}") String instrumentCode) {
    }

    public record SessionView(
            UUID id,
            String instrumentCode,
            DiagnosticStatus status,
            Instant createdAt,
            Instant updatedAt,
            List<String> availableActions) {
    }
}
