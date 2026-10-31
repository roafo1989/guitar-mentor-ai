package ai.guitarmentor.infrastructure.persistence;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** PostgreSQL integration checks durable intent, failed disk deletion and quota release. */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named="GUITAR_MENTOR_TEST_POSTGRES",matches="true")
class LibraryDeletionWorkerPostgresTest {
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired LibraryMaterialRepository materials;
    @TempDir Path temp;

    private UUID create(UUID materialId,String key) {
        UUID owner=UUID.randomUUID();
        jdbc.sql("INSERT INTO accounts(id,role,status) VALUES(:id,'LEARNER','ACTIVE')")
            .param("id",owner).update();
        jdbc.sql("""
            INSERT INTO library_account_usage(account_id,charged_bytes,charged_files)
            VALUES(:owner,123,1)
            """).param("owner",owner).update();
        jdbc.sql("""
            INSERT INTO library_materials(id,owner_account_id,title,material_type,state,revision)
            VALUES(:id,:owner,'Test','COMPOSITION','READY',1)
            """).param("id",materialId).param("owner",owner).update();
        jdbc.sql("""
            INSERT INTO library_assets(id,material_id,object_key,original_name,format,byte_size,sha256)
            VALUES(:id,:material,:key,'score.pdf','PDF',123,:sha)
            """).param("id",UUID.randomUUID()).param("material",materialId)
            .param("key",key).param("sha","a".repeat(64)).update();
        return owner;
    }

    private long count(String table,UUID material) {
        return jdbc.sql("SELECT count(*) FROM "+table+" WHERE material_id=:id")
            .param("id",material).query(Long.class).single();
    }

    @Test void deletionHidesImmediatelyReleasesQuotaOnlyAfterFilesGone() throws IOException {
        UUID id=UUID.randomUUID(), key=UUID.randomUUID();
        UUID owner=create(id,key.toString());
        assertEquals(1,materials.markDeleting(owner,id,1));
        materials.queueDeletion(id);
        assertTrue(materials.find(owner,id).isEmpty());
        assertEquals(0,materials.page(owner,null,null,20).size());

        var storage=mock(LibraryObjectStore.class);
        when(storage.isAvailable()).thenReturn(true);
        var worker=new LibraryDeletionWorker(jdbc,transactions,storage,true);
        assertEquals(1,worker.processBatch(10));
        verify(storage).delete(key.toString());
        assertEquals(0,count("library_assets",id));
        assertEquals(0,count("library_deletion_jobs",id));
        var usage=jdbc.sql("""
            SELECT charged_bytes,charged_files FROM library_account_usage WHERE account_id=:owner
            """).param("owner",owner).query().singleRow();
        assertEquals(0L,((Number)usage.get("charged_bytes")).longValue());
        assertEquals(0L,((Number)usage.get("charged_files")).longValue());
        assertEquals("DELETING",materials.findIncludingDeleting(owner,id).orElseThrow().state());
        assertEquals(0,worker.processBatch(10)); // no duplicate release
    }

    @Test void actualPrivateObjectIsPhysicallyRemoved() throws IOException {
        var store=new LibraryObjectStore(temp.resolve("library").toString(),
            temp.resolve("attempts").toString(),temp.resolve("scores").toString());
        byte[] bytes=new byte[123];
        System.arraycopy("%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
            0,bytes,0,5);
        var stored=store.put(new ByteArrayInputStream(bytes),1024);
        UUID id=UUID.randomUUID();
        UUID owner=create(id,stored.key());
        assertEquals(1,materials.markDeleting(owner,id,1));
        materials.queueDeletion(id);
        assertEquals(1,new LibraryDeletionWorker(jdbc,transactions,store,true).processBatch(10));
        assertThrows(IOException.class,()->store.file(stored.key()));
        assertEquals(0,count("library_assets",id));
        assertEquals(0,count("library_deletion_jobs",id));
    }

    @Test void diskFailurePreservesQuotaAssetsAndSchedulesRetry() throws IOException {
        UUID id=UUID.randomUUID(), key=UUID.randomUUID();
        UUID owner=create(id,key.toString());
        assertEquals(1,materials.markDeleting(owner,id,1));
        materials.queueDeletion(id);
        var storage=mock(LibraryObjectStore.class);
        when(storage.isAvailable()).thenReturn(true);
        doThrow(new IOException("synthetic disk failure")).when(storage).delete(key.toString());
        var worker=new LibraryDeletionWorker(jdbc,transactions,storage,true);
        assertEquals(0,worker.processBatch(10));
        assertEquals(1,count("library_assets",id));
        assertEquals(1,count("library_deletion_jobs",id));
        var job=jdbc.sql("""
            SELECT state,attempts FROM library_deletion_jobs WHERE material_id=:id
            """).param("id",id).query().singleRow();
        assertEquals("RETRY",job.get("state"));
        assertEquals(1,((Number)job.get("attempts")).intValue());
        Long charged=jdbc.sql("""
            SELECT charged_bytes FROM library_account_usage WHERE account_id=:owner
            """).param("owner",owner).query(Long.class).single();
        assertEquals(123L,charged);
        // The actual scheduled backoff expires later; simulate passage of time on test DB.
        jdbc.sql("UPDATE library_deletion_jobs SET next_attempt_at=now()-interval '1 second' WHERE material_id=:id")
            .param("id",id).update();
        reset(storage);
        when(storage.isAvailable()).thenReturn(true);
        assertEquals(1,worker.processBatch(10));
        assertEquals(0,count("library_assets",id));
        assertEquals(0,count("library_deletion_jobs",id));
    }
}
