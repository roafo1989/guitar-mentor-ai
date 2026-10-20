package ai.guitarmentor.infrastructure.media;

import ai.guitarmentor.application.attempt.AttemptMediaStore.DeleteOutcome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Private uploads; these files are never made into public score previews. */
@Component
public class DocumentMediaStore {
    public static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final String KEY_PATTERN =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(pdf|jpg|jpeg|png)";
    private final Path root;
    public record Stored(String key, String format, long bytes) {}

    public DocumentMediaStore(@Value("$"+"{guitar-mentor.submissions.score-storage-path:./.local-media/scores}")
            String path) {
        root = Path.of(path).toAbsolutePath().normalize();
    }

    public Stored store(MultipartFile file) {
        if (file == null || file.getSize() <= 0 || file.getSize() > MAX_BYTES)
            throw invalid("INVALID_SCORE_SIZE");
        String original = file.getOriginalFilename();
        String format = original == null || !original.contains(".") ? "" :
                original.substring(original.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!List.of("pdf", "jpg", "jpeg", "png").contains(format))
            throw invalid("UNSUPPORTED_SCORE_FORMAT");
        try {
            byte[] magic;
            try (var stream = file.getInputStream()) { magic = stream.readNBytes(12); }
            if (!hasSignature(format, magic)) throw invalid("INVALID_SCORE_CONTENT");
            Files.createDirectories(root);
            String key = UUID.randomUUID() + "." + format;
            Path destination = checked(key);
            try (var input = file.getInputStream();
                 var output = Files.newOutputStream(destination,
                         StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                input.transferTo(output);
            } catch (IOException failure) {
                Files.deleteIfExists(destination);
                throw failure;
            }
            if (Files.size(destination) != file.getSize()) {
                Files.deleteIfExists(destination);
                throw new IOException("SIZE_MISMATCH");
            }
            return new Stored(key, format, file.getSize());
        } catch (IOException io) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE");
        }
    }

    public static boolean hasSignature(String format, byte[] p) {
        if (p.length < 8) return false;
        return switch (format) {
            case "pdf" -> p[0]=='%' && p[1]=='P' && p[2]=='D' && p[3]=='F' && p[4]=='-';
            case "png" -> (p[0] & 255)==137 && p[1]==80 && p[2]==78 && p[3]==71
                    && p[4]==13 && p[5]==10 && p[6]==26 && p[7]==10;
            case "jpg", "jpeg" -> (p[0] & 255)==255 && (p[1] & 255)==216
                    && (p[2] & 255)==255;
            default -> false;
        };
    }
    private static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
    private Path checked(String key) throws IOException {
        if (key == null || !key.matches(KEY_PATTERN)) throw new IOException("INVALID_KEY");
        Path path = root.resolve(key).normalize();
        if (!root.equals(path.getParent())) throw new IOException("INVALID_KEY");
        return path;
    }
    public DeleteOutcome delete(String key) throws IOException {
        Path path = checked(key);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS))
            return DeleteOutcome.ALREADY_ABSENT;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("INVALID_MEDIA");
        Files.delete(path);
        return DeleteOutcome.DELETED;
    }
    public List<String> oldCandidates(Instant cutoff) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
        var result = new ArrayList<String>();
        try (var items = Files.list(root)) {
            for (Path file : items.toList()) {
                String key = file.getFileName().toString();
                if (!key.matches(KEY_PATTERN) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    continue;
                if (Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS)
                        .toInstant().isBefore(cutoff)) result.add(key);
            }
        }
        return result;
    }
    public boolean stillOld(String key, Instant cutoff) throws IOException {
        Path path = checked(key);
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS)
                        .toInstant().isBefore(cutoff);
    }
}
