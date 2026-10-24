package ai.guitarmentor.infrastructure.persistence;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named="GUITAR_MENTOR_TEST_POSTGRES",matches="true")
class LibraryMaterialPostgresTest {
    @Autowired JdbcClient jdbc;
    @Autowired LibraryMaterialRepository materials;

    private UUID account() {
        UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO accounts(id,role,status) VALUES(:id,'LEARNER','ACTIVE')")
            .param("id",id).update();
        return id;
    }
    private UUID material(UUID owner,String title) {
        UUID id=UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO library_materials(id,owner_account_id,title,material_type,state)
            VALUES(:id,:owner,:title,'ETUDE','READY')
            """).param("id",id).param("owner",owner).param("title",title).update();
        return id;
    }

    @Test void privateAccountsNeverReadOrEditEachOthersMaterials() {
        UUID alice=account(),bob=account(),id=material(alice,"Blues");
        assertTrue(materials.find(alice,id).isPresent());
        assertTrue(materials.find(bob,id).isEmpty());
        assertEquals(0,materials.update(bob,id,1,"stolen","OTHER",null));
        assertEquals("Blues",materials.find(alice,id).orElseThrow().title());
        assertEquals(1,materials.update(alice,id,1,"Updated","COMPOSITION",null));
        assertEquals(0,materials.update(alice,id,1,"stale","COMPOSITION",null));
        assertEquals(2,materials.find(alice,id).orElseThrow().revision());
    }
    @Test void keysetNeverRepeatsAReadRowAfterConcurrentInsert() {
        UUID owner=account();
        UUID first=material(owner,"First");
        // Ensure tuple order is deterministic independent of Java UUID ordering.
        UUID second=material(owner,"Second");
        var older=materials.page(owner,null,null,1).getFirst();
        UUID inserted=material(owner,"Newly inserted");
        var rest=materials.page(owner,older.createdAt(),older.id(),20);
        assertTrue(rest.stream().noneMatch(m->m.id().equals(older.id())));
        assertTrue(rest.size()<=2);
        assertEquals(3,materials.page(owner,null,null,20).size());
    }
    @Test void pendingAndDeletingCardsAreNeverVisible() {
        UUID owner=account();
        UUID id=material(owner,"Temporary");
        assertEquals(1,materials.markDeleting(owner,id,1));
        assertTrue(materials.find(owner,id).isEmpty());
        assertTrue(materials.page(owner,null,null,20).isEmpty());
        materials.queueDeletion(id);
        assertEquals(1L,jdbc.sql("SELECT count(*) FROM library_deletion_jobs WHERE material_id=:id")
            .param("id",id).query(Long.class).single());
    }
}
