package ai.guitarmentor.interfaces.rest.library;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Both clean install and populated V34 -> current migration in disposable schemas. */
@EnabledIfEnvironmentVariable(named="GUITAR_MENTOR_TEST_POSTGRES",matches="true")
class CommunityWorkMigrationPostgresTest {
    @Test void cleanAndPopulatedUpgradePreservePrivateAndPublishedData() throws Exception {
        String url=System.getenv("SPRING_DATASOURCE_URL"),user=System.getenv("SPRING_DATASOURCE_USERNAME"),password=System.getenv("SPRING_DATASOURCE_PASSWORD");
        for(boolean upgrade:List.of(false,true)) {
            String schema="qa204_"+UUID.randomUUID().toString().replace("-","");
            try(var connection=DriverManager.getConnection(url,user,password);var sql=connection.createStatement()) {
                var config=Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema);
                if(upgrade) config.target("34");config.load().migrate();
                sql.execute("SET search_path TO "+schema);
                UUID account=UUID.randomUUID(),material=UUID.randomUUID(),publication=UUID.randomUUID();
                sql.execute("INSERT INTO accounts(id,role,status) VALUES('"+account+"','LEARNER','ACTIVE')");
                sql.execute("INSERT INTO library_materials(id,owner_account_id,title,material_type,state) VALUES('"+material+"','"+account+"','Existing private title','ETUDE','READY')");
                sql.execute("INSERT INTO library_publications(id,material_id,owner_account_id,version,material_revision,idempotency_key,request_fingerprint,title,material_type,author,state) VALUES('"+publication+"','"+material+"','"+account+"',1,1,'"+UUID.randomUUID()+"','"+"a".repeat(64)+"','Existing snapshot','ETUDE','Existing author','PUBLISHED')");
                if(upgrade) Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).load().migrate();
                try(var row=sql.executeQuery("SELECT title,work_id FROM library_publications WHERE id='"+publication+"'")){assertTrue(row.next());assertEquals("Existing snapshot",row.getString(1));assertNull(row.getObject(2));}
                try(var row=sql.executeQuery("SELECT count(*) FROM community_works")){row.next();assertEquals(0,row.getInt(1));}
                try(var row=sql.executeQuery("SELECT count(*) FROM library_material_work_links")){row.next();assertEquals(0,row.getInt(1));}
                Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).load().validate();
            } finally {
                try(var connection=DriverManager.getConnection(url,user,password);var sql=connection.createStatement()){sql.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");}
            }
        }
    }
}
