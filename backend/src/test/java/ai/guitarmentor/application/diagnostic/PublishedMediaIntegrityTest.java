package ai.guitarmentor.application.diagnostic;
import static org.junit.jupiter.api.Assertions.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** Freeze the recovered original package bytes, never generate replacement music in CI. */
class PublishedMediaIntegrityTest {
    @Test void original08BMediaIsPackagedUnchanged() throws Exception {
        check("score_v08B.pdf", "c879a7a89c8766dfa0b0859220e0cfc182d7793880c911b77cc708d05a6da3d2");
        check("preview_v08B.mp3", "01db204253a4a76670f0a82e461d14808790ec35386812e36323e2603a5f7afa");
    }
    void check(String name, String hash) throws Exception {
        try (var stream = new ClassPathResource("exercises/GM-EB01/" + name).getInputStream()) {
            assertEquals(hash, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes())));
        }
    }
}
