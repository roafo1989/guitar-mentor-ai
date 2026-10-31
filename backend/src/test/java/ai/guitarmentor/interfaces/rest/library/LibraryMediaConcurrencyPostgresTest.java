package ai.guitarmentor.interfaces.rest.library;

import static org.junit.jupiter.api.Assertions.*;
import ai.guitarmentor.infrastructure.persistence.LibraryMaterialRepository;
import ai.guitarmentor.infrastructure.persistence.LibraryObjectStore;
import ai.guitarmentor.infrastructure.persistence.LibraryOrphanRecovery;
import ai.guitarmentor.infrastructure.security.CurrentActor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL race check for the account-level one-upload gate and reservation rollback. */
@SpringBootTest(properties={
    "guitar-mentor.security.enabled=false",
    "guitar-mentor.library.enabled=true",
    "guitar-mentor.library.community-media-enabled=true",
    "guitar-mentor.library.asset-deletion-delay-ms=3600000",
    "guitar-mentor.library.reconcile-delay-ms=3600000",
    "guitar-mentor.library.cursor-secret=ci-only-media-concurrency-secret-long-enough"
})
@EnabledIfEnvironmentVariable(named="GUITAR_MENTOR_TEST_POSTGRES",matches="true")
class LibraryMediaConcurrencyPostgresTest {
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired LibraryMaterialRepository repository;
    @Autowired LibraryOrphanRecovery recovery;
    private static final Path ROOT;
    static {
        try { ROOT=Files.createTempDirectory("gm-media-concurrency-"); }
        catch(java.io.IOException e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void storage(DynamicPropertyRegistry registry) {
        registry.add("guitar-mentor.library.storage-path",()->ROOT.resolve("library").toString());
    }
    @AfterAll static void cleanupStorage() throws Exception {
        if(Files.exists(ROOT)) try(var paths=Files.walk(ROOT)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(Exception ignored){}});
        }
    }

