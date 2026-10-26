package ai.guitarmentor.infrastructure.security;
import static org.junit.jupiter.api.Assertions.*;
import javax.sql.DataSource;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
@SpringBootTest
@EnabledIfEnvironmentVariable(named="GUITAR_MENTOR_TEST_POSTGRES",matches="true")
class UsernameMigrationPostgresTest {
    @Autowired DataSource source;
    @Test void upgradesPopulatedAsciiTablesWithoutRenamingOrRehashing() throws Exception {
        String schema="qa232_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=source.getConnection();var sql=connection.createStatement()) {
            String old=connection.getSchema();sql.execute("CREATE SCHEMA "+schema);
            try {
                connection.setSchema(schema);
                sql.execute("CREATE TABLE local_credentials(login_normalized varchar(64) UNIQUE, password_hash text, CONSTRAINT local_login_shape CHECK(login_normalized ~ '^[a-z0-9][a-z0-9._-]{2,63}$'))");
                sql.execute("CREATE TABLE pending_local_registrations(login_normalized varchar(64) UNIQUE)");
                sql.execute("CREATE TABLE pending_local_links(login_normalized varchar(64) UNIQUE)");
                sql.execute("INSERT INTO local_credentials VALUES ('existing-player','unchanged-hash')");
                new ResourceDatabasePopulator(new ClassPathResource("db/migration/V30__unicode_account_logins.sql")).populate(connection);
                try(var rows=sql.executeQuery("SELECT login_normalized,password_hash FROM local_credentials")) {assertTrue(rows.next());assertEquals("existing-player",rows.getString(1));assertEquals("unchanged-hash",rows.getString(2));}
                sql.execute("INSERT INTO local_credentials VALUES ('роман','test-hash')");
                sql.execute("INSERT INTO pending_local_registrations VALUES ('игрок')");
                sql.execute("INSERT INTO pending_local_links VALUES ('музыка')");
                assertThrows(java.sql.SQLException.class,()->sql.execute("INSERT INTO local_credentials VALUES ('bad user','hash')"));
            } finally { connection.setSchema(old);sql.execute("DROP SCHEMA "+schema+" CASCADE"); }
        }
    }
}
