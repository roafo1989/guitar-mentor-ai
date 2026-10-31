package ai.guitarmentor.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Runs the pinned alphaTab renderer as an unprivileged, network-isolated Docker process.
 * The caller owns the returned workspace and must delete it after consuming both outputs.
 */
@Component
public final class LibraryConversionProcessRunner {
    private static final long MAX_SOURCE_BYTES = 20L * 1024 * 1024;
    private static final long MAX_OUTPUT_BYTES = 80L * 1024 * 1024;
    private static final Duration WATCHDOG = Duration.ofSeconds(60);
    private final String dockerExecutable;
    private final String image;
    private final Path workspaceRoot;
    private final ObjectMapper mapper;

    public LibraryConversionProcessRunner(
            @Value("${guitar-mentor.library.conversion-docker-executable:docker}") String dockerExecutable,
            @Value("${guitar-mentor.library.conversion-image:gm-conversion-worker:170b}") String image,
            @Value("${guitar-mentor.library.conversion-workspace:./.local-media/conversion-work}") String workspaceRoot,
            ObjectMapper mapper) {
        this.dockerExecutable = dockerExecutable;
        this.image = image;
        this.workspaceRoot = Path.of(workspaceRoot).toAbsolutePath().normalize();
        this.mapper = mapper;
        if (dockerExecutable == null || dockerExecutable.isBlank()
                || image == null || !image.matches("[a-zA-Z0-9._/:@-]{1,160}"))
            throw new IllegalArgumentException("Invalid conversion worker configuration");
    }

    public record TrackCandidate(int trackIndex, String name, boolean guitar, String instrument) {}
    public record WorkerResult(String status, String errorCode, List<TrackCandidate> tracks,
            Integer selectedTrackIndex, String selectedTrackName, String sourceSha256,
            long pdfBytes, long wavBytes, double wavDurationSeconds, Path workspace,
            Path pdfPath, Path wavPath) {
        public boolean rendered() { return "RENDERED".equals(status); }
        public boolean trackSelectionRequired() { return "TRACK_SELECTION_REQUIRED".equals(errorCode); }
        public boolean cancelled() { return "CANCELLED".equals(status); }
    }

