package ai.guitarmentor.domain.learner;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "instrument_profiles")
public class InstrumentProfile {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "learner_id", nullable = false)
    private Learner learner;

    @Column(name = "instrument_code", nullable = false, length = 50)
    private String instrumentCode;

    @Column(name = "technique_code", nullable = false, length = 50)
    private String techniqueCode;

    @ElementCollection
    @CollectionTable(name = "instrument_music_preferences",
            joinColumns = @JoinColumn(name = "instrument_profile_id"))
    private List<MusicPreference> preferences = new ArrayList<>();

    protected InstrumentProfile() {
        // Required by JPA
    }

    public InstrumentProfile(String instrumentCode, String techniqueCode) {
        this.id = UUID.randomUUID();
        this.instrumentCode = instrumentCode;
        this.techniqueCode = techniqueCode;
    }

    void assignLearner(Learner learner) {
        this.learner = learner;
    }

    public UUID getId() {
        return id;
    }

    public String getInstrumentCode() {
        return instrumentCode;
    }

    public String getTechniqueCode() {
        return techniqueCode;
    }

    public List<MusicPreference> getPreferences() {
        return List.copyOf(preferences);
    }

    public void replacePreferences(List<MusicPreference> updated) {
        preferences.clear();
        preferences.addAll(updated);
    }
}
