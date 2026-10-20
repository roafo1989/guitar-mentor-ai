package ai.guitarmentor.application.evidence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class EvidenceDtos {
    private EvidenceDtos() {}
    public record Capabilities(boolean enabled, List<String> audioFormats,
            long maxAudioBytes, int maxAudioSeconds, List<String> scoreFormats,
            long maxScoreBytes, int maxItems) {}
    public record MediaView(String format, long bytes, String status,
            BigDecimal durationSeconds, String errorCode, String mediaState) {}
    public record EvidenceView(UUID id, String title, String composer, String comment,
            Instant submittedAt, MediaView audio, MediaView score,
            String source, String skillEvidence) {}
}
