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
        Process process = new ProcessBuilder("ffprobe", "-v", "error",
                "-protocol_whitelist", "file,pipe", "-select_streams", "a:0",
                "-show_entries", "stream=codec_name,sample_rate,channels:format=format_name,duration",
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
                    && audio.path("channels").asInt(-1) == channels
                    && supportedFormat(source,
                            root.path("format").path("format_name").asText(""),
                            audio.path("codec_name").asText(""));
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

    /**
     * Research inputs follow the existing product upload allow-list: WAV, MP3, OGG, WebM.
     * Owner's original AAC/M4A is retained for provenance, but only an approved WAV
     * derivative can be part of the validator's accepted benchmark media set.
     * ffprobe guesses format from content, so require extension AND detected format/codec.
     */
    private static boolean supportedFormat(Path source, String format, String codec) {
        String filename = source.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (filename.endsWith(".wav")) {
            return "wav".equals(format) && java.util.Set.of(
                    "pcm_u8", "pcm_s16le", "pcm_s24le", "pcm_s32le",
                    "pcm_f32le", "pcm_f64le").contains(codec);
        }
        if (filename.endsWith(".mp3")) {
            return "mp3".equals(format) && "mp3".equals(codec);
        }
        if (filename.endsWith(".ogg")) {
            return "ogg".equals(format) && java.util.Set.of("opus", "vorbis").contains(codec);
        }
        if (filename.endsWith(".webm")) {
            return "matroska,webm".equals(format) && java.util.Set.of("opus", "vorbis").contains(codec);
        }
        return false;
    }
}
