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
        var bobCreated=controller.upload(actor(bob),key,metadata,List.of(pdf(bytes)));
        assertEquals(201,bobCreated.getStatusCode().value(),
            "The same idempotency key belongs independently to another account");
        var bobCard=(LibraryMaterialController.MaterialView)bobCreated.getBody();
        assertNotNull(bobCard);
        assertNotEquals(card.id(),bobCard.id());

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

    @Test void staleReservationAndOnlyUnreferencedOriginalAreRecovered() throws Exception {
        UUID owner=account();
        var store=new LibraryObjectStore(root.resolve("library").toString(),
            root.resolve("attempts").toString(),root.resolve("scores").toString());
        var orphan=store.put(new java.io.ByteArrayInputStream(new byte[]{1,2,3}),100);
        var protectedObject=store.put(new java.io.ByteArrayInputStream(new byte[]{4,5,6}),100);
        Files.setLastModifiedTime(store.file(orphan.key()),
            java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(7200)));
        Files.setLastModifiedTime(store.file(protectedObject.key()),
            java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(7200)));
        UUID material=UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO library_materials(id,owner_account_id,title,material_type,state)
            VALUES(:id,:owner,'Protected','ETUDE','READY')
            """).param("id",material).param("owner",owner).update();
        jdbc.sql("""
            INSERT INTO library_assets(id,material_id,object_key,original_name,format,byte_size,sha256)
            VALUES(:id,:material,:key,'file.gp','GP',:size,:sha)
            """).param("id",UUID.randomUUID()).param("material",material)
            .param("key",protectedObject.key()).param("size",protectedObject.length())
            .param("sha",protectedObject.sha256()).update();
        jdbc.sql("""
            INSERT INTO library_account_usage(account_id,reserved_bytes,reserved_files)
            VALUES(:owner,100,1)
            """).param("owner",owner).update();
        UUID operation=UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO library_upload_operations
                (id,owner_account_id,idempotency_key,state,reserved_bytes,reserved_files,expires_at,updated_at)
            VALUES(:id,:owner,:key,'RECEIVING',100,1,now()+interval '24 hours',now()-interval '2 hours')
            """).param("id",operation).param("owner",owner).param("key",UUID.randomUUID()).update();
        new ai.guitarmentor.infrastructure.persistence.LibraryOrphanRecovery(
            store,jdbc,transactions,true).reconcile();
        assertEquals(0L,jdbc.sql("SELECT reserved_bytes FROM library_account_usage WHERE account_id=:owner")
            .param("owner",owner).query(Long.class).single());
        assertEquals("FAILED",jdbc.sql("SELECT state FROM library_upload_operations WHERE id=:id")
            .param("id",operation).query(String.class).single());
        assertFalse(Files.exists(root.resolve("library").resolve(orphan.key())));
        assertTrue(Files.exists(store.file(protectedObject.key())));
    }

    @Test void recoveryNeverDeletesAnObjectInActiveDurableUploadManifest() throws Exception {
        UUID owner=account();
        var store=new LibraryObjectStore(root.resolve("library").toString(),
            root.resolve("attempts").toString(),root.resolve("scores").toString());
        var uploading=store.put(new java.io.ByteArrayInputStream(new byte[]{7,8,9}),100);
        Files.setLastModifiedTime(store.file(uploading.key()),
            java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(7200)));
        jdbc.sql("""
            INSERT INTO library_upload_operations
                (id,owner_account_id,idempotency_key,state,manifest,expires_at,last_lease_at)
            VALUES(:id,:owner,:key,'RECEIVING',
                jsonb_build_object('keys',jsonb_build_array(CAST(:object AS text))),
                now()+interval '24 hours',now())
            """).param("id",UUID.randomUUID()).param("owner",owner)
                .param("key",UUID.randomUUID()).param("object",uploading.key()).update();
        new ai.guitarmentor.infrastructure.persistence.LibraryOrphanRecovery(
            store,jdbc,transactions,true).reconcile();
        assertTrue(Files.exists(store.file(uploading.key())),
            "Live operation's manifested original must survive recovery");
    }

    @Test void abandonedCommittingReleasesQuotaWithoutTouchingReadyObject() throws Exception {
        UUID owner=account();
        var store=new LibraryObjectStore(root.resolve("library").toString(),
            root.resolve("attempts").toString(),root.resolve("scores").toString());
        var orphan=store.put(new java.io.ByteArrayInputStream(new byte[]{9,8,7}),100);
        Files.setLastModifiedTime(store.file(orphan.key()),
            java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(7200)));
        jdbc.sql("""
            INSERT INTO library_account_usage(account_id,reserved_bytes,reserved_files)
            VALUES(:owner,3,1)
            """).param("owner",owner).update();
        UUID operation=UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO library_upload_operations
                (id,owner_account_id,idempotency_key,state,manifest,reserved_bytes,reserved_files,
                 expires_at,updated_at,last_lease_at)
            VALUES(:id,:owner,:key,'COMMITTING',
                jsonb_build_object('keys',jsonb_build_array(CAST(:object AS text))),
                3,1,now()+interval '24 hours',now()-interval '2 hours',now()-interval '2 hours')
            """).param("id",operation).param("owner",owner)
                .param("key",UUID.randomUUID()).param("object",orphan.key()).update();
        new ai.guitarmentor.infrastructure.persistence.LibraryOrphanRecovery(
            store,jdbc,transactions,true).reconcile();
        assertEquals("FAILED",jdbc.sql("SELECT state FROM library_upload_operations WHERE id=:id")
            .param("id",operation).query(String.class).single());
        assertEquals(0L,jdbc.sql("SELECT reserved_bytes FROM library_account_usage WHERE account_id=:id")
            .param("id",owner).query(Long.class).single());
        assertFalse(Files.exists(root.resolve("library").resolve(orphan.key())));
    }

    @Test void storageFailureReleasesReservationAndNeverCreatesReadyCard() throws Exception {
        UUID owner=account();
        var failing=org.mockito.Mockito.mock(LibraryObjectStore.class);
        org.mockito.Mockito.when(failing.put(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong()))
            .thenThrow(new java.io.IOException("simulated disk unavailable"));
        var controller=new LibraryOriginalController(jdbc,transactions,repository,failing,true);
        byte[] bytes="%PDF-1.7\\nexample".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("LIBRARY_STORAGE_UNAVAILABLE",assertThrows(RuntimeException.class,
            ()->controller.upload(actor(owner),UUID.randomUUID().toString(),
                new LibraryOriginalController.Metadata("Test","ETUDE",null),
                List.of(pdf(bytes)))).getMessage());
        assertEquals(0L,jdbc.sql("SELECT reserved_bytes FROM library_account_usage WHERE account_id=:owner")
            .param("owner",owner).query(Long.class).single());
        assertEquals(0L,jdbc.sql("SELECT count(*) FROM library_materials WHERE owner_account_id=:owner")
            .param("owner",owner).query(Long.class).single());
    }

}
