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
class MusicCatalogRepositoryPostgresTest {
    @Autowired JdbcClient jdbc;
    @Autowired MusicCatalogRepository catalog;

    private UUID account() {
        UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO accounts(id,role,status) VALUES(:id,'LEARNER','ACTIVE')")
            .param("id",id).update();
        return id;
    }
    private void add(UUID owner, String workId,String title,String artist,
            String arrangementId,String ext,String parser) {
        jdbc.sql("""
            INSERT INTO music_catalog_works(owner_account_id,source_work_id,title,artist,
                normalized_title,normalized_artist,identity_status,grouping_method)
            VALUES(:owner,:id,:title,:artist,lower(:title),lower(:artist),'CANDIDATE','test')
            """).param("owner",owner).param("id",workId).param("title",title)
            .param("artist",artist).update();
        jdbc.sql("""
            INSERT INTO music_catalog_arrangements(owner_account_id,source_arrangement_id,
                source_work_id,name,identity_status,parser_status)
            VALUES(:owner,:id,:work,:name,'UNVERIFIED',:parser)
            """).param("owner",owner).param("id",arrangementId).param("work",workId)
            .param("name",title+" guitar").param("parser",parser).update();
        jdbc.sql("""
            INSERT INTO music_catalog_source_files(owner_account_id,source_file_id,
                source_arrangement_id,relative_path,original_filename,source_group,
                extension,size_bytes,rights_status,visibility)
            VALUES(:owner,:id,:arr,:path,:name,'test',:ext,123,'UNKNOWN','PRIVATE')
            """).param("owner",owner).param("id","f-"+arrangementId)
            .param("arr",arrangementId).param("path","secret/"+arrangementId+ext)
            .param("name",title+ext).param("ext",ext).update();
    }

    @Test void ownerIsolationAndFilesHaveNoPaths() {
        UUID alice=account(),bob=account();
        add(alice,"one","Yesterday","Beatles","arr1",".gp5","PARSED");
        add(bob,"other","Чужая","Секрет","arr2",".gpx","PARSED");
        assertEquals(1,catalog.search(alice,"","","","title",20,0).size());
        assertEquals(0,catalog.search(bob,"Yesterday","","","title",20,0).size());
        assertTrue(catalog.work(bob,"one").isEmpty());
        assertTrue(catalog.arrangement(bob,"arr1").isEmpty());
        assertTrue(catalog.arrangements(bob,"one").isEmpty());
        assertTrue(catalog.files(bob,"arr1").isEmpty());
        var file=catalog.files(alice,"arr1").getFirst();
        assertEquals("UNAVAILABLE",file.originalAvailability());
        assertEquals("Yesterday.gp5",file.name());
        assertEquals(7,file.getClass().getRecordComponents().length);
    }

    @Test void unicodeLiteralSearchFormatParserAndPageBoundaries() {
        UUID owner=account();
        add(owner,"a","Ветер перемен","Дунаевский","aaa",".gp5","PARSED");
        add(owner,"b","Windy & Warm","Chet Atkins","bbb",".gpx","PARSED");
        add(owner,"c","Yesterday","Beatles","ccc",".pdf","METADATA_ONLY");
        assertEquals("a",catalog.search(owner,"ветер","","","title",5,0).getFirst().id());
        assertEquals("a",catalog.search(owner,"Дунаевский","","","artist",5,0).getFirst().id());
        assertTrue(catalog.search(owner,"%","","","title",20,0).isEmpty()); // percent must be literal
        assertEquals("b",catalog.search(owner,"",".gpx","PARSED","title",5,0).getFirst().id());
        assertEquals(0,catalog.search(owner,"",".gpx","METADATA_ONLY","title",5,0).size());
        var first=catalog.search(owner,"","","","title",1,0);
        var next=catalog.search(owner,"","","","title",1,1);
        assertEquals(1,first.size());assertEquals(1,next.size());
        assertNotEquals(first.getFirst().id(),next.getFirst().id());
        assertTrue(catalog.search(owner,"","","","title",20,3).isEmpty());
    }
}
