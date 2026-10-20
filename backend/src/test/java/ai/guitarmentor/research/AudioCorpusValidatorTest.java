package ai.guitarmentor.research;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AudioCorpusValidatorTest {
    @TempDir Path root;

    @Test
    void rejectsTraversalAndAbsoluteReferences() throws Exception {
        Files.writeString(root.resolve("safe.json"), "{}");
        assertThrows(Exception.class, () -> AudioCorpusValidator.safePath(root, "../outside"));
        assertThrows(Exception.class, () -> AudioCorpusValidator.safePath(root, "/tmp/private"));
        assertThrows(Exception.class, () -> AudioCorpusValidator.safePath(root, "C:\\private\\take.wav"));
        assertThrows(Exception.class, () -> AudioCorpusValidator.safePath(root, "https://example.test/data"));
        assertThrows(Exception.class, () -> AudioCorpusValidator.safePath(root, "./safe.json"));
        assertEquals(root.resolve("safe.json"), AudioCorpusValidator.safePath(root, "safe.json"));
    }

    @Test
    void rejectsSymbolicLinkWhereSupported() throws Exception {
        Path destination = root.resolve("data.bin");
        Files.writeString(destination, "private");
        Path link = root.resolve("link.bin");
        try {
            Files.createSymbolicLink(link, destination);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException | SecurityException e) {
            return; // Windows systems without symlink permission
        }
        assertThrows(Exception.class, () -> AudioCorpusValidator.safePath(root, "link.bin"));
    }

    @Test
    void rejectsSymlinkedAncestorOfCorpusRootWhereSupported() throws Exception {
        Path realParent = Files.createDirectory(root.resolve("real-parent"));
        Path realCorpus = Files.createDirectory(realParent.resolve("corpus"));
        Files.writeString(realCorpus.resolve("safe.json"), "{}");
        Path alias = root.resolve("linked-parent");
        try {
            Files.createSymbolicLink(alias, realParent);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException | SecurityException e) {
            return; // Platforms without symlink creation permission
        }
        // The final file itself is not a symlink; its ancestor is.
        assertThrows(Exception.class, () -> AudioCorpusValidator.safePath(
                alias.resolve("corpus"), "safe.json"));
    }

    @Test
    void invalidManifestIsInvalid() throws Exception {
        Files.writeString(root.resolve("manifest.json"), "{\"schemaVersion\":0,\"recordings\":[]}");
        assertEquals("INVALID", AudioCorpusValidator.validate(root, "manifest.json").status());
    }

    @Test
    void syntheticOnlyIsNeverReady() throws Exception {
        Files.writeString(root.resolve("manifest.json"), """
            {
              "schemaVersion":1,"datasetId":"SYN-ONLY","datasetVersion":"1",
              "createdAt":"2026-10-19T00:00:00Z","sourceCommit":"example","recordings":[]
            }
            """);
        var result = AudioCorpusValidator.validate(root, "manifest.json");
        assertEquals("INCOMPLETE", result.status());
        assertEquals(0, result.realCount());
        assertTrue(result.gaps().contains("NO_INDEPENDENT_HOLDOUT:REAL-BASS"));
    }

    @Test
    void malformedFileHashIsInvalid() throws Exception {
        Files.writeString(root.resolve("recording.wav"), "not actually audio");
        Files.writeString(root.resolve("manifest.json"), """
            {
              "schemaVersion":1,"datasetId":"EXAMPLE","datasetVersion":"1",
              "createdAt":"2026-10-19T00:00:00Z","sourceCommit":"example",
              "recordings":[{"recordingId":"SYN-1","kind":"SYNTHETIC",
                "group":"SYNTHETIC-CONTROL","split":"DIAGNOSTIC_ONLY",
                "availability":"AVAILABLE","fileRef":"recording.wav",
                "bytes":18,"sourceSha256":"0000000000000000000000000000000000000000000000000000000000000000",
                "durationMs":1000,"sampleRateHz":44100,"channels":1,
                "referenceId":"REF-1","referenceVersion":"1",
                "referenceSha256":"0000000000000000000000000000000000000000000000000000000000000000",
                "barFrom":1,"barTo":1,"transform":{}
              }]
            }
            """);
        var result = AudioCorpusValidator.validate(root, "manifest.json");
        assertEquals("INVALID", result.status());
        assertTrue(result.errors().stream().anyMatch(e -> e.startsWith("SOURCE_INTEGRITY")));
    }

    @Test
    void missingMediaCannotBeReady() throws Exception {
        Files.writeString(root.resolve("manifest.json"), """
            {
              "schemaVersion":1,"datasetId":"EXAMPLE","datasetVersion":"1",
              "createdAt":"2026-10-19T00:00:00Z","sourceCommit":"example",
              "recordings":[{"recordingId":"Q-1","kind":"QUALITY",
                "group":"QUALITY-CONTROL","split":"DIAGNOSTIC_ONLY",
                "availability":"MISSING"}]
            }
            """);
        assertEquals("INCOMPLETE", AudioCorpusValidator.validate(root, "manifest.json").status());
    }
    @Test
    void rejectsOversizedManifest() throws Exception {
        Files.writeString(root.resolve("manifest.json"), " ".repeat(1024 * 1024 + 1));
        assertEquals("INVALID", AudioCorpusValidator.validate(root, "manifest.json").status());
    }

    @Test
    void rejectsMissingAndRemoteManifest() {
        assertEquals("INVALID", AudioCorpusValidator.validate(root, "missing.json").status());
        assertEquals("INVALID", AudioCorpusValidator.validate(root, "https://bad.test/data").status());
    }

    @Test
    void impossibleRealCorpusIsNotReady() throws Exception {
        Files.writeString(root.resolve("manifest.json"), """
            {
              "schemaVersion":1,"datasetId":"REAL-NOT-AVAILABLE","datasetVersion":"1",
              "createdAt":"2026-10-19T00:00:00Z","sourceCommit":"example",
              "recordings":[{"recordingId":"REAL-1","kind":"REAL","group":"REAL-BASS",
                "recordingGroupId":"GROUP-1","performerId":"P-1","sessionId":"S-1",
                "deviceId":"D-1","split":"HOLDOUT","availability":"WITHDRAWN",
                "rightsBasis":"OWNED","consentRef":"C-1","useScope":"LOCAL_RESEARCH",
                "rightsConfirmed":true,"manualReviewConfirmed":true}]
            }
            """);
        var result = AudioCorpusValidator.validate(root, "manifest.json");
        assertEquals("INCOMPLETE", result.status());
        assertTrue(result.gaps().contains("SOURCE_UNAVAILABLE:REAL-1"));
        assertTrue(result.gaps().contains("NO_INDEPENDENT_HOLDOUT:REAL-BASS"));
    }

    @Test
    void rejectsExcessiveRecordingCount() throws Exception {
        var rootJson = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        rootJson.put("schemaVersion", 1).put("datasetId", "BOUND")
                .put("datasetVersion", "1").put("createdAt", "2026-10-19T00:00:00Z")
                .put("sourceCommit", "test");
        var recordings = rootJson.putArray("recordings");
        for (int i = 0; i < 1001; i++) recordings.addObject().put("recordingId", "R-" + i);
        Files.writeString(root.resolve("manifest.json"), rootJson.toString());
        var result = AudioCorpusValidator.validate(root, "manifest.json");
        assertEquals("INVALID", result.status());
        assertTrue(result.errors().contains("TOO_MANY_RECORDINGS"));
    }

}
