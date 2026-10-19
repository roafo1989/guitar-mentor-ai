package ai.guitarmentor.infrastructure.media;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads audio packet timestamps with ffprobe (FFmpeg), never decodes or grades music.
 * Works with the four uploaded containers, including MediaRecorder WebM missing Duration metadata.
 */
@Component
public class AudioDurationProbe {
    public static final double MAX_DURATION_SECONDS = 120.0;
    private static final long MAX_OUTPUT_BYTES = 2_000_000;
    private static final int TIMEOUT_SECONDS = 12;
    private final String command;

    public AudioDurationProbe(@Value("${guitar-mentor.submissions.ffprobe-command:ffprobe}")
            String command) {
        this.command = command;
    }

    public double measure(Path file) throws IOException {
        Path output = Files.createTempFile("guitar-audio-probe-", ".txt");
        try {
            Process process;
            try {
                process = new ProcessBuilder(command, "-v", "error",
                        "-select_streams", "a:0",
                        "-show_entries", "packet=pts_time,duration_time",
                        "-of", "csv=p=0", file.toAbsolutePath().toString())
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .redirectOutput(output.toFile()).start();
            } catch (IOException unavailable) {
                throw new IOException("PROBE_UNAVAILABLE");
            }
            try {
                if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("PROBE_TIMEOUT");
                }
            } catch (InterruptedException interrupted) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("PROBE_INTERRUPTED", interrupted);
            }
            if (process.exitValue() != 0 || Files.size(output) > MAX_OUTPUT_BYTES) {
                throw new IOException("INVALID_MEDIA");
            }
            var duration = parsePacketDuration(Files.readAllLines(output));
            if (!(duration > 0) || !Double.isFinite(duration)) {
                throw new IOException("INVALID_MEDIA");
            }
            if (duration > MAX_DURATION_SECONDS) {
                throw new IOException("DURATION_EXCEEDED");
            }
            return duration;
        } finally {
            Files.deleteIfExists(output);
        }
    }

    /** Returns the maximum audio packet end timestamp, including short last frames. */
    static double parsePacketDuration(Iterable<String> lines) throws IOException {
        double end = -1;
        int count = 0;
        for (String line : lines) {
            if (++count > 150_000) throw new IOException("INVALID_MEDIA");
            String[] parts = line.trim().split(",", -1);
            if (parts.length == 0) continue;
            try {
                double pts = new BigDecimal(parts[0]).doubleValue();
                double packetDuration = 0;
                if (parts.length > 1 && !parts[1].isBlank()
                        && !parts[1].equals("N/A")) {
                    packetDuration = new BigDecimal(parts[1]).doubleValue();
                }
                if (Double.isFinite(pts) && Double.isFinite(packetDuration)
                        && packetDuration >= 0) {
                    end = Math.max(end, pts + packetDuration);
                }
            } catch (NumberFormatException ignored) {
                // ffprobe may report N/A timestamps; other valid audio packets still count.
            }
        }
        if (end <= 0) throw new IOException("INVALID_MEDIA");
        return end;
    }
}
