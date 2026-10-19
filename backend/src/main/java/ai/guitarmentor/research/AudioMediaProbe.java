package ai.guitarmentor.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Strictly local ffprobe inspection. No shell, network URI, or transcoding. */
final class AudioMediaProbe {
    private static final ObjectMapper JSON = new ObjectMapper();
    private AudioMediaProbe() {}

    static boolean matches(Path source, long durationMs, int sampleRate, int channels) throws IOException {
        // Ensure bounded output is consumed concurrently: ffprobe must never block on a full pipe.
        Process process = new ProcessBuilder("ffprobe", "-v", "error", "-nostdin",
                "-protocol_whitelist", "file,pipe", "-select_streams", "a:0",
                "-show_entries", "stream=sample_rate,channels:format=duration",
                "-of", "json", source.toString()).redirectErrorStream(true).start();
        var executor = Executors.newSingleThreadExecutor();
        Future<byte[]> outputTask = executor.submit(() -> {
            try (var stream = process.getInputStream()) {
                return stream.readNBytes(65537);
            }
        });
        try {
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("probe timeout");
            }
            byte[] output = outputTask.get(2, TimeUnit.SECONDS);
            if (process.exitValue() != 0 || output.length > 65536) return false;
            JsonNode root = JSON.readTree(output);
            JsonNode streams = root.path("streams");
            if (!streams.isArray() || streams.isEmpty()) return false;
            JsonNode audio = streams.get(0);
            double seconds = root.path("format").path("duration").asDouble(-1);
            long actualMs = Math.round(seconds * 1000);
            return seconds > 0 && actualMs <= 120_000 && Math.abs(actualMs - durationMs) <= 100
                    && audio.path("sample_rate").asInt(-1) == sampleRate
                    && audio.path("channels").asInt(-1) == channels;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("probe interrupted");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IOException("probe failed");
        } finally {
            process.destroyForcibly();
            outputTask.cancel(true);
            executor.shutdownNow();
        }
    }
}
