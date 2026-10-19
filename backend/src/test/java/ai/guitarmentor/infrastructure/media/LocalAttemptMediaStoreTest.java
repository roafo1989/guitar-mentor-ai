package ai.guitarmentor.infrastructure.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import ai.guitarmentor.application.attempt.AttemptMediaStore;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class LocalAttemptMediaStoreTest {
    @TempDir Path temp;

    @Test
    void storesWavUnderGeneratedNameAndDeletesIt() throws Exception {
        var store = new LocalAttemptMediaStore(temp.toString());
        byte[] wav = new byte[] {'R','I','F','F', 4,0,0,0, 'W','A','V','E', 'f','m','t',' '};
        var item = store.store(new MockMultipartFile("file", "My guitar.WAV", "audio/wav", wav));
        assertTrue(item.key().endsWith(".wav"));
        assertTrue(item.key().matches("[0-9a-f-]{36}\\.wav"));
        assertEquals(wav.length, item.size());
        assertTrue(Files.exists(temp.resolve(item.key())));
        assertEquals(64, store.verifyAndHash(item.key(), wav.length).length());
        assertThrows(java.io.IOException.class,
                () -> store.verifyAndHash(item.key(), wav.length + 1));
        assertEquals(AttemptMediaStore.DeleteOutcome.DELETED, store.delete(item.key()));
        assertEquals(AttemptMediaStore.DeleteOutcome.ALREADY_ABSENT, store.delete(item.key()));
        assertTrue(Files.notExists(temp.resolve(item.key())));
    }

    @Test
    void rejectsFakeMp3AndUnknownExtension() {
        var store = new LocalAttemptMediaStore(temp.toString());
        assertThrows(ResponseStatusException.class,
                () -> store.store(new MockMultipartFile("file", "fake.mp3",
                        "audio/mpeg", new byte[] {1, 2, 3, 4, 5})));
        assertThrows(ResponseStatusException.class,
                () -> store.store(new MockMultipartFile("file", "../unsafe.exe",
                        "audio/mpeg", new byte[] {'I', 'D', '3', 1, 2})));
    }

    @Test
    void rejectsSizeAboveLimitBeforeReadingFile() {
        var store = new LocalAttemptMediaStore(temp.toString());
        var uploaded = mock(org.springframework.web.multipart.MultipartFile.class);
        when(uploaded.getSize()).thenReturn(AttemptMediaStore.MAX_BYTES + 1);
        var failure = assertThrows(ResponseStatusException.class, () -> store.store(uploaded));
        assertEquals(400, failure.getStatusCode().value());
    }

    @Test
    void rejectsTraversalInInternalProcessingKey() {
        var store = new LocalAttemptMediaStore(temp.toString());
        assertThrows(java.io.IOException.class,
                () -> store.resolveForInspection("../../etc/passwd"));
    }

    @Test
    void rejectsEmptyAudio() {
        var store = new LocalAttemptMediaStore(temp.toString());
        assertThrows(ResponseStatusException.class,
                () -> store.store(new MockMultipartFile("file", "silent.wav", "audio/wav", new byte[0])));
    }
    @Test
    void deletingOneOfTwoFilesNeverTouchesItsNeighbour() throws Exception {
        var media = new LocalAttemptMediaStore(temp.toString());
        byte[] wav = {'R','I','F','F',4,0,0,0,'W','A','V','E'};
        var first = media.store(new MockMultipartFile("file", "first.wav", "audio/wav", wav));
        var second = media.store(new MockMultipartFile("file", "second.wav", "audio/wav", wav));
        assertEquals(AttemptMediaStore.DeleteOutcome.DELETED, media.delete(first.key()));
        assertEquals(AttemptMediaStore.DeleteOutcome.ALREADY_ABSENT, media.delete(first.key()));
        assertTrue(Files.exists(temp.resolve(second.key())));
    }

    @Test
    void invalidKeysCannotEscapeRootOrRemoveOtherFiles() throws Exception {
        var media = new LocalAttemptMediaStore(temp.toString());
        Path outside = Files.createTempFile("outside-d30-", ".wav");
        try {
            for (String key : new String[] {"../" + outside.getFileName(), outside.toString(),
                    "../../etc/passwd", "not-a-uuid.wav", java.util.UUID.randomUUID() + ".txt"}) {
                assertEquals("INVALID_KEY",
                        assertThrows(IOException.class, () -> media.delete(key)).getMessage());
                assertEquals("INVALID_KEY",
                        assertThrows(IOException.class,
                                () -> media.resolveForInspection(key)).getMessage());
            }
            assertTrue(Files.exists(outside));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void directoriesAndSymlinksAreNeitherInspectedNorDeleted() throws Exception {
        var media = new LocalAttemptMediaStore(temp.toString());
        Path directory = temp.resolve(java.util.UUID.randomUUID() + ".wav");
        Files.createDirectory(directory);
        assertEquals("INVALID_MEDIA", assertThrows(IOException.class,
                () -> media.resolveForInspection(directory.getFileName().toString())).getMessage());
        assertEquals("INVALID_MEDIA", assertThrows(IOException.class,
                () -> media.delete(directory.getFileName().toString())).getMessage());
        assertTrue(Files.isDirectory(directory));

        Path outside = Files.createTempFile("outside-target-d30-", ".wav");
        Path link = temp.resolve(java.util.UUID.randomUUID() + ".wav");
        try {
            try {
                Files.createSymbolicLink(link, outside);
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException
                     | SecurityException unsupported) {
                assumeTrue(false, "Symbolic links unavailable on this machine");
            }
            assertEquals("INVALID_MEDIA", assertThrows(IOException.class,
                    () -> media.resolveForInspection(link.getFileName().toString())).getMessage());
            assertEquals("INVALID_MEDIA", assertThrows(IOException.class,
                    () -> media.verifyAndHash(link.getFileName().toString(), Files.size(outside)))
                    .getMessage());
            assertEquals("INVALID_MEDIA", assertThrows(IOException.class,
                    () -> media.delete(link.getFileName().toString())).getMessage());
            assertTrue(Files.isSymbolicLink(link));
            assertTrue(Files.exists(outside));
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void filesystemDeletionFailureIsNotMistakenForAbsentFile() throws Exception {
        var media = new LocalAttemptMediaStore(temp.toString());
        Path target = temp.resolve(java.util.UUID.randomUUID() + ".wav");
        Files.write(target, new byte[]{1,2,3});
        // Custom answer avoids invoking the real Files.delete during Mockito stubbing.
        try (MockedStatic<Files> mocked = Mockito.mockStatic(Files.class, invocation -> {
            if ("delete".equals(invocation.getMethod().getName())
                    && target.equals(invocation.getArgument(0))) {
                throw new AccessDeniedException("simulated-error");
            }
            return invocation.callRealMethod();
        })) {
            var failure = assertThrows(IOException.class,
                    () -> media.delete(target.getFileName().toString()));
            assertEquals("DELETE_IO_ERROR", failure.getMessage());
        }
        assertTrue(Files.exists(target));
    }

}
