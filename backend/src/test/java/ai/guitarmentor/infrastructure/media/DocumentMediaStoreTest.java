package ai.guitarmentor.infrastructure.media;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class DocumentMediaStoreTest {
    @TempDir Path root;

    @Test void pdfIsPrivateAndDeletionIsIdempotent() throws Exception {
        var store=new DocumentMediaStore(root.toString());
        var pdf=new MockMultipartFile("score","my-notes.pdf","application/pdf",
                "%PDF-1.7\nfake PDF document".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        var stored=store.store(pdf);
        assertEquals("pdf",stored.format());
        assertFalse(stored.key().contains("my-notes"));
        assertTrue(Files.exists(root.resolve(stored.key())));
        assertEquals(ai.guitarmentor.application.attempt.AttemptMediaStore.DeleteOutcome.DELETED,
                store.delete(stored.key()));
        assertEquals(ai.guitarmentor.application.attempt.AttemptMediaStore.DeleteOutcome.ALREADY_ABSENT,
                store.delete(stored.key()));
    }
    @Test void extensionWithoutContentIsRejected() {
        var store=new DocumentMediaStore(root.toString());
        var invalid=new MockMultipartFile("score","score.png","image/png","not a PNG".getBytes());
        var error=assertThrows(ResponseStatusException.class,()->store.store(invalid));
        assertEquals("INVALID_SCORE_CONTENT",error.getReason());
    }
    @Test void emptyAndOversizeAreRejected() {
        var store=new DocumentMediaStore(root.toString());
        assertEquals("INVALID_SCORE_SIZE",assertThrows(ResponseStatusException.class,
                ()->store.store(new MockMultipartFile("score","a.pdf","application/pdf",new byte[0])))
                .getReason());
        assertEquals("INVALID_SCORE_SIZE",assertThrows(ResponseStatusException.class,
                ()->store.store(new MockMultipartFile("score","a.pdf","application/pdf",
                    new byte[(int)DocumentMediaStore.MAX_BYTES+1]))).getReason());
    }
    @Test void impossiblePathsAreNeverDeleted() {
        var store=new DocumentMediaStore(root.toString());
        assertThrows(java.io.IOException.class,()->store.delete("../outside.pdf"));
        assertThrows(java.io.IOException.class,()->store.delete("anything.png"));
    }
    @Test void forgedGifAndSvgAreNotAccepted() {
        var store=new DocumentMediaStore(root.toString());
        assertEquals("UNSUPPORTED_SCORE_FORMAT",assertThrows(ResponseStatusException.class,
                ()->store.store(new MockMultipartFile("score","x.svg","image/svg+xml",
                        "<svg>hi</svg>".getBytes()))).getReason());
    }
}
