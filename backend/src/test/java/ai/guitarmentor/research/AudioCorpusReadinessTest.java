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
                .put("barCount", 1).put("bpm", 60)
                .put("meterNumerator", 4).put("meterDenominator", 4)
                .put("origin", "SYNTHETIC_TEST").put("rights", "OWNED")
                .put("instrument", "GUITAR");
        reference.putArray("tuning")
                .add("E2").add("A2").add("D3").add("G3").add("B3").add("E4");
        var event = mapper.createObjectNode();
        event.put("eventId", "NOTE-1").put("bar", 1).put("beat", 0)
                .put("durationBeats", 1).put("voice", "BASS");
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
                String mediaSha = writeWav(media, index);
                ObjectNode annotation = mapper.createObjectNode();
                annotation.put("annotationId", "A-" + index).put("annotationVersion", "1")
                        .put("recordingId", id).put("sourceSha256", mediaSha)
                        .put("annotatorId", "TEST-ANNOTATOR").put("manualConfirmed", true);
                ObjectNode observation = annotation.putArray("observations").addObject();
                observation.put("eventId", "NOTE-1").put("onsetMs", 0).put("fromMs", 0)
                        .put("toMs", 10).put("voice", "BASS").put("label", "MATCH");
                observation.putArray("actualNotes").add("E2");
                String annotationName = "annotation-" + index + ".json";
                String annotationSha = writeJson(annotationName, annotation);
                ObjectNode r = recordings.addObject();
                r.put("recordingId", id).put("kind", "REAL").put("group", group)
                        .put("recordingGroupId", "G-" + index)
                        .put("performerId", "P-1").put("sessionId", "S-" + index)
                        .put("deviceId", "D-1").put("split", split)
                        .put("availability", "AVAILABLE").put("holdoutIndependentConfirmed", true).put("rightsBasis", "OWNED")
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

        // Missing mandatory score and note fields must never make a false READY fixture.
        reference.remove("meterDenominator");
        updateReferenceHash(recordings, writeJson("reference.json", reference));
        writeJson("manifest.json", manifest);
        var scoreWithoutMeter = AudioCorpusValidator.validate(root, "manifest.json");
        assertEquals("INVALID", scoreWithoutMeter.status(), scoreWithoutMeter.toString());
        assertTrue(scoreWithoutMeter.errors().stream().anyMatch(x -> x.startsWith("REFERENCE_CONTENT")));
        reference.put("meterDenominator", 4);

        event.remove("voice");
        updateReferenceHash(recordings, writeJson("reference.json", reference));
        writeJson("manifest.json", manifest);
        var scoreWithoutVoice = AudioCorpusValidator.validate(root, "manifest.json");
        assertEquals("INVALID", scoreWithoutVoice.status(), scoreWithoutVoice.toString());
        assertTrue(scoreWithoutVoice.errors().stream().anyMatch(x -> x.startsWith("REFERENCE_EVENT")));
        event.put("voice", "BASS");

        event.put("beat", 4);
        updateReferenceHash(recordings, writeJson("reference.json", reference));
        writeJson("manifest.json", manifest);
        assertEquals("INVALID", AudioCorpusValidator.validate(root, "manifest.json").status());
        event.put("beat", 0);

        event.withArray("notes").set(0, mapper.getNodeFactory().textNode("NOT_A_NOTE"));
        updateReferenceHash(recordings, writeJson("reference.json", reference));
        writeJson("manifest.json", manifest);
        assertEquals("INVALID", AudioCorpusValidator.validate(root, "manifest.json").status());
        event.withArray("notes").set(0, mapper.getNodeFactory().textNode("E2"));
        updateReferenceHash(recordings, writeJson("reference.json", reference));

        // The annotation must have voice, valid actualNotes and bounded interval endMs.
        ObjectNode firstAnnotationAtStart = (ObjectNode) mapper.readTree(
                Files.readString(root.resolve("annotation-1.json")));
        ObjectNode firstObservation = (ObjectNode) firstAnnotationAtStart.withArray("observations").get(0);
        firstObservation.remove("voice");
        ((ObjectNode)recordings.get(0)).put("annotationSha256",
                writeJson("annotation-1.json", firstAnnotationAtStart));
        writeJson("manifest.json", manifest);
        assertTrue(AudioCorpusValidator.validate(root, "manifest.json").errors().stream()
                .anyMatch(x -> x.startsWith("ANNOTATION_EVENT")));
        firstObservation.put("voice", "BASS");

        firstObservation.put("label", "PAUSE").put("actualNotes", "UNKNOWN")
                .put("endMs", 1001);
        ((ObjectNode)recordings.get(0)).put("annotationSha256",
                writeJson("annotation-1.json", firstAnnotationAtStart));
        writeJson("manifest.json", manifest);
        assertTrue(AudioCorpusValidator.validate(root, "manifest.json").errors().stream()
                .anyMatch(x -> x.startsWith("ANNOTATION_EVENT")));

        firstObservation.put("endMs", 8);
        ((ObjectNode)recordings.get(0)).put("annotationSha256",
                writeJson("annotation-1.json", firstAnnotationAtStart));
        writeJson("manifest.json", manifest);
        assertEquals("INVALID", AudioCorpusValidator.validate(root, "manifest.json").status());

        // UNKNOWN is a legitimate explicitly marked abstention, not a fabricated pitch.
        firstObservation.put("label", "AMBIGUOUS").remove("endMs");
        ((ObjectNode)recordings.get(0)).put("annotationSha256",
                writeJson("annotation-1.json", firstAnnotationAtStart));
        writeJson("manifest.json", manifest);
        assertEquals("READY_FOR_POC", AudioCorpusValidator.validate(root, "manifest.json").status());

        // Valid WAV bytes under an unsupported extension are not an approved audio input.
        Files.copy(root.resolve("take-1.wav"), root.resolve("take-1.m4a"));
        ((ObjectNode)recordings.get(0)).put("fileRef", "take-1.m4a");
        writeJson("manifest.json", manifest);
        var badMedia = AudioCorpusValidator.validate(root, "manifest.json");
        assertEquals("INVALID", badMedia.status());
        assertTrue(badMedia.errors().stream().anyMatch(x -> x.startsWith("MEDIA_PROBE_MISMATCH")));
        ((ObjectNode)recordings.get(0)).put("fileRef", "take-1.wav");

        // Restore baseline before the existing rights/holdout and event-reference checks.
        firstObservation.put("label", "MATCH").putArray("actualNotes").add("E2");
        ((ObjectNode)recordings.get(0)).put("annotationSha256",
                writeJson("annotation-1.json", firstAnnotationAtStart));
        writeJson("manifest.json", manifest);
        assertEquals("READY_FOR_POC", AudioCorpusValidator.validate(root, "manifest.json").status());

        // Regression: owner's recording permission alone does not attest all score rights.
        ObjectNode first = (ObjectNode) recordings.get(0);
        first.put("rightsConfirmed", false);
        writeJson("manifest.json", manifest);
        var unlicensedScore = AudioCorpusValidator.validate(root, "manifest.json");
        assertEquals("INCOMPLETE", unlicensedScore.status(), unlicensedScore.toString());
        assertTrue(unlicensedScore.gaps().contains("RIGHTS_UNCONFIRMED:R-1"));
        first.put("rightsConfirmed", true);

        // A file with no independently confirmed event review cannot count as ready.
        first.put("manualReviewConfirmed", false);
        writeJson("manifest.json", manifest);
        var noHumanReview = AudioCorpusValidator.validate(root, "manifest.json");
        assertEquals("INCOMPLETE", noHumanReview.status(), noHumanReview.toString());
        assertTrue(noHumanReview.gaps().contains("REVIEW_MISSING:R-1"));
        first.put("manualReviewConfirmed", true);

        // Separate file hashes alone cannot replace holdout lineage attestation.
        ObjectNode second = (ObjectNode) recordings.get(1);
        second.put("holdoutIndependentConfirmed", false);
        writeJson("manifest.json", manifest);
        var holdoutUnknown = AudioCorpusValidator.validate(root, "manifest.json");
        assertEquals("INCOMPLETE", holdoutUnknown.status(), holdoutUnknown.toString());
        assertTrue(holdoutUnknown.gaps().contains("HOLDOUT_INDEPENDENCE_UNCONFIRMED:R-2"));
        second.put("holdoutIndependentConfirmed", true);

        // A consent/review checkbox in the manifest is not sufficient by itself.
        ObjectNode firstAnnotation = (ObjectNode) mapper.readTree(
                Files.readString(root.resolve("annotation-1.json")));
        firstAnnotation.put("manualConfirmed", false);
        String unconfirmedHash = writeJson("annotation-1.json", firstAnnotation);
        ((ObjectNode)recordings.get(0)).put("annotationSha256", unconfirmedHash);
        writeJson("manifest.json", manifest);
        var unconfirmed = AudioCorpusValidator.validate(root, "manifest.json");
        assertEquals("INVALID", unconfirmed.status());
        assertTrue(unconfirmed.errors().stream().anyMatch(e -> e.startsWith("ANNOTATION_SCHEMA")));
        firstAnnotation.put("manualConfirmed", true);
        ((ObjectNode)recordings.get(0)).put("annotationSha256", writeJson("annotation-1.json", firstAnnotation));


        String secondHash = second.path("sourceSha256").asText();
        second.put("sourceSha256", first.path("sourceSha256").asText());
        writeJson("manifest.json", manifest);
        var duplicate = AudioCorpusValidator.validate(root, "manifest.json");
        assertEquals("INVALID", duplicate.status());
        assertTrue(duplicate.errors().stream().anyMatch(e -> e.startsWith("DUPLICATE_REAL_SOURCE")));
        second.put("sourceSha256", secondHash);


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

    private void updateReferenceHash(ArrayNode recordings, String digest) {
        for (var record : recordings) ((ObjectNode) record).put("referenceSha256", digest);
    }

    private String writeJson(String file, ObjectNode node) throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(node);
        Files.write(root.resolve(file), bytes);
        return sha(bytes);
    }

    private String writeWav(String file, int takeId) throws Exception {
        int dataSize = 8000 * 2;
        ByteBuffer b = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R','I','F','F'}).putInt(36 + dataSize)
                .put(new byte[]{'W','A','V','E','f','m','t',' '}).putInt(16)
                .putShort((short)1).putShort((short)1).putInt(8000)
                .putInt(16000).putShort((short)2).putShort((short)16)
                .put(new byte[]{'d','a','t','a'}).putInt(dataSize);
        for (int i=0; i<8000; i++) b.putShort((short)(i == 0 ? takeId : 0));
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
