package ai.guitarmentor.infrastructure.media;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import ai.guitarmentor.application.attempt.AttemptMediaStore;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

/** Independent TC30-10..13/21, real temporary filesystem, no user files. */
class IndependentStorageAcceptanceTest {
    @TempDir Path root;
    private static final byte[] WAV = {'R','I','F','F',4,0,0,0,'W','A','V','E',0,0,0,0};

    @Test void completeInvalidKeyMatrixForAllThreeMethods() throws Exception {
        var store = new LocalAttemptMediaStore(root.toString());
        var control = store.store(new MockMultipartFile("file", "control.wav", "audio/wav", WAV));
        Path outside = Files.createTempFile("qa30-outside-", ".wav");
        try {
            Files.write(outside, WAV);
            for (String key : Arrays.asList(null, "", "../x.wav", "..\\x.wav", "/tmp/x.wav",
                    "C:\\temp\\x.wav", "folder/" + UUID.randomUUID() + ".wav", "not-uuid.wav",
                    UUID.randomUUID() + ".txt", UUID.randomUUID() + ".WAV", outside.toString())) {
                assertEquals("INVALID_KEY", assertThrows(IOException.class, () -> store.delete(key)).getMessage());
                assertEquals("INVALID_KEY", assertThrows(IOException.class, () -> store.resolveForInspection(key)).getMessage());
                assertEquals("INVALID_KEY", assertThrows(IOException.class, () -> store.verifyAndHash(key, WAV.length)).getMessage());
                assertArrayEquals(WAV, Files.readAllBytes(outside));
                assertArrayEquals(WAV, Files.readAllBytes(root.resolve(control.key())));
            }
        } finally { Files.deleteIfExists(outside); }
    }

    @Test void directorySymlinkFifoAndMissingObjectAcrossAllMethods() throws Exception {
        var store = new LocalAttemptMediaStore(root.toString());
        Path outside = Files.createTempFile("qa30-target-", ".wav");
        try {
            Files.write(outside, WAV);
            Path dir = Files.createDirectory(root.resolve(UUID.randomUUID() + ".wav"));
            Files.write(dir.resolve("control"), WAV);
            Path link = Files.createSymbolicLink(root.resolve(UUID.randomUUID() + ".wav"), outside);
            Path fifo = root.resolve(UUID.randomUUID() + ".wav");
            assertEquals(0, new ProcessBuilder("mkfifo", fifo.toString()).start().waitFor());
            for (Path object : List.of(dir, link, fifo)) {
                String key = object.getFileName().toString();
                assertEquals("INVALID_MEDIA", assertThrows(IOException.class, () -> store.delete(key)).getMessage());
                assertEquals("INVALID_MEDIA", assertThrows(IOException.class, () -> store.resolveForInspection(key)).getMessage());
                assertEquals("INVALID_MEDIA", assertThrows(IOException.class, () -> store.verifyAndHash(key, WAV.length)).getMessage());
            }
            assertArrayEquals(WAV, Files.readAllBytes(outside));
            assertArrayEquals(WAV, Files.readAllBytes(dir.resolve("control")));
            assertTrue(Files.isSymbolicLink(link));
            String missing = UUID.randomUUID() + ".wav";
            assertEquals("MEDIA_MISSING", assertThrows(IOException.class, () -> store.resolveForInspection(missing)).getMessage());
            assertEquals("MEDIA_MISSING", assertThrows(IOException.class, () -> store.verifyAndHash(missing, 1)).getMessage());
            assertEquals(AttemptMediaStore.DeleteOutcome.ALREADY_ABSENT, store.delete(missing));
        } finally { Files.deleteIfExists(outside); }
    }

    @Test void deletionFailureThenRecoveryAndNeighbourHash() throws Exception {
        var store = new LocalAttemptMediaStore(root.toString());
        var first = store.store(new MockMultipartFile("file", "first.wav", "audio/wav", WAV));
        var neighbour = store.store(new MockMultipartFile("file", "other.wav", "audio/wav", WAV));
        String hash = store.verifyAndHash(neighbour.key(), WAV.length);
        Path target = root.resolve(first.key());
        try (var mocked = mockStatic(Files.class, invocation -> {
            if ("delete".equals(invocation.getMethod().getName()) && target.equals(invocation.getArgument(0)))
                throw new AccessDeniedException("PRIVATE_PATH_MARKER");
            return invocation.callRealMethod();
        })) {
            assertEquals("DELETE_IO_ERROR", assertThrows(IOException.class, () -> store.delete(first.key())).getMessage());
            assertTrue(Files.exists(target));
        }
        assertEquals(AttemptMediaStore.DeleteOutcome.DELETED, store.delete(first.key()));
        assertEquals(AttemptMediaStore.DeleteOutcome.ALREADY_ABSENT, store.delete(first.key()));
        assertEquals(hash, store.verifyAndHash(neighbour.key(), WAV.length));
    }

    @Test void exactSizeBoundaryAndUppercaseUserFilename() throws Exception {
        var store = new LocalAttemptMediaStore(root.toString());
        byte[] exact = new byte[(int) AttemptMediaStore.MAX_BYTES];
        System.arraycopy(WAV, 0, exact, 0, WAV.length);
        var saved = store.store(new MockMultipartFile("file", "UPPER.WAV", "audio/wav", exact));
        assertEquals(AttemptMediaStore.MAX_BYTES, Files.size(root.resolve(saved.key())));
        assertTrue(saved.key().endsWith(".wav"));
        for (long size : new long[] {0, AttemptMediaStore.MAX_BYTES + 1}) {
            var file = mock(org.springframework.web.multipart.MultipartFile.class);
            when(file.getSize()).thenReturn(size);
            assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> store.store(file));
            verify(file, never()).getInputStream();
        }
    }
}
