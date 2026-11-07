package ai.guitarmentor.infrastructure.security;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real Chromium, local login, CSRF, PostgreSQL and protected storage. Synthetic accounts only. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "guitar-mentor.security.enabled=true", "guitar-mentor.security.local-http=true",
    "server.address=127.0.0.1", "server.servlet.session.cookie.secure=false",
    "guitar-mentor.library.enabled=true", "guitar-mentor.library.conversion-enabled=false",
    "guitar-mentor.library.cursor-secret=qa196-only-isolated-cursor-secret-long-enough"
})
@EnabledIfEnvironmentVariable(named="GUITAR_MENTOR_TEST_POSTGRES", matches="true")
@EnabledIfEnvironmentVariable(named="GM_TEST_REAL_BROWSER", matches="true")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class MusicCatalogOriginalBrowserPostgresTest {
    static final Path ROOT;
    static {try {ROOT=Files.createTempDirectory("gm196-browser-");} catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void storage(DynamicPropertyRegistry r) {r.add("guitar-mentor.library.storage-path",()->ROOT.toString());}
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    UUID account(String login,boolean index) {
        UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO accounts(id,role,status) VALUES(:id,'LEARNER','ACTIVE')").param("id",id).update();
        jdbc.sql("INSERT INTO local_credentials(account_id,email_normalized,login_normalized,password_hash,email_verified_at) VALUES(:id,:email,:login,:hash,now())")
            .param("id",id).param("email",login+"@example.invalid").param("login",login)
            .param("hash",Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode("qa196-synthetic-password")).update();
        if(index)MusicCatalogOriginalHttpPostgresTest.seed(jdbc,id);
        return id;
    }
    @Test void browserUploadAttachDownloadAndIsolation() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8),nameA="qa196-a-"+suffix,nameB="qa196-b-"+suffix;
        UUID a=account(nameA,true),b=account(nameB,false);
        Path repo=Path.of("..").toAbsolutePath().normalize(),evidence=repo.resolve("artifacts/catalog-196/browser");
        Files.createDirectories(evidence);
        Process browser=null;
        try {
            var command=new ProcessBuilder("node","tests/music-archive-live-browser.mjs")
                .directory(repo.resolve("frontend").toFile()).redirectErrorStream(true)
                .redirectOutput(evidence.resolve("browser.log").toFile());
            command.environment().put("GM_TEST_BACKEND_URL","http://127.0.0.1:"+port);
            command.environment().put("GM_TEST_LOGIN_A",nameA);command.environment().put("GM_TEST_LOGIN_B",nameB);
            browser=command.start();
            assertTrue(browser.waitFor(3,TimeUnit.MINUTES),"Browser exceeded three minutes");
            assertEquals(0,browser.exitValue(),()->{try{return Files.readString(evidence.resolve("browser.log"));}catch(Exception e){return e.toString();}});
            assertEquals(2,jdbc.sql("SELECT count(*) FROM music_catalog_source_files WHERE owner_account_id=:o AND original_asset_id IS NOT NULL").param("o",a).query(Integer.class).single());
        } finally {
            if(browser!=null && browser.isAlive()){browser.descendants().forEach(p->p.destroyForcibly());browser.destroyForcibly();}
            for(UUID owner:List.of(a,b)) {
                for(String sql:List.of(
                    "DELETE FROM music_catalog_review_queue WHERE owner_account_id=:owner",
                    "DELETE FROM music_catalog_source_files WHERE owner_account_id=:owner",
                    "DELETE FROM music_catalog_arrangements WHERE owner_account_id=:owner",
                    "DELETE FROM music_catalog_works WHERE owner_account_id=:owner",
                    "DELETE FROM library_upload_operations WHERE owner_account_id=:owner",
                    "DELETE FROM library_assets WHERE material_id IN (SELECT id FROM library_materials WHERE owner_account_id=:owner)",
                    "DELETE FROM library_materials WHERE owner_account_id=:owner",
                    "DELETE FROM library_account_usage WHERE account_id=:owner",
                    "DELETE FROM accounts WHERE id=:owner"))jdbc.sql(sql).param("owner",owner).update();
            }
            try(var paths=Files.walk(ROOT)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
    }
}
