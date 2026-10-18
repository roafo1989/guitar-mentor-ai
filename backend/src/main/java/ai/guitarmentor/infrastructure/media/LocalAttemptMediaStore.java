package ai.guitarmentor.infrastructure.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Developer-only, private, on-disk storage. Never expose a media download endpoint
 * until user authentication and authorization are implemented. */
@Component
public class LocalAttemptMediaStore {
    public static final long MAX_BYTES = 20L * 1024L * 1024L;

    private final Path root;

    public LocalAttemptMediaStore(
            @Value("${guitar-mentor.submissions.storage-path:./.local-media/attempts}") String path) {
        this.root = Path.of(path).toAbsolutePath().normalize();
    }

    public record StoredMedia(String key, String format, long size) {}

    public StoredMedia store(MultipartFile file) {
        long size = file.getSize();
        if (size <= 0 || size > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Audio file must be between 1 byte and 20 MiB");
        }
        String filename = file.getOriginalFilename();
        if (filename == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Audio filename is missing");
        }
        var lower = filename.toLowerCase(Locale.ROOT);
        String format = null;
        for (var allowed : new String[] {"mp3", "wav", "ogg", "webm"}) {
            if (lower.endsWith("." + allowed)) {
                format = allowed;
                break;
            }
        }
        if (format == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only .mp3, .wav, .ogg or .webm are supported");
        }
        try {
            byte[] prefix;
            try (var in = file.getInputStream()) {
                prefix = in.readNBytes(16);
            }
            if (!matchesHeader(format, prefix)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Audio format does not match the file header");
            }
            Files.createDirectories(root);
            String key = UUID.randomUUID() + "." + format;
            Path destination = root.resolve(key).normalize();
            if (!destination.getParent().equals(root)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid media path");
            }
            try (var in = file.getInputStream();
                    var out = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE)) {
                in.transferTo(out);
            } catch (IOException problem) {
                Files.deleteIfExists(destination);
                throw problem;
            }
            if (Files.size(destination) != size) {
                Files.deleteIfExists(destination);
                throw new IOException("Uploaded size differs from the reported size");
            }
            return new StoredMedia(key, format, size);
        } catch (IOException problem) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not save audio in the local test storage", problem);
        }
    }

    private static boolean matchesHeader(String ext, byte[] prefix) {
        if (prefix.length < 4) return false;
        return switch (ext) {
            case "mp3" -> (prefix[0] == 'I' && prefix[1] == 'D' && prefix[2] == '3')
                    || ((prefix[0] & 0xff) == 0xff && (prefix[1] & 0xe0) == 0xe0);
            case "wav" -> prefix.length >= 12 && prefix[0] == 'R' && prefix[1] == 'I'
                    && prefix[2] == 'F' && prefix[3] == 'F'
                    && prefix[8] == 'W' && prefix[9] == 'A'
                    && prefix[10] == 'V' && prefix[11] == 'E';
            case "ogg" -> prefix[0] == 'O' && prefix[1] == 'g'
                    && prefix[2] == 'g' && prefix[3] == 'S';
            case "webm" -> (prefix[0] & 0xff) == 0x1a && (prefix[1] & 0xff) == 0x45
                    && (prefix[2] & 0xff) == 0xdf && (prefix[3] & 0xff) == 0xa3;
            default -> false;
        };
    }

    /**
     * Technical inspection only: checks stored length, container signature and SHA-256.
     * Does not decode audio, establish its duration or assess musical performance.
     */
    public String verifyAndHash(String key, long expectedSize) throws IOException {
        if (key == null || !key.matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(mp3|wav|ogg|webm)")) {
            throw new IOException("INVALID_KEY");
        }
        Path target = root.resolve(key).normalize();
        if (!target.getParent().equals(root) || !Files.isRegularFile(target)) {
            throw new IOException("MEDIA_MISSING");
        }
        if (Files.size(target) != expectedSize) {
            throw new IOException("SIZE_MISMATCH");
        }
        String format = key.substring(key.lastIndexOf('.') + 1);
        byte[] header;
        try (var in = Files.newInputStream(target)) {
            header = in.readNBytes(16);
        }
        if (!matchesHeader(format, header)) {
            throw new IOException("INVALID_FORMAT");
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var in = new DigestInputStream(Files.newInputStream(target), digest)) {
                in.transferTo(java.io.OutputStream.nullOutputStream());
            }
            if (Files.size(target) != expectedSize) {
                throw new IOException("SIZE_MISMATCH");
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unexpected) {
            throw new IllegalStateException("JVM is missing SHA-256", unexpected);
        }
    }

    public void deleteQuietly(String key) {
        try {
            Files.deleteIfExists(root.resolve(key).normalize());
        } catch (IOException ignored) {
            // TTL and reconciliation in R5 will handle persistent deletion failures.
        }
    }
}
