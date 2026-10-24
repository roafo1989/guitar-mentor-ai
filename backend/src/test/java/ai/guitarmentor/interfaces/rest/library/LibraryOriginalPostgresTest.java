package ai.guitarmentor.interfaces.rest.library;

import static org.junit.jupiter.api.Assertions.*;
import ai.guitarmentor.infrastructure.persistence.LibraryMaterialRepository;
import ai.guitarmentor.infrastructure.persistence.LibraryObjectStore;
import ai.guitarmentor.infrastructure.security.CurrentActor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named="GUITAR_MENTOR_TEST_POSTGRES",matches="true")
class LibraryOriginalPostgresTest {
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired LibraryMaterialRepository repository;
    @TempDir Path root;

    private UUID account() {
        UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO accounts(id,role,status) VALUES(:id,'LEARNER','ACTIVE')")
            .param("id",id).update();
        return id;
    }
    private MockHttpServletRequest actor(UUID owner) {
        var req=new MockHttpServletRequest();
        req.setAttribute("guitarMentorActor",new CurrentActor(owner,null,"LEARNER"));
        return req;
    }
    private LibraryOriginalController controller() {
        return new LibraryOriginalController(jdbc,transactions,repository,
            new LibraryObjectStore(root.resolve("library").toString(),
                root.resolve("attempts").toString(),root.resolve("scores").toString()),true);
    }
    private static MockMultipartFile pdf(byte[] bytes) {
        return new MockMultipartFile("files","original.pdf","application/pdf",bytes);
    }
    @Test void exactBytesReplayIsolationAndWrongFingerprint() throws Exception {
        UUID alice=account(),bob=account();
        var controller=controller();
        byte[] bytes="%PDF-1.7\nunique test content\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var metadata=new LibraryOriginalController.Metadata("My PDF","ETUDE",null);
        String key=UUID.randomUUID().toString();
        var created=controller.upload(actor(alice),key,metadata,List.of(pdf(bytes)));
        assertEquals(201,created.getStatusCode().value());
        var card=(LibraryMaterialController.MaterialView)created.getBody();
        assertNotNull(card);
        UUID asset=card.files().getFirst().id();
        var content=controller.content(actor(alice),card.id(),asset);
        assertEquals(200,content.getStatusCode().value());
        var resource=(FileSystemResource)content.getBody();
        assertNotNull(resource);
        assertArrayEquals(bytes,Files.readAllBytes(resource.getFile().toPath()));
        assertEquals("LIBRARY_MATERIAL_NOT_FOUND",assertThrows(RuntimeException.class,
            ()->controller.content(actor(bob),card.id(),asset)).getMessage());
        jdbc.sql("UPDATE library_account_usage SET charged_bytes=209715200 WHERE account_id=:id")
            .param("id",alice).update();
        var replay=controller.upload(actor(alice),key,metadata,List.of(pdf(bytes)));
        assertEquals(200,replay.getStatusCode().value());
        assertEquals(1L,jdbc.sql("SELECT charged_files FROM library_account_usage WHERE account_id=:id")
            .param("id",alice).query(Long.class).single());
        assertEquals(0L,jdbc.sql("SELECT reserved_files FROM library_account_usage WHERE account_id=:id")
            .param("id",alice).query(Long.class).single());
        assertEquals("LIBRARY_QUOTA_EXCEEDED",assertThrows(RuntimeException.class,
            ()->controller.upload(actor(alice),UUID.randomUUID().toString(),metadata,
                List.of(pdf(bytes)))).getMessage());
        assertEquals("IDEMPOTENCY_KEY_REUSED",assertThrows(RuntimeException.class,
            ()->controller.upload(actor(alice),key,
                new LibraryOriginalController.Metadata("Different","ETUDE",null),
                List.of(pdf(bytes)))).getMessage());
    }
    @Test void opaqueGuitarProUnchangedAndHeadHasNoBody() throws Exception {
        UUID owner=account();
        var controller=controller();
        byte[] original=new byte[]{0,1,2,3,4,(byte)255,0,17};
        var file=new MockMultipartFile("files","etude.gp5","application/octet-stream",original);
        var response=controller.upload(actor(owner),UUID.randomUUID().toString(),
            new LibraryOriginalController.Metadata("GP Etude","ETUDE",null),List.of(file));
        assertEquals(201,response.getStatusCode().value());
        var card=(LibraryMaterialController.MaterialView)response.getBody();
        assertNotNull(card);
        assertEquals("GP5",card.files().getFirst().format());
        assertEquals("ORIGINAL_UNVERIFIED",card.files().getFirst().validationStatus());
        var get=controller.content(actor(owner),card.id(),card.files().getFirst().id());
        assertArrayEquals(original,Files.readAllBytes(((FileSystemResource)get.getBody()).getFile().toPath()));
        var headRequest=actor(owner);
        headRequest.setMethod("HEAD");
        var head=controller.content(headRequest,card.id(),card.files().getFirst().id());
        assertEquals(200,head.getStatusCode().value());
        assertNull(head.getBody());
        assertEquals(original.length,head.getHeaders().getContentLength());
        assertEquals("nosniff",head.getHeaders().getFirst("X-Content-Type-Options"));
        assertEquals("no-store",head.getHeaders().getCacheControl());
    }

}
