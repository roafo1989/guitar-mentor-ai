package ai.guitarmentor.infrastructure.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        store.deleteQuietly(item.key());
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
    void rejectsEmptyAudio() {
        var store = new LocalAttemptMediaStore(temp.toString());
        assertThrows(ResponseStatusException.class,
                () -> store.store(new MockMultipartFile("file", "silent.wav", "audio/wav", new byte[0])));
    }
}
