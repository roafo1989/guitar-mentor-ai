package ai.guitarmentor.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AudioCorpusReadinessTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sixIndependentSyntheticTestHarnessTakesMeetStructuralReadiness() throws Exception {
        // Files are generated PCM WAV test signals, NOT human performances or research data.
        assumeTrue(ffprobePresent(), "ffprobe unavailable; skip positive media integration scenario");
        var reference = mapper.createObjectNode();
        reference.put("referenceId", "REF-1").put("referenceVersion", "1")
                .put("barCount", 1).put("bpm", 60);
        var event = mapper.createObjectNode();
        event.put("eventId", "NOTE-1").put("bar", 1).put("beat", 0)
                .put("durationBeats", 1);
        event.putArray("notes").add("E2");
        reference.putArray("events").add(event);
        String refHash = writeJson("reference.json", reference);

        ObjectNode manifest = mapper.createObjectNode();
        manifest.put("schemaVersion", 1).put("datasetId", "STRUCTURAL-TEST")
                .put("datasetVersion", "1").put("createdAt", "2026-10-19T00:00:00Z")
                .put("sourceCommit", "test");
        ArrayNode recordings = manifest.putArray("recordings");
        int index = 0;
        for (String group : new String[]{"REAL-BASS", "REAL-TIMING", "REAL-POLY"}) {
            for (String split : new String[]{"CALIBRATION", "HOLDOUT"}) {
                index++;
                String id = "R-" + index;
                String media = "take-" + index + ".wav";
                String mediaSha = writeWav(media);
                ObjectNode annotation = mapper.createObjectNode();
                annotation.put("annotationId", "A-" + index).put("annotationVersion", "1")
                        .put("recordingId", id).put("sourceSha256", mediaSha)
                        .put("annotatorId", "TEST-ANNOTATOR");
                ObjectNode observation = annotation.putArray("observations").addObject();
                observation.put("eventId", "NOTE-1").put("onsetMs", 0).put("fromMs", 0)
                        .put("toMs", 10).put("voice", "BASS").put("label", "MATCH");
                String annotationName = "annotation-" + index + ".json";
                String annotationSha = writeJson(annotationName, annotation);
                ObjectNode r = recordings.addObject();
                r.put("recordingId", id).put("kind", "REAL").put("group", group)
                        .put("recordingGroupId", "G-" + index)
                        .put("performerId", "P-1").put("sessionId", "S-" + index)
                        .put("deviceId", "D-1").put("split", split)
                        .put("availability", "AVAILABLE").put("rightsBasis", "OWNED")
                        .put("consentRef", "C-" + index).put("useScope", "LOCAL_RESEARCH")
                        .put("rightsConfirmed", true).put("manualReviewConfirmed", true)
                        .put("fileRef", media).put("sourceSha256", mediaSha)
                        .put("bytes", Files.size(root.resolve(media)))
                        .put("durationMs", 1000).put("sampleRateHz", 8000).put("channels", 1)
                        .put("referenceId", "REF-1").put("referenceVersion", "1")
                        .put("referenceRef", "reference.json").put("referenceSha256", refHash)
                        .put("barFrom", 1).put("barTo", 1)
                        .put("annotationRef", annotationName).put("annotationSha256", annotationSha)
                        .put("annotationVersion", "1");
            }
        }
        writeJson("manifest.json", manifest);
        var result = AudioCorpusValidator.validate(root, "manifest.json");
        assertEquals("READY_FOR_POC", result.status(), result.toString());
        assertEquals(6, result.realCount());
        assertEquals(0, result.exitCode());

        // A reference to a nonexistent notation event must fail.
        ObjectNode bad = (ObjectNode) mapper.readTree(Files.readString(root.resolve("annotation-1.json")));
        ((ObjectNode)bad.withArray("observations").get(0)).put("eventId", "NONEXISTENT");
        String replacementSha = writeJson("annotation-1.json", bad);
        ((ObjectNode)recordings.get(0)).put("annotationSha256", replacementSha);
        writeJson("manifest.json", manifest);
        var rejected = AudioCorpusValidator.validate(root, "manifest.json");
        assertEquals("INVALID", rejected.status());
        assertTrue(rejected.errors().stream().anyMatch(e -> e.startsWith("UNKNOWN_EVENT_ID")));
    }

    private String writeJson(String file, ObjectNode node) throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(node);
        Files.write(root.resolve(file), bytes);
        return sha(bytes);
    }

    private String writeWav(String file) throws Exception {
        int dataSize = 8000 * 2;
        ByteBuffer b = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R','I','F','F'}).putInt(36 + dataSize)
                .put(new byte[]{'W','A','V','E','f','m','t',' '}).putInt(16)
                .putShort((short)1).putShort((short)1).putInt(8000)
                .putInt(16000).putShort((short)2).putShort((short)16)
                .put(new byte[]{'d','a','t','a'}).putInt(dataSize);
        for (int i=0; i<8000; i++) b.putShort((short)0);
        byte[] bytes = b.array();
        Files.write(root.resolve(file), bytes);
        return sha(bytes);
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private boolean ffprobePresent() {
        try {
            Process process = new ProcessBuilder("ffprobe", "-version").start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
