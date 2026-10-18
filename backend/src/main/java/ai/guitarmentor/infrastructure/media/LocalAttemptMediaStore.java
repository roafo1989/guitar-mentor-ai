package ai.guitarmentor.infrastructure.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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

    public void deleteQuietly(String key) {
        try {
            Files.deleteIfExists(root.resolve(key).normalize());
        } catch (IOException ignored) {
            // TTL and reconciliation in R5 will handle persistent deletion failures.
        }
    }
}
