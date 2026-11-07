package ai.guitarmentor.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@EnabledIfEnvironmentVariable(named="GUITAR_MENTOR_TEST_POSTGRES",matches="true")
class MusicCatalogOriginalMigrationPostgresTest {
    @Autowired DataSource datasource;
    @Test void v35IndexRowsRemainUnavailableAfterV36() throws Exception {
        String schema="cat196_"+UUID.randomUUID().toString().replace("-","");
        try(var c=datasource.getConnection();var s=c.createStatement()){s.execute("CREATE SCHEMA "+schema);}
        try {
            Flyway.configure().dataSource(datasource).schemas(schema).defaultSchema(schema).target("35").load().migrate();
            UUID owner=UUID.randomUUID();
            try(var c=datasource.getConnection();var s=c.createStatement()) {
                String previous=c.getSchema();
                try {
                    c.setSchema(schema);
                    s.execute("INSERT INTO accounts(id,role,status) VALUES('"+owner+"','LEARNER','ACTIVE')");
                    s.execute("INSERT INTO music_catalog_works(owner_account_id,source_work_id,title,identity_status,grouping_method) VALUES('"+owner+"','old','Legacy','CANDIDATE','test')");
                    s.execute("INSERT INTO music_catalog_arrangements(owner_account_id,source_arrangement_id,source_work_id,name,identity_status,parser_status) VALUES('"+owner+"','old','old','Legacy','UNRESOLVED','METADATA_ONLY')");
                    s.execute("INSERT INTO music_catalog_source_files(owner_account_id,source_file_id,source_arrangement_id,relative_path,original_filename,source_group,extension,size_bytes,rights_status) VALUES('"+owner+"','old','old','unread/local/path','legacy.pdf','test','.pdf',123,'course_private')");
                } finally {c.setSchema(previous==null?"public":previous);}
            }
            Flyway.configure().dataSource(datasource).schemas(schema).defaultSchema(schema).load().migrate();
            try(var c=datasource.getConnection();var s=c.createStatement()) {
                String previous=c.getSchema();
                try {
                    c.setSchema(schema);
                    try(var r=s.executeQuery("SELECT original_asset_id,rights_status,visibility,relative_path,size_bytes FROM music_catalog_source_files")) {
                        assertTrue(r.next());assertNull(r.getObject(1));assertEquals("course_private",r.getString(2));
                        assertEquals("PRIVATE",r.getString(3));assertEquals("unread/local/path",r.getString(4));assertEquals(123,r.getLong(5));assertFalse(r.next());
                    }
                    // FK cleanup preserves metadata when the underlying original is physically removed.
                    UUID material=UUID.randomUUID(),asset=UUID.randomUUID();
                    s.execute("INSERT INTO library_materials(id,owner_account_id,title,material_type,state) VALUES('"+material+"','"+owner+"','Legacy file','OTHER','READY')");
                    s.execute("INSERT INTO library_assets(id,material_id,object_key,original_name,format,byte_size,sha256) VALUES('"+asset+"','"+material+"','synthetic','file.pdf','PDF',123,'"+"a".repeat(64)+"')");
                    s.execute("UPDATE music_catalog_source_files SET original_asset_id='"+asset+"'");
                    s.execute("DELETE FROM library_assets WHERE id='"+asset+"'");
                    try(var r=s.executeQuery("SELECT original_asset_id FROM music_catalog_source_files")){assertTrue(r.next());assertNull(r.getObject(1));}
                } finally {c.setSchema(previous==null?"public":previous);}
            }
        } finally {try(var c=datasource.getConnection();var s=c.createStatement()){s.execute("DROP SCHEMA "+schema+" CASCADE");}}
    }
}
