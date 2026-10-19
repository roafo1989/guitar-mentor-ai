package ai.guitarmentor.application.attempt;

import java.io.IOException;
import java.nio.file.Path;
import org.springframework.web.multipart.MultipartFile;

/** Internal storage port shared by upload and technical processing. No public download API. */
public interface AttemptMediaStore {
    long MAX_BYTES = 20L * 1024 * 1024;

    record StoredMedia(String key, String format, long size) {}
    enum DeleteOutcome { DELETED, ALREADY_ABSENT }

    StoredMedia store(MultipartFile file);
    String verifyAndHash(String key, long expectedSize) throws IOException;
    Path resolveForInspection(String key) throws IOException;
    DeleteOutcome delete(String key) throws IOException;
    record OrphanCandidate(String key, java.time.Instant modifiedAt) {}
    default java.util.List<OrphanCandidate> scanOlderThan(java.time.Instant cutoff, int limit)
            throws IOException { return java.util.List.of(); }
}
