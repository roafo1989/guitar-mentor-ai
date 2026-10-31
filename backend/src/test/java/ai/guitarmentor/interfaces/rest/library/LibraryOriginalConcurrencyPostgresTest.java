package ai.guitarmentor.interfaces.rest.library;

import static org.junit.jupiter.api.Assertions.*;
import ai.guitarmentor.infrastructure.persistence.LibraryMaterialRepository;
import ai.guitarmentor.infrastructure.persistence.LibraryObjectStore;
import ai.guitarmentor.infrastructure.security.CurrentActor;
import java.nio.file.Path;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs without a wrapping test transaction so each HTTP-like upload uses real commits. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named="GUITAR_MENTOR_TEST_POSTGRES",matches="true")
class LibraryOriginalConcurrencyPostgresTest {
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired LibraryMaterialRepository materials;
    @TempDir Path disk;

    private MockHttpServletRequest request(UUID id) {
        var req=new MockHttpServletRequest();
        req.setAttribute("guitarMentorActor",new CurrentActor(id,null,"LEARNER"));
        req.setRemoteAddr(id.toString());
        return req;
    }
    @Test void concurrentCallsNeverChargeBeyondAvailableQuota() throws Exception {
        UUID id=UUID.randomUUID();
        transactions.executeWithoutResult(tx -> {
            jdbc.sql("INSERT INTO accounts(id,role,status) VALUES(:id,'LEARNER','ACTIVE')")
                .param("id",id).update();
            jdbc.sql("INSERT INTO library_account_usage(account_id,charged_bytes) VALUES(:id,209715195)")
                .param("id",id).update();
        });
        var controller=new LibraryOriginalController(jdbc,transactions,materials,
            new LibraryObjectStore(disk.resolve("library").toString(),
                disk.resolve("attempts").toString(),disk.resolve("scores").toString()),true);
        var start=new CountDownLatch(1);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> task=() -> {
                start.await();
                var gp=new MockMultipartFile("files","small.gp","application/octet-stream",
                    new byte[]{1,2,3,4,5});
                try {
                    return controller.upload(request(id),UUID.randomUUID().toString(),
                        new LibraryOriginalController.Metadata("Parallel","ETUDE",null),
                        List.of(gp)).getStatusCode().value();
                } catch(RuntimeException ex) {
                    assertTrue(java.util.Set.of("LIBRARY_QUOTA_EXCEEDED",
                        "LIBRARY_UPLOAD_IN_PROGRESS").contains(ex.getMessage()),
                        "Unexpected concurrent outcome: "+ex.getMessage());
                    return 409;
                }
            };
            Future<Integer> left=pool.submit(task),right=pool.submit(task);
            start.countDown();
            int a=left.get(25,TimeUnit.SECONDS),b=right.get(25,TimeUnit.SECONDS);
            assertEquals(1,(a==201?1:0)+(b==201?1:0));
            var usage=jdbc.sql("""
                SELECT charged_bytes,reserved_bytes,charged_files,reserved_files
                FROM library_account_usage WHERE account_id=:owner
                """).param("owner",id).query().singleRow();
            assertEquals(209715200L,((Number)usage.get("charged_bytes")).longValue());
            assertEquals(0L,((Number)usage.get("reserved_bytes")).longValue());
            assertEquals(1L,((Number)usage.get("charged_files")).longValue());
            assertEquals(0L,((Number)usage.get("reserved_files")).longValue());
        } finally {
            start.countDown();
            pool.shutdownNow();
            transactions.executeWithoutResult(tx -> {
                jdbc.sql("DELETE FROM library_upload_operations WHERE owner_account_id=:id")
                    .param("id",id).update();
                jdbc.sql("""
                    DELETE FROM library_assets WHERE material_id IN
                    (SELECT id FROM library_materials WHERE owner_account_id=:id)
                    """).param("id",id).update();
                jdbc.sql("DELETE FROM library_materials WHERE owner_account_id=:id")
                    .param("id",id).update();
                jdbc.sql("DELETE FROM library_account_usage WHERE account_id=:id")
                    .param("id",id).update();
                jdbc.sql("DELETE FROM accounts WHERE id=:id").param("id",id).update();
            });
        }
    }
}
