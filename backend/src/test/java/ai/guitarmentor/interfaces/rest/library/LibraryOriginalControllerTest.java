package ai.guitarmentor.interfaces.rest.library;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import ai.guitarmentor.infrastructure.persistence.LibraryMaterialRepository;
import ai.guitarmentor.infrastructure.persistence.LibraryObjectStore;
import ai.guitarmentor.infrastructure.security.CurrentActor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class LibraryOriginalControllerTest {
    @TempDir Path temp;
    private LibraryOriginalController controller(boolean enabled) {
        return new LibraryOriginalController(mock(JdbcClient.class),
            new TransactionTemplate(mock(PlatformTransactionManager.class)),
            mock(LibraryMaterialRepository.class),
            new LibraryObjectStore(temp.resolve("library").toString(),
                temp.resolve("attempts").toString(),temp.resolve("scores").toString()),enabled);
    }
    private MockHttpServletRequest request() {
        var request=new MockHttpServletRequest();
        request.setAttribute("guitarMentorActor",
            new CurrentActor(UUID.randomUUID(),null,"LEARNER"));
        return request;
    }
    @Test void unauthenticatedContentAndUploadCannotReadOrWrite() {
        var controller=controller(true);
        assertEquals("UNAUTHENTICATED",assertThrows(RuntimeException.class,
            ()->controller.content(new MockHttpServletRequest(),UUID.randomUUID(),
                UUID.randomUUID())).getMessage());
        assertEquals("UNAUTHENTICATED",assertThrows(RuntimeException.class,
            ()->controller.upload(new MockHttpServletRequest(),UUID.randomUUID().toString(),
                new LibraryOriginalController.Metadata("Title","ETUDE",null),
                List.of())).getMessage());
    }
    @Test void disabledLibraryFailsClosed() {
        var controller=controller(false);
        assertEquals("LIBRARY_DISABLED",assertThrows(RuntimeException.class,
            ()->controller.content(request(),UUID.randomUUID(),UUID.randomUUID())).getMessage());
    }
    @Test void unsupportedExtensionsAndMetadataRejected() {
        var controller=controller(true);
        var html=new MockMultipartFile("files","attack.html","text/html",
            "<script>malicious</script>".getBytes(StandardCharsets.UTF_8));
        assertEquals("LIBRARY_UNSUPPORTED_FORMAT",assertThrows(RuntimeException.class,
            ()->controller.upload(request(),UUID.randomUUID().toString(),
                new LibraryOriginalController.Metadata("Title","ETUDE",null),
                List.of(html))).getMessage());
        assertEquals("LIBRARY_INVALID_METADATA",assertThrows(RuntimeException.class,
            ()->controller.upload(request(),UUID.randomUUID().toString(),
                new LibraryOriginalController.Metadata("","ETUDE",null),
                List.of(html))).getMessage());
    }
    @Test void invalidPdfDoesNotLeakStoredBytes() throws Exception {
        var controller=controller(true);
        var fakePdf=new MockMultipartFile("files","fake.pdf","application/pdf",
            "not a pdf".getBytes(StandardCharsets.UTF_8));
        assertEquals("LIBRARY_INVALID_PDF",assertThrows(RuntimeException.class,
            ()->controller.upload(request(),UUID.randomUUID().toString(),
                new LibraryOriginalController.Metadata("Title","ETUDE",null),
                List.of(fakePdf))).getMessage());
        try(var files=java.nio.file.Files.list(temp.resolve("library"))) {
            assertEquals(0,files.count());
        }
    }

}