    private static MockHttpServletRequest actor(UUID id) {
        var request=new MockHttpServletRequest();
        request.setAttribute("guitarMentorActor",new CurrentActor(id,null,"LEARNER"));
        return request;
    }
    @Test void concurrentUploadsForOneAccountCannotDoubleCommitOrLeakReservation() throws Exception {
        UUID owner=UUID.randomUUID();
        jdbc.sql("INSERT INTO accounts(id,role,status) VALUES(:id,'LEARNER','ACTIVE')").param("id",owner).update();
        var store=new LibraryObjectStore(ROOT.resolve("library").toString(),ROOT.resolve("attempts").toString(),ROOT.resolve("scores").toString());
        UUID materialId=null;
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            var originals=new LibraryOriginalController(jdbc,transactions,repository,store,true);
            var created=originals.upload(actor(owner),UUID.randomUUID().toString(),
                new LibraryOriginalController.Metadata("Concurrent media","ETUDE",null),
                List.of(new MockMultipartFile("files","score.pdf","application/pdf","%PDF-1.7\nscore".getBytes())));
            var card=(LibraryMaterialController.MaterialView)created.getBody();
            assertNotNull(card); materialId=card.id();
            var media=new LibraryMediaController(jdbc,transactions,repository,store,true,true);
            byte[] wav=wav(1,44100,88200);
            CountDownLatch start=new CountDownLatch(1);
            Callable<Integer> call=()->{
                start.await();
                try {
                    return media.upload(actor(owner),card.id(),"\"1\"",UUID.randomUUID().toString(),
                        Map.of("role","ORIGINAL_RECORDING"),List.of(
                            new MockMultipartFile("files","take.wav","audio/wav",wav))).getStatusCode().value();
                } catch(RuntimeException e) {
                    if("LIBRARY_UPLOAD_IN_PROGRESS".equals(e.getMessage())) return 409;
                    throw e;
                }
            };
            Future<Integer> first=pool.submit(call),second=pool.submit(call);
            start.countDown();
            int a=first.get(10,TimeUnit.SECONDS),b=second.get(10,TimeUnit.SECONDS);
            assertEquals(1,(a==201?1:0)+(b==201?1:0));
            assertTrue((a==409||a==201)&&(b==409||b==201));
            assertEquals(2L,jdbc.sql("SELECT revision FROM library_materials WHERE id=:id")
                .param("id",card.id()).query(Long.class).single());
            assertEquals(2L,jdbc.sql("SELECT charged_files FROM library_account_usage WHERE account_id=:id")
                .param("id",owner).query(Long.class).single());
            assertEquals(0L,jdbc.sql("SELECT reserved_files FROM library_account_usage WHERE account_id=:id")
                .param("id",owner).query(Long.class).single());
            assertEquals(1L,jdbc.sql("SELECT count(*) FROM library_assets WHERE material_id=:id AND role='ORIGINAL_RECORDING'")
                .param("id",card.id()).query(Long.class).single());
        } finally {
            pool.shutdownNow();
            if(materialId!=null) jdbc.sql("DELETE FROM library_assets WHERE material_id=:id").param("id",materialId).update();
            jdbc.sql("DELETE FROM library_upload_operations WHERE owner_account_id=:id").param("id",owner).update();
            jdbc.sql("DELETE FROM library_account_usage WHERE account_id=:id").param("id",owner).update();
            if(materialId!=null) jdbc.sql("DELETE FROM library_materials WHERE id=:id").param("id",materialId).update();
            jdbc.sql("DELETE FROM accounts WHERE id=:id").param("id",owner).update();
        }
    }
    @Test void stalePhotoManifestReleasesQuotaAndSweepsOriginalAndPreviewObjects() throws Exception {
        UUID owner=UUID.randomUUID(), operation=UUID.randomUUID(), key=UUID.randomUUID();
        jdbc.sql("INSERT INTO accounts(id,role,status) VALUES(:id,'LEARNER','ACTIVE')").param("id",owner).update();
        var store=new LibraryObjectStore(ROOT.resolve("library").toString(),ROOT.resolve("attempts").toString(),ROOT.resolve("scores").toString());
        var original=store.put(new java.io.ByteArrayInputStream(new byte[]{1,2,3,4}),100);
        var preview=store.put(new java.io.ByteArrayInputStream(new byte[]{5,6,7}),100);
        var old=java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofHours(2)));
        Files.setLastModifiedTime(store.file(original.key()),old);
        Files.setLastModifiedTime(store.file(preview.key()),old);
        jdbc.sql("INSERT INTO library_account_usage(account_id,reserved_bytes,reserved_files) VALUES(:owner,7,2)")
            .param("owner",owner).update();
        String manifest="{\"keys\":[\""+original.key()+"\",\""+preview.key()+"\"]}";
        jdbc.sql("""
            INSERT INTO library_upload_operations(id,owner_account_id,idempotency_key,state,reserved_bytes,reserved_files,
                manifest,last_lease_at,expires_at,updated_at)
            VALUES(:id,:owner,:key,'COMMITTING',7,2,CAST(:manifest AS jsonb),now()-interval '2 hours',now()+interval '1 day',now()-interval '2 hours')
            """).param("id",operation).param("owner",owner).param("key",key).param("manifest",manifest).update();
        recovery.reconcile();
        assertEquals("FAILED",jdbc.sql("SELECT state FROM library_upload_operations WHERE id=:id")
            .param("id",operation).query(String.class).single());
        assertEquals(0L,jdbc.sql("SELECT reserved_bytes+reserved_files FROM library_account_usage WHERE account_id=:id")
            .param("id",owner).query(Long.class).single());
        assertThrows(java.nio.file.NoSuchFileException.class,()->store.file(original.key()));
        assertThrows(java.nio.file.NoSuchFileException.class,()->store.file(preview.key()));
        jdbc.sql("DELETE FROM library_upload_operations WHERE id=:id").param("id",operation).update();
        jdbc.sql("DELETE FROM library_account_usage WHERE account_id=:id").param("id",owner).update();
        jdbc.sql("DELETE FROM accounts WHERE id=:id").param("id",owner).update();
    }

    private static byte[] wav(int channels,int rate,int dataBytes) {
        int block=channels*2;
        ByteBuffer b=ByteBuffer.allocate(44+dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{82,73,70,70}).putInt(36+dataBytes).put(new byte[]{87,65,86,69});
        b.put(new byte[]{102,109,116,32}).putInt(16).putShort((short)1).putShort((short)channels)
            .putInt(rate).putInt(rate*block).putShort((short)block).putShort((short)16);
        b.put(new byte[]{100,97,116,97}).putInt(dataBytes); return b.array();
    }
}