    public WorkerResult execute(Path source, String format, String expectedSha256,
            Integer selectedTrackIndex, BooleanSupplier cancelRequested) throws IOException, InterruptedException {
        if (!List.of("GP3", "GP4", "GP5").contains(format))
            return failure("UNSUPPORTED_FORMAT");
        if (source == null || Files.isSymbolicLink(source)
                || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
            return failure("SOURCE_UNAVAILABLE");
        long sourceSize = Files.size(source);
        if (sourceSize < 1) return failure("EMPTY_SOURCE");
        if (sourceSize > MAX_SOURCE_BYTES) return failure("SOURCE_TOO_LARGE");
        String actualSha = sha256(source);
        if (expectedSha256 == null || !MessageDigest.isEqual(
                actualSha.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                expectedSha256.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
            return failure("SOURCE_HASH_MISMATCH");

        Files.createDirectories(workspaceRoot);
        if (Files.isSymbolicLink(workspaceRoot) || !Files.isDirectory(workspaceRoot, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Unsafe conversion workspace root");
        Path workspace = Files.createTempDirectory(workspaceRoot, "job-");
        Path inputDir = Files.createDirectory(workspace.resolve("input"));
        Path outputDir = Files.createDirectory(workspace.resolve("output"));
        Path inputFile = inputDir.resolve("source.gp");
        setPermissions(inputDir, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE));
        Files.copy(source, inputFile, StandardCopyOption.COPY_ATTRIBUTES);
        setPermissions(inputFile, EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ));
        setPermissions(outputDir, EnumSet.allOf(PosixFilePermission.class));
        Path cidFile = workspace.resolve("container.id");
        Path resultFile = outputDir.resolve("result.json");

        List<String> command = new ArrayList<>(List.of(
                dockerExecutable, "run", "--rm", "--pull=never",
                "--cidfile", cidFile.toString(),
                "--network", "none",
                "--memory", "512m", "--memory-swap", "512m",
                "--cpus", "1", "--pids-limit", "64",
                "--read-only", "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges:true",
                "--tmpfs", "/tmp:rw,noexec,nosuid,size=100m",
                "--user", "10001:10001",
                "--mount", "type=bind,source=" + inputDir + ",target=/input,readonly",
                "--mount", "type=bind,source=" + outputDir + ",target=/out",
                "--env", "GM_CONVERSION_FORMAT=" + format));
        if (selectedTrackIndex != null)
            command.addAll(List.of("--env", "GM_CONVERSION_TRACK_INDEX=" + selectedTrackIndex));
        command.add(image);

        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            long deadline = System.nanoTime() + WATCHDOG.toNanos();
            while (process.isAlive()) {
                if (cancelRequested != null && cancelRequested.getAsBoolean()) {
                    stopContainer(cidFile);
                    process.destroyForcibly();
                    process.waitFor();
                    deleteWorkspace(workspace);
                    return new WorkerResult("CANCELLED", "CANCELLED_BY_USER", List.of(),
                            null, null, actualSha, 0, 0, 0, null, null, null);
                }
                if (System.nanoTime() >= deadline) {
                    stopContainer(cidFile);
                    process.destroyForcibly();
                    process.waitFor();
                    deleteWorkspace(workspace);
                    return failure("TIMED_OUT");
                }
                process.waitFor(200, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            int exit = process.exitValue();
            if (!Files.isRegularFile(resultFile, LinkOption.NOFOLLOW_LINKS)) {
                deleteWorkspace(workspace);
                return failure(exit == 0 ? "WORKER_RESULT_MISSING" : "WORKER_FAILED");
            }
            JsonNode result = mapper.readTree(resultFile.toFile());
            String status = result.path("status").asText("ERROR");
            String error = result.path("code").asText(null);
            List<TrackCandidate> tracks = new ArrayList<>();
            if (result.path("tracks").isArray()) {
                for (JsonNode item : result.path("tracks")) {
                    tracks.add(new TrackCandidate(item.path("trackIndex").asInt(-1),
                            safeName(item.path("name").asText("Guitar")),
                            item.path("guitar").asBoolean(false),
                            safeName(item.path("instrument").asText("Guitar"))));
                }
            }
            if ("ERROR".equals(status)) {
                deleteWorkspace(workspace);
                return new WorkerResult(status, error == null ? "WORKER_FAILED" : error,
                        List.copyOf(tracks), null, null, actualSha, 0, 0, 0, null, null, null);
            }
            if (exit != 0 || !"RENDERED".equals(status)) {
                deleteWorkspace(workspace);
                return failure("WORKER_PROTOCOL_ERROR");
            }
            Path pdf = outputDir.resolve("score.pdf");
            Path wav = outputDir.resolve("preview.wav");
            validateOutput(pdf, "%PDF");
            validateOutput(wav, "RIFF");
            long pdfBytes = Files.size(pdf), wavBytes = Files.size(wav);
            if (pdfBytes > MAX_OUTPUT_BYTES || wavBytes > MAX_OUTPUT_BYTES) {
                deleteWorkspace(workspace);
                return failure("OUTPUT_TOO_LARGE");
            }
            return new WorkerResult(status, null, List.copyOf(tracks),
                    result.path("selectedTrackIndex").asInt(), safeName(result.path("selectedTrackName").asText("Guitar")),
                    actualSha, pdfBytes, wavBytes, result.path("wavDurationSeconds").asDouble(),
                    workspace, pdf, wav);
        } catch (IOException | RuntimeException | InterruptedException error) {
            if (process != null && process.isAlive()) process.destroyForcibly();
            stopContainer(cidFile);
            deleteWorkspace(workspace);
            if (error instanceof InterruptedException interrupted) throw interrupted;
            if (error instanceof IOException io) throw io;
            throw new IOException("Conversion worker failed", error);
        }
    }

    public void deleteWorkspace(Path workspace) throws IOException {
        if (workspace == null || !workspace.normalize().startsWith(workspaceRoot)
                || Files.isSymbolicLink(workspace)) throw new IOException("Invalid conversion workspace");
        if (!Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(workspace)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("Symlink in conversion workspace");
                Files.deleteIfExists(path);
            }
        }
    }

    private void stopContainer(Path cidFile) {
        try {
            if (!Files.isRegularFile(cidFile, LinkOption.NOFOLLOW_LINKS)) return;
            String id = Files.readString(cidFile).trim();
            if (!id.matches("[a-f0-9]{12,64}")) return;
            new ProcessBuilder(dockerExecutable, "kill", id)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // A subsequent lease recovery will reclaim the job; never expose Docker diagnostics.
        }
    }

    private static WorkerResult failure(String code) {
        return new WorkerResult("ERROR", code, List.of(), null, null, null,
                0, 0, 0, null, null, null);
    }

    private static String safeName(String value) {
        return value.replaceAll("[\\p{Cntrl}]", "").substring(0, Math.min(value.length(), 80));
    }

    private static String sha256(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (int count; (count = in.read(buffer)) >= 0;) if (count > 0) digest.update(buffer, 0, count);
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void validateOutput(Path file, String signature) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)
                || Files.size(file) < 1000) throw new IOException("Invalid conversion output");
        byte[] header = new byte[4];
        try (var input = Files.newInputStream(file)) {
            if (input.read(header) != header.length) throw new IOException("Invalid conversion output");
        }
        if (!signature.equals(new String(header, java.nio.charset.StandardCharsets.US_ASCII)))
            throw new IOException("Invalid conversion output signature");
        if ("RIFF".equals(signature)) {
            try (var input = Files.newInputStream(file)) {
                input.skipNBytes(8);
                byte[] wave = input.readNBytes(4);
                if (!"WAVE".equals(new String(wave, java.nio.charset.StandardCharsets.US_ASCII)))
                    throw new IOException("Invalid WAV container");
            }
        }
    }

    private static void setPermissions(Path path, java.util.Set<PosixFilePermission> permissions) throws IOException {
        try {
            if (Files.getFileStore(path).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Docker Desktop/Windows uses host ACLs; the container remains unprivileged.
        }
    }
}
