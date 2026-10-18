package ai.guitarmentor.interfaces.rest.onboarding;

import ai.guitarmentor.domain.onboarding.ExperienceLevel;
import ai.guitarmentor.domain.onboarding.GoalCode;
import ai.guitarmentor.domain.onboarding.SkillTag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;

public final class OnboardingDtos {
    private OnboardingDtos() {
    }

    public record KnownPieceInput(
            @NotBlank @Size(max = 120) String title,
            @NotNull @Size(max = 120) String artist) {
    }

    public record BackgroundRequest(
            @NotNull ExperienceLevel experienceLevel,
            @Min(0) @Max(60) int yearsPlaying,
            @NotNull Set<@NotNull SkillTag> skills,
            @NotNull @Size(max = 10) List<@Valid KnownPieceInput> knownPieces,
            @NotNull Set<@NotNull GoalCode> goals,
            @Min(10) @Max(120) int practiceMinutes,
            @Min(1) @Max(7) int sessionsPerWeek,
            @NotNull @Size(max = 1000) String notes) {
    }

    public record BackgroundView(
            ExperienceLevel experienceLevel,
            int yearsPlaying,
            Set<SkillTag> skills,
            List<KnownPieceInput> knownPieces,
            Set<GoalCode> goals,
            int practiceMinutes,
            int sessionsPerWeek,
            String notes) {
    }
}
