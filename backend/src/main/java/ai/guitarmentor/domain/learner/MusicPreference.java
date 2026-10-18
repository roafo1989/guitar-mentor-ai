package ai.guitarmentor.domain.learner;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.util.Objects;

@Embeddable
public class MusicPreference {

    @Column(name = "style_code", nullable = false, length = 40)
    private String styleCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "rating", nullable = false, length = 10)
    private PreferenceRating rating;

    protected MusicPreference() {
        // Required by JPA
    }

    public MusicPreference(String styleCode, PreferenceRating rating) {
        this.styleCode = Objects.requireNonNull(styleCode);
        this.rating = Objects.requireNonNull(rating);
    }

    public String getStyleCode() {
        return styleCode;
    }

    public PreferenceRating getRating() {
        return rating;
    }
}
