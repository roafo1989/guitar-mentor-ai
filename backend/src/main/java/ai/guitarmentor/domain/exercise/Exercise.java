package ai.guitarmentor.domain.exercise;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.Set;

@Entity
@Table(name = "exercises")
public class Exercise {
    @Id
    private UUID id;
    @Column(nullable = false)
    private String code;
    @Column(name = "is_current", nullable = false)
    private boolean current;
    @Column(nullable = false)
    private String version;
    @Column(nullable = false)
    private String title;
    @Column(name = "instrument_code", nullable = false)
    private String instrumentCode;
    @Column(name = "technique_code", nullable = false)
    private String techniqueCode;
    @Column(name = "style_code", nullable = false)
    private String styleCode;
    @Column(nullable = false)
    private String difficulty;
    @Column(nullable = false)
    private int bpm;
    @Column(nullable = false)
    private int measures;
    @Column(nullable = false)
    private String meter;
    @Column(nullable = false)
    private String author;
    @Column(nullable = false)
    private String license;
    @Column(name = "review_status", nullable = false)
    private String reviewStatus;
    @Column(nullable = false)
    private String description;
    @Column(name = "score_resource", nullable = false)
    private String scoreResource;
    @Column(name = "events_resource", nullable = false)
    private String eventsResource;

    @Column(name = "preview_audio_resource")
    private String previewAudioResource;
    @Column(name = "preview_pdf_resource")
    private String previewPdfResource;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "exercise_skills", joinColumns = @JoinColumn(name = "exercise_id"))
    @Column(name = "skill_code")
    private Set<String> skillCodes = new LinkedHashSet<>();

    protected Exercise() {}

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public boolean isCurrent() { return current; }
    public String getVersion() { return version; }
    public String getTitle() { return title; }
    public String getInstrumentCode() { return instrumentCode; }
    public String getTechniqueCode() { return techniqueCode; }
    public String getStyleCode() { return styleCode; }
    public String getDifficulty() { return difficulty; }
    public int getBpm() { return bpm; }
    public int getMeasures() { return measures; }
    public String getMeter() { return meter; }
    public String getAuthor() { return author; }
    public String getLicense() { return license; }
    public String getReviewStatus() { return reviewStatus; }
    public String getDescription() { return description; }
    public String getScoreResource() { return scoreResource; }
    public String getEventsResource() { return eventsResource; }
    public String getPreviewAudioResource() { return previewAudioResource; }
    public String getPreviewPdfResource() { return previewPdfResource; }
    public Set<String> getSkillCodes() { return Set.copyOf(skillCodes); }
}
