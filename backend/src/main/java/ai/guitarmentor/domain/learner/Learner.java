package ai.guitarmentor.domain.learner;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "learners")
public class Learner {

    @Id
    private UUID id;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "learner", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<InstrumentProfile> instrumentProfiles = new ArrayList<>();

    protected Learner() {
        // Required by JPA
    }

    public Learner(String displayName) {
        this.id = UUID.randomUUID();
        this.displayName = displayName;
        this.createdAt = Instant.now();
    }

    public void addInstrumentProfile(InstrumentProfile profile) {
        profile.assignLearner(this);
        instrumentProfiles.add(profile);
    }

    public UUID getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<InstrumentProfile> getInstrumentProfiles() {
        return List.copyOf(instrumentProfiles);
    }
}
