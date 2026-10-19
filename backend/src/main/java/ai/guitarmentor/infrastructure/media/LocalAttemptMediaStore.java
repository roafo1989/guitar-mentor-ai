package ai.guitarmentor.infrastructure.media;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import ai.guitarmentor.application.attempt.AttemptMediaStore;
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
public class LocalAttemptMediaStore implements AttemptMediaStore {
    // Limit allocation and parsing for untrusted ISO BMFF compatible-brand tables.
    private static final int MAX_FTYP_BYTES = 64 * 1024;
    private static final String KEY_PATTERN =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(mp3|wav|ogg|webm|m4a)";

    private final Path root;

    public LocalAttemptMediaStore(
            @Value("${guitar-mentor.submissions.storage-path:./.local-media/attempts}") String path) {
        this.root = Path.of(path).toAbsolutePath().normalize();
    }

    @Override
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
        for (var allowed : new String[] {"mp3", "wav", "ogg", "webm", "m4a"}) {
            if (lower.endsWith("." + allowed)) {
                format = allowed;
                break;
            }
        }
        if (format == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only .mp3, .wav, .ogg, .webm or .m4a are supported");
        }
        try {
            byte[] prefix;
            try (var in = file.getInputStream()) {
                prefix = readHeader(format, in);
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
                byte[] chunk = new byte[8192];
                long written = 0;
                int count;
                while ((count = in.read(chunk)) != -1) {
                    written += count;
                    if (written > MAX_BYTES) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Audio file must be between 1 byte and 20 MiB");
                    }
                    out.write(chunk, 0, count);
                }
                if (written != size) {
                    throw new IOException("Uploaded size differs from the reported size");
                }
            } catch (IOException | ResponseStatusException problem) {
                Files.deleteIfExists(destination);
                throw problem;
            }
            return new StoredMedia(key, format, size);
        } catch (IOException problem) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not save audio in the local test storage", problem);
        }
    }

    /**
     * Read the complete ISO BMFF ftyp box. Its length is untrusted and
     * strictly bounded before allocating. Reject truncated boxes.
     */
    private static byte[] readHeader(String format, InputStream in) throws IOException {
        byte[] first = in.readNBytes(16);
        if (!"m4a".equals(format) || first.length < 16) {
            return first;
        }
        long boxLength = ftypBoxLength(first);
        if (boxLength < 16 || boxLength > MAX_FTYP_BYTES
                || (boxLength - 16) % 4 != 0) {
            return first; // matchesM4aHeader rejects the invalid length.
        }
        byte[] tail = in.readNBytes((int) boxLength - 16);
        byte[] header = Arrays.copyOf(first, 16 + tail.length);
        System.arraycopy(tail, 0, header, 16, tail.length);
        return header;
    }

    private static long ftypBoxLength(byte[] prefix) {
        return ((long) (prefix[0] & 0xff) << 24)
                | ((long) (prefix[1] & 0xff) << 16)
                | ((long) (prefix[2] & 0xff) << 8) | (prefix[3] & 0xff);
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
            case "m4a" -> matchesM4aHeader(prefix);
            default -> false;
        };
    }

    /**
     * ISO BMFF begins with a complete ftyp box: size, "ftyp", major brand,
     * minor version, then zero or more compatible four-character brands.
     * Audio-only/AAC and absence of video are verified separately by ffprobe.
     */
    private static boolean matchesM4aHeader(byte[] prefix) {
        if (prefix.length < 16 || prefix[4] != 'f' || prefix[5] != 't'
                || prefix[6] != 'y' || prefix[7] != 'p') return false;
        long boxLength = ftypBoxLength(prefix);
        if (boxLength < 16 || boxLength > MAX_FTYP_BYTES
                || boxLength != prefix.length || (boxLength - 16) % 4 != 0) {
            return false;
        }
        if (allowedM4aBrand(prefix, 8)) return true;
        for (int offset = 16; offset < boxLength; offset += 4) {
            if (allowedM4aBrand(prefix, offset)) return true;
        }
        return false;
    }

    private static boolean allowedM4aBrand(byte[] prefix, int offset) {
        String brand = new String(prefix, offset, 4, java.nio.charset.StandardCharsets.US_ASCII);
        return switch (brand) {
            case "M4A ", "isom", "iso2", "iso5", "iso6",
                    "3gp4", "3gp5", "3gp6", "mp41", "mp42" -> true;
            default -> false;
        };
    }

    /**
     * Technical inspection only: checks stored length, container signature and SHA-256.
     * Does not decode audio, establish its duration or assess musical performance.
     */
    @Override
    public String verifyAndHash(String key, long expectedSize) throws IOException {
        Path target = resolveForInspection(key);
        if (Files.size(target) != expectedSize) {
            throw new IOException("SIZE_MISMATCH");
        }
        String format = key.substring(key.lastIndexOf('.') + 1);
        byte[] header;
        try (var in = Files.newInputStream(target)) {
            header = readHeader(format, in);
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

    /** A single storage-key and root-boundary validation for reads, inspections and deletes. */
    private Path checkedPath(String key) throws IOException {
        if (key == null || !key.matches(KEY_PATTERN)) throw new IOException("INVALID_KEY");
        Path target = root.resolve(key).normalize();
        if (!root.equals(target.getParent())) throw new IOException("INVALID_KEY");
        return target;
    }

    private BasicFileAttributes attributes(Path target) throws IOException {
        return Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    /** Never return a directory or symbolic link to an external audio probe. */
    @Override
    public Path resolveForInspection(String key) throws IOException {
        Path target = checkedPath(key);
        try {
            if (!attributes(target).isRegularFile()) throw new IOException("INVALID_MEDIA");
        } catch (NoSuchFileException missing) {
            throw new IOException("MEDIA_MISSING");
        } catch (IOException problem) {
            if ("INVALID_MEDIA".equals(problem.getMessage())) throw problem;
            throw new IOException("IO_ERROR");
        }
        return target;
    }

    @Override
    public boolean isUnreferencedCandidateOld(String key,java.time.Instant cutoff)
            throws IOException {
        Path file=checkedPath(key);
        try {
            var attr=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            return attr.isRegularFile() && !attr.lastModifiedTime().toInstant().isAfter(cutoff);
        } catch(NoSuchFileException missing) {
            return false;
        }
    }
    @Override
    public java.util.List<OrphanCandidate> scanOlderThan(java.time.Instant cutoff,int limit)
            throws IOException {
        return scanOlderThan(cutoff,limit,null);
    }

    @Override
    public java.util.List<OrphanCandidate> scanOlderThan(java.time.Instant cutoff,int limit,
            String afterKey) throws IOException {
        if (limit <= 0 || !Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)) return java.util.List.of();
        // Bound memory to the requested page, irrespective of directory size.
        var page=new java.util.TreeMap<String,OrphanCandidate>();
        try (var stream=Files.list(root)) {
            for (var path:(Iterable<Path>)stream::iterator) {
                String key=path.getFileName().toString();
                if (!key.matches(KEY_PATTERN) || (afterKey!=null && key.compareTo(afterKey)<=0)) continue;
                var attr=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if (!attr.isRegularFile()) continue;
                var modified=attr.lastModifiedTime().toInstant();
                if (modified.isAfter(cutoff)) continue;
                page.put(key,new OrphanCandidate(key,modified));
                if (page.size()>limit) page.pollLastEntry();
            }
        }
        return java.util.List.copyOf(page.values());
    }

    /** Idempotent deletion with explicit errors. No recursion and no symlink following. */
    @Override
    public DeleteOutcome delete(String key) throws IOException {
        Path target = checkedPath(key);
        try {
            if (!attributes(target).isRegularFile()) throw new IOException("INVALID_MEDIA");
            Files.delete(target);
            return DeleteOutcome.DELETED;
        } catch (NoSuchFileException missing) {
            return DeleteOutcome.ALREADY_ABSENT;
        } catch (IOException problem) {
            if ("INVALID_MEDIA".equals(problem.getMessage())) throw problem;
            throw new IOException("DELETE_IO_ERROR");
        }
    }
}
