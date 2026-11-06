package ai.guitarmentor.infrastructure.security;

import static org.junit.jupiter.api.Assertions.*;
import ai.guitarmentor.infrastructure.persistence.LibraryConversionWorker;
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

/** Real browser, local login/CSRF, TCP, PostgreSQL, scheduler and Docker. No API/worker mocks. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "guitar-mentor.security.enabled=true", "guitar-mentor.security.local-http=true",
    "server.address=127.0.0.1", "server.servlet.session.cookie.secure=false",
    "guitar-mentor.library.enabled=true", "guitar-mentor.library.conversion-enabled=true",
    "guitar-mentor.library.conversion-worker-enabled=true", "guitar-mentor.library.conversion-poll-ms=200",
    "guitar-mentor.library.cursor-secret=qa170e-only-isolated-cursor-secret-long-enough"
})
@EnabledIfEnvironmentVariable(named="GUITAR_MENTOR_TEST_POSTGRES", matches="true")
@EnabledIfEnvironmentVariable(named="GM_TEST_REAL_BROWSER", matches="true")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class LibraryConversionBrowserPostgresTest {
    static final String IMAGE="gm-conversion-worker:170e-browser-"+UUID.randomUUID();
    static final Path ROOT;
    static {
        try { ROOT=Files.createTempDirectory("qa170e-browser-"); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void storage(DynamicPropertyRegistry r) {
        r.add("guitar-mentor.library.storage-path", () -> ROOT.resolve("assets").toString());
        r.add("guitar-mentor.library.conversion-workspace", () -> ROOT.resolve("work").toString());
        r.add("guitar-mentor.library.conversion-image", () -> IMAGE);
    }
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired LibraryConversionWorker worker;

    UUID account(String login) {
        UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO accounts(id,role,status) VALUES(:id,'LEARNER','ACTIVE')").param("id",id).update();
        jdbc.sql("INSERT INTO local_credentials(account_id,email_normalized,login_normalized,password_hash,email_verified_at) VALUES(:id,:email,:login,:hash,now())")
            .param("id",id).param("email",login+"@example.invalid").param("login",login)
            .param("hash",Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode("qa170e-synthetic-password")).update();
        return id;
    }
    long count(String sql, UUID owner) { return jdbc.sql(sql).param("owner",owner).query(Long.class).single(); }

    @Test void browserUploadSelectCancelRetryDownloadAndOwnerIsolation() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8), nameA="qa170e-a-"+suffix, nameB="qa170e-b-"+suffix;
        UUID a=account(nameA), b=account(nameB);
        Path repo=Path.of("..").toAbsolutePath().normalize();
        Path evidence=repo.resolve("artifacts/conversion-170e/browser");
        Files.createDirectories(evidence);
        Process browser=null;
        try {
            docker("tag","gm-conversion-worker:170e",IMAGE);
            var command=new ProcessBuilder("node","tests/conversion-live-browser.mjs")
                .directory(repo.resolve("frontend").toFile()).redirectErrorStream(true)
                .redirectOutput(evidence.resolve("browser.log").toFile());
            command.environment().put("GM_TEST_BACKEND_URL","http://127.0.0.1:"+port);
            command.environment().put("GM_TEST_LOGIN_A",nameA);
            command.environment().put("GM_TEST_LOGIN_B",nameB);
            command.environment().put("GM_TEST_BROWSER_IMAGE",IMAGE);
            browser=command.start();
            assertTrue(browser.waitFor(4,TimeUnit.MINUTES),"Browser exceeded four minutes; inspect browser.log");
            assertEquals(0,browser.exitValue(),() -> {
                try { return Files.readString(evidence.resolve("browser.log")); }
                catch (Exception e) { return e.toString(); }
            });
            assertEquals(5,count("SELECT count(*) FROM library_conversion_jobs WHERE owner_account_id=:owner AND state='SUCCEEDED'",a));
            assertEquals(1,count("SELECT count(*) FROM library_conversion_jobs WHERE owner_account_id=:owner AND state='CANCELLED'",a));
            assertEquals(5,count("SELECT count(*) FROM library_conversion_output_lineage WHERE owner_account_id=:owner AND source_sha256=score_revision",a));
            assertEquals(16,count("SELECT count(*) FROM library_assets WHERE material_id IN (SELECT id FROM library_materials WHERE owner_account_id=:owner)",a));
            assertEquals(0,count("SELECT count(*) FROM library_publications WHERE owner_account_id=:owner",a));
            assertEquals(0,count("SELECT count(*) FROM library_conversion_worker_slots WHERE job_id IN (SELECT id FROM library_conversion_jobs WHERE owner_account_id=:owner)",a));
            var usage=jdbc.sql("SELECT charged_bytes,charged_files,reserved_bytes,reserved_files FROM library_account_usage WHERE account_id=:id").param("id",a).query().singleRow();
            assertEquals(16,((Number)usage.get("charged_files")).longValue());
            assertEquals(count("SELECT sum(byte_size) FROM library_assets WHERE material_id IN (SELECT id FROM library_materials WHERE owner_account_id=:owner)",a),((Number)usage.get("charged_bytes")).longValue());
            assertEquals(0,((Number)usage.get("reserved_bytes")).longValue());
            assertEquals(0,((Number)usage.get("reserved_files")).longValue());
            // Commit is visible just before the runner's finally removes its workspace.
            for(int i=0;i<50 && !workEmpty();i++) Thread.sleep(100);
            assertTrue(workEmpty(),"Completed jobs must remove their temporary workspaces");
            System.out.println("170-E LIVE BROWSER PASS: GP3/4/5, selection, cancellation, real image failure/retry, downloads, account isolation, quota and cleanup");
        } finally {
            if(browser!=null && browser.isAlive()) {
                browser.descendants().forEach(p -> p.destroyForcibly());
                browser.destroyForcibly(); browser.waitFor(10,TimeUnit.SECONDS);
            }
            worker.close();
            for(int i=0;i<50 && !workEmpty();i++) Thread.sleep(100);
            for(UUID owner:List.of(a,b)) {
                jdbc.sql("UPDATE library_conversion_worker_slots SET job_id=NULL,lease_owner=NULL,lease_until=NULL,heartbeat_at=NULL WHERE job_id IN (SELECT id FROM library_conversion_jobs WHERE owner_account_id=:owner)").param("owner",owner).update();
                for(String sql:List.of(
                    "DELETE FROM library_conversion_output_lineage WHERE owner_account_id=:owner",
                    "DELETE FROM library_conversion_jobs WHERE owner_account_id=:owner",
                    "DELETE FROM library_upload_operations WHERE owner_account_id=:owner",
                    "DELETE FROM library_assets WHERE material_id IN (SELECT id FROM library_materials WHERE owner_account_id=:owner)",
                    "DELETE FROM library_materials WHERE owner_account_id=:owner",
                    "DELETE FROM library_account_usage WHERE account_id=:owner",
                    "DELETE FROM accounts WHERE id=:owner")) jdbc.sql(sql).param("owner",owner).update();
            }
            try(var paths=Files.walk(ROOT)) { for(Path p:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
            docker("image","rm",IMAGE);
        }
    }
    void docker(String... args) throws Exception {
        var command=new ArrayList<String>(); command.add("docker"); command.addAll(List.of(args));
        var p=new ProcessBuilder(command).redirectErrorStream(true).start();
        if(!p.waitFor(15,TimeUnit.SECONDS)) { p.destroyForcibly(); fail("Docker image operation timed out"); }
        assertEquals(0,p.exitValue(),new String(p.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
    }
    boolean workEmpty() throws Exception {
        if(!Files.isDirectory(ROOT.resolve("work"))) return true;
        try(var paths=Files.list(ROOT.resolve("work"))) { return paths.findAny().isEmpty(); }
    }
}
