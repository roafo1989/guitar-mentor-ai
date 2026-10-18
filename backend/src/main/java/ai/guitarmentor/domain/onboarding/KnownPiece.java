package ai.guitarmentor.domain.onboarding;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class KnownPiece {
    @Column(name = "piece_title", nullable = false, length = 120)
    private String title;

    @Column(name = "piece_artist", nullable = false, length = 120)
    private String artist;

    protected KnownPiece() {
    }

    public KnownPiece(String title, String artist) {
        this.title = title;
        this.artist = artist;
    }

    public String getTitle() {
        return title;
    }

    public String getArtist() {
        return artist;
    }
}
