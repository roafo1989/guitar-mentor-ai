package ai.guitarmentor.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Runs the pinned renderer with bounded input, scratch, protocol and output. No shell or network. */
@Component
public final class LibraryConversionProcessRunner {
    private static final long MAX_SOURCE_BYTES = 20L * 1024 * 1024;
    private static final int MAX_OUTPUT_BYTES = 20 * 1024 * 1024;
    private final String dockerExecutable;
    private final String image;
    private final Path workspaceRoot;
    private final Duration watchdog;
    private final ObjectMapper mapper = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Autowired
    public LibraryConversionProcessRunner(
            @Value("${guitar-mentor.library.conversion-docker-executable:docker}") String dockerExecutable,
            @Value("${guitar-mentor.library.conversion-image:gm-conversion-worker:170e}") String image,
            @Value("${guitar-mentor.library.conversion-workspace:./.local-media/conversion-work}") String workspaceRoot) {
        this(dockerExecutable, image, workspaceRoot, Duration.ofSeconds(60));
    }

    // Short watchdogs are injected only by fault tests, never by runtime configuration.
    LibraryConversionProcessRunner(String dockerExecutable, String image, String workspaceRoot, Duration watchdog) {
        this.dockerExecutable = dockerExecutable;
        this.image = image;
        this.workspaceRoot = Path.of(workspaceRoot).toAbsolutePath().normalize();
        this.watchdog = watchdog;
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
        if (format == null || !List.of("GP3", "GP4", "GP5").contains(format)) return failure("UNSUPPORTED_FORMAT");
        if (source == null || Files.isSymbolicLink(source) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
            return failure("SOURCE_UNAVAILABLE");
        long sourceSize = Files.size(source);
        if (sourceSize < 1) return failure("EMPTY_SOURCE");
        if (sourceSize > MAX_SOURCE_BYTES) return failure("SOURCE_TOO_LARGE");
        String actualSha = sha256(source);
        if (!actualSha.equals(expectedSha256)) return failure("SOURCE_HASH_MISMATCH");
        rejectSymlinkComponents(workspaceRoot);
        Files.createDirectories(workspaceRoot);
        Path workspace = Files.createTempDirectory(workspaceRoot, "job-");
        String containerName = "gm-conversion-" + UUID.randomUUID();
        Process process = null;
        boolean retain = false;
        try {
            Path inputDir = Files.createDirectory(workspace.resolve("input"));
            Path outputDir = Files.createDirectory(workspace.resolve("output"));
            Path inputFile = inputDir.resolve("source.gp");
            permissions(inputDir, "rwxr-xr-x");
            // Copy with a byte cap even if the source changes after stat(). Never follow a changed symlink.
            try (var input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
                 var out = Files.newOutputStream(inputFile)) {
                byte[] buffer = new byte[8192]; long total = 0;
                for (int count; (count = input.read(buffer)) != -1;) {
                    total += count;
                    if (total > MAX_SOURCE_BYTES) return failure("SOURCE_TOO_LARGE");
                    out.write(buffer, 0, count);
                }
            }
            if (!actualSha.equals(sha256(inputFile))) return failure("SOURCE_CHANGED_DURING_COPY");
            permissions(inputFile, "r--r--r--");
            if (inputDir.toString().contains(",")) throw new IOException("Workspace path cannot contain commas");
            List<String> command = new ArrayList<>(List.of(dockerExecutable, "run", "--rm", "--pull=never",
                "--name", containerName, "--network", "none", "--memory", "512m", "--memory-swap", "512m",
                "--cpus", "1", "--pids-limit", "64", "--read-only", "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges:true", "--tmpfs", "/scratch:rw,noexec,nosuid,size=100m,mode=1777",
                "--user", "10001:10001", "--mount", "type=bind,source=" + inputDir + ",target=/input,readonly",
                "--env", "GM_CONVERSION_FORMAT=" + format, "--env", "GM_CONVERSION_STREAM=1"));
            if (selectedTrackIndex != null)
                command.addAll(List.of("--env", "GM_CONVERSION_TRACK_INDEX=" + selectedTrackIndex));
            command.add(image);
            try {
                process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            } catch (IOException error) { return failure("WORKER_UNAVAILABLE"); }
            Process child = process;
            // Drain stdout while the process runs; validate lengths BEFORE writing or allocating.
            FutureTask<Void> reader = new FutureTask<>(() -> { readBundle(child.getInputStream(), outputDir); return null; });
            Thread readerThread = new Thread(reader, "conversion-output-reader");
            readerThread.setDaemon(true);
            readerThread.start();
            long deadline = System.nanoTime() + watchdog.toNanos();
            while (process.isAlive()) {
                if (cancelRequested != null && cancelRequested.getAsBoolean())
                    return new WorkerResult("CANCELLED", "CANCELLED_BY_USER", List.of(), null, null,
                        actualSha, 0, 0, 0, null, null, null);
                if (System.nanoTime() >= deadline) return failure("TIMED_OUT");
                if (reader.isDone()) {
                    try { reader.get(); }
                    catch (java.util.concurrent.ExecutionException error) { return failure("WORKER_PROTOCOL_ERROR"); }
                }
                process.waitFor(100, TimeUnit.MILLISECONDS);
            }
            int exit = process.exitValue();
            if (exit == 124 || exit == 137) return failure(exit == 124 ? "TIMED_OUT" : "WORKER_RESOURCE_LIMIT");
            try { reader.get(2, TimeUnit.SECONDS); }
            catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException error) {
                return failure(exit == 125 || exit == 127 ? "WORKER_UNAVAILABLE" : "WORKER_PROTOCOL_ERROR");
            }
            JsonNode result = mapper.readTree(outputDir.resolve("result.json").toFile());
            if (result == null || !result.isObject()) return failure("WORKER_PROTOCOL_ERROR");
            String status = result.path("status").asText("");
            String error = result.path("code").asText("WORKER_FAILED");
            List<TrackCandidate> tracks = new ArrayList<>();
            if (result.path("tracks").isArray()) {
                if (result.path("tracks").size() > 32) return failure("WORKER_PROTOCOL_ERROR");
                java.util.Set<Integer> indices = new java.util.HashSet<>();
                for (JsonNode item : result.path("tracks")) {
                    int index = item.path("trackIndex").asInt(-1);
                    if (index < 0 || index >= 32 || !indices.add(index) || !item.path("guitar").asBoolean(false))
                        return failure("WORKER_PROTOCOL_ERROR");
                    tracks.add(new TrackCandidate(index, safeName(item.path("name").asText("Guitar")), true,
                        safeName(item.path("instrument").asText("Guitar"))));
                }
            }
            if ("ERROR".equals(status)) {
                if (!error.matches("[A-Z0-9_]{1,64}") || ("TRACK_SELECTION_REQUIRED".equals(error) && tracks.size() < 2))
                    return failure("WORKER_PROTOCOL_ERROR");
                return new WorkerResult(status, error, List.copyOf(tracks), null, null, actualSha, 0, 0, 0, null, null, null);
            }
            if (exit != 0 || !"RENDERED".equals(status)) return failure("WORKER_PROTOCOL_ERROR");
            int selectedIndex = result.path("selectedTrackIndex").asInt(-1);
            String selectedName = safeName(result.path("selectedTrackName").asText(""));
            double duration = result.path("wavDurationSeconds").asDouble(-1);
            if (!format.equals(result.path("format").asText()) || !actualSha.equals(result.path("sourceSha256").asText())
                    || !actualSha.equals(result.path("scoreRevision").asText()) || selectedIndex < 0 || selectedIndex >= 32
                    || (selectedTrackIndex != null && selectedIndex != selectedTrackIndex) || selectedName.isBlank()
                    || !Double.isFinite(duration) || duration <= 0 || duration > 120)
                return failure("WORKER_RESULT_MISMATCH");
            Path pdf = outputDir.resolve("score.pdf"), wav = outputDir.resolve("preview.wav");
            validateOutput(pdf, "%PDF");
            validateOutput(wav, "RIFF");
            long pdfBytes = Files.size(pdf), wavBytes = Files.size(wav);
            if (pdfBytes != result.path("pdfBytes").asLong(-1) || wavBytes != result.path("wavBytes").asLong(-1))
                return failure("WORKER_RESULT_MISMATCH");
            retain = true;
            return new WorkerResult(status, null, List.copyOf(tracks), selectedIndex, selectedName,
                actualSha, pdfBytes, wavBytes, duration, workspace, pdf, wav);
        } finally {
            // Name exists before Docker starts, so cancellation cannot miss a not-yet-written cidfile.
            stopContainer(containerName);
            if (process != null) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.getInputStream().close();
            }
            if (!retain) deleteWorkspace(workspace);
        }
    }

    private static void readBundle(InputStream stream, Path output) throws IOException {
        try (var input = new DataInputStream(stream)) {
            if (!java.util.Arrays.equals(input.readNBytes(8), "GM170E01".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
                throw new IOException("Invalid worker protocol");
            String[] names = {"result.json", "score.pdf", "preview.wav"};
            for (int i = 0; i < names.length; i++) {
                int length = input.readInt();
                if (length < 0 || length > (i == 0 ? 65536 : MAX_OUTPUT_BYTES)) throw new IOException("Output limit");
                if (i == 0 && length == 0) throw new IOException("Missing worker result");
                try (var out = Files.newOutputStream(output.resolve(names[i]))) {
                    byte[] buffer = new byte[8192]; int remaining = length;
                    while (remaining > 0) {
                        int n = input.read(buffer, 0, Math.min(remaining, buffer.length));
                        if (n < 0) throw new IOException("Incomplete worker output");
                        out.write(buffer, 0, n); remaining -= n;
                    }
                }
            }
            if (input.read() != -1) throw new IOException("Trailing worker output");
        }
    }

    public void deleteWorkspace(Path workspace) throws IOException {
        if (workspace == null || !workspace.toAbsolutePath().normalize().getParent().equals(workspaceRoot)
                || !workspace.getFileName().toString().startsWith("job-") || Files.isSymbolicLink(workspace))
            throw new IOException("Invalid conversion workspace");
        rejectSymlinkComponents(workspaceRoot);
        if (!Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(workspace)) {
            // walk does not follow links; unlink a hostile link without touching its target.
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    public void cleanupAbandonedWorkspaces() throws IOException {
        rejectSymlinkComponents(workspaceRoot);
        if (!Files.isDirectory(workspaceRoot, LinkOption.NOFOLLOW_LINKS)) return;
        var cutoff = java.time.Instant.now().minus(Duration.ofMinutes(10));
        try (var paths = Files.list(workspaceRoot)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().startsWith("job-"))
                    .filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).limit(100).toList()) {
                if (Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff))
                    deleteWorkspace(path);
            }
        }
    }

    private void stopContainer(String name) {
        Process cleanup = null;
        boolean interrupted = Thread.interrupted();
        try {
            cleanup = new ProcessBuilder(dockerExecutable, "rm", "--force", name)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!cleanup.waitFor(5, TimeUnit.SECONDS)) cleanup.destroyForcibly();
        } catch (IOException ignored) {
            // Container-side 60s deadline is independent of the Java process/daemon connection.
        } catch (InterruptedException error) { interrupted = true; }
        finally {
            if (cleanup != null && cleanup.isAlive()) cleanup.destroyForcibly();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static WorkerResult failure(String code) {
        return new WorkerResult("ERROR", code, List.of(), null, null, null, 0, 0, 0, null, null, null);
    }
    private static String safeName(String value) {
        String safe = value.replaceAll("[\\p{Cntrl}]", "");
        return safe.substring(0, Math.min(safe.length(), 80));
    }
    private static String sha256(Path file) throws IOException {
        try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (int count; (count = in.read(buffer)) != -1;) digest.update(buffer, 0, count);
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void validateOutput(Path file, String signature) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) < 1000)
            throw new IOException("Invalid conversion output");
        try (var input = Files.newInputStream(file)) {
            if (!signature.equals(new String(input.readNBytes(4), java.nio.charset.StandardCharsets.US_ASCII)))
                throw new IOException("Invalid output signature");
            if ("RIFF".equals(signature)) {
                input.skipNBytes(4);
                if (!"WAVE".equals(new String(input.readNBytes(4), java.nio.charset.StandardCharsets.US_ASCII)))
                    throw new IOException("Invalid WAV container");
            }
        }
    }
    private static void rejectSymlinkComponents(Path path) throws IOException {
        Path part = path.getRoot();
        for (Path name : path) {
            part = part.resolve(name);
            if (Files.isSymbolicLink(part)) throw new IOException("Unsafe conversion workspace");
        }
    }
    private static void permissions(Path path, String mode) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode));
    }
}
