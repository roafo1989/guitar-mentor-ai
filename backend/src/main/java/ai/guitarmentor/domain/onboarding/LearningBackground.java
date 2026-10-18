package ai.guitarmentor.domain.onboarding;

import ai.guitarmentor.domain.learner.InstrumentProfile;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "learning_backgrounds")
public class LearningBackground {

    @Id
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instrument_profile_id", nullable = false, unique = true)
    private InstrumentProfile instrumentProfile;

    @Enumerated(EnumType.STRING)
    @Column(name = "experience_level", nullable = false, length = 20)
    private ExperienceLevel experienceLevel;

    @Column(name = "years_playing", nullable = false)
    private int yearsPlaying;

    @Column(name = "practice_minutes", nullable = false)
    private int practiceMinutes;

    @Column(name = "sessions_per_week", nullable = false)
    private int sessionsPerWeek;

    @Column(name = "notes", nullable = false, length = 1000)
    private String notes;

    @ElementCollection
    @Enumerated(EnumType.STRING)
    @CollectionTable(name = "background_skills",
            joinColumns = @JoinColumn(name = "background_id"))
    @Column(name = "skill_code", nullable = false, length = 50)
    private Set<SkillTag> skills = new HashSet<>();

    @ElementCollection
    @Enumerated(EnumType.STRING)
    @CollectionTable(name = "background_goals",
            joinColumns = @JoinColumn(name = "background_id"))
    @Column(name = "goal_code", nullable = false, length = 50)
    private Set<GoalCode> goals = new HashSet<>();

    @ElementCollection
    @CollectionTable(name = "background_known_pieces",
            joinColumns = @JoinColumn(name = "background_id"))
    private List<KnownPiece> knownPieces = new ArrayList<>();

    protected LearningBackground() {
    }

    public LearningBackground(InstrumentProfile instrumentProfile) {
        this.id = UUID.randomUUID();
        this.instrumentProfile = instrumentProfile;
    }

    public void update(ExperienceLevel experienceLevel, int yearsPlaying, Set<SkillTag> skills,
            List<KnownPiece> knownPieces, Set<GoalCode> goals,
            int practiceMinutes, int sessionsPerWeek, String notes) {
        this.experienceLevel = experienceLevel;
        this.yearsPlaying = yearsPlaying;
        this.practiceMinutes = practiceMinutes;
        this.sessionsPerWeek = sessionsPerWeek;
        this.notes = notes;
        this.skills.clear();
        this.skills.addAll(skills);
        this.goals.clear();
        this.goals.addAll(goals);
        this.knownPieces.clear();
        this.knownPieces.addAll(knownPieces);
    }

    public ExperienceLevel getExperienceLevel() { return experienceLevel; }
    public int getYearsPlaying() { return yearsPlaying; }
    public int getPracticeMinutes() { return practiceMinutes; }
    public int getSessionsPerWeek() { return sessionsPerWeek; }
    public String getNotes() { return notes; }
    public Set<SkillTag> getSkills() { return Set.copyOf(skills); }
    public Set<GoalCode> getGoals() { return Set.copyOf(goals); }
    public List<KnownPiece> getKnownPieces() { return List.copyOf(knownPieces); }
}
