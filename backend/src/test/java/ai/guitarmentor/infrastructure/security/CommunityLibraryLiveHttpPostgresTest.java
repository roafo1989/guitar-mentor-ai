package ai.guitarmentor.infrastructure.security;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** #168: real TCP, independent cookie jars, real local login/CSRF and PostgreSQL. No MockMvc. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "guitar-mentor.security.enabled=true", "guitar-mentor.library.enabled=true", "guitar-mentor.security.local-http=true",
    "server.address=127.0.0.1", "server.servlet.session.cookie.secure=false",
    "guitar-mentor.library.cursor-secret=qa168-only-isolated-cursor-secret-long-enough"
})
@Import(SecurityHttpIntegrationTest.FakeOidcRegistration.class)
@EnabledIfEnvironmentVariable(named="GUITAR_MENTOR_TEST_POSTGRES",matches="true")
class CommunityLibraryLiveHttpPostgresTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final Path ROOT;
    static {try{ROOT=Files.createTempDirectory("qa168-live-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void storage(DynamicPropertyRegistry r){r.add("guitar-mentor.library.storage-path",ROOT::toString);}
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    URI url(String path){return URI.create("http://127.0.0.1:"+port+path);}
    final class Session {
        final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        final HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(10)).build();
        String csrfHeader,csrfToken;
        HttpResponse<String> request(String method,String path,String body,Map<String,String> headers) throws Exception {
            var b=HttpRequest.newBuilder(url(path)).timeout(Duration.ofSeconds(30));
            headers.forEach(b::header);
            if(!method.equals("GET")){b.header(csrfHeader,csrfToken);}
            return client.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        }
        void csrf() throws Exception {var r=request("GET","/api/v1/csrf",null,Map.of());assertEquals(200,r.statusCode(),r.body());var n=JSON.readTree(r.body());csrfHeader=n.path("headerName").asText();csrfToken=n.path("token").asText();assertFalse(csrfToken.isBlank());}
        void login(String name) throws Exception {csrf();var r=request("POST","/api/v1/local-auth/login",JSON.writeValueAsString(Map.of("identifier",name,"password","qa168-synthetic-password")),Map.of("Content-Type","application/json"));assertEquals(200,r.statusCode(),r.body());csrf();}
    }
    UUID account(String login){
        UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO accounts(id,role,status) VALUES(:id,'LEARNER','ACTIVE')").param("id",id).update();
        jdbc.sql("INSERT INTO local_credentials(account_id,email_normalized,login_normalized,password_hash,email_verified_at) VALUES(:id,:email,:login,:hash,now())")
          .param("id",id).param("email",login+"@example.invalid").param("login",login)
          .param("hash",Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode("qa168-synthetic-password")).update();
        return id;
    }
    @Test void realIndependentSessionsPublishReadEditStableWithdrawDeny() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8),nameA="qa168-a-"+suffix,nameB="qa168-b-"+suffix;
        UUID a=account(nameA),b=account(nameB);
        try {
            var alice=new Session();var bob=new Session();var guest=new Session();alice.login(nameA);bob.login(nameB);
            String boundary="qa168"+suffix;
            String multipart="--"+boundary+"\r\nContent-Disposition: form-data; name=\"metadata\"\r\nContent-Type: application/json\r\n\r\n{\"title\":\"QA168 original\",\"type\":\"ETUDE\"}\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"files\"; filename=\"synthetic.pdf\"\r\nContent-Type: application/pdf\r\n\r\n%PDF-1.7\nSynthetic QA168 score\n\r\n--"+boundary+"--\r\n";
            var created=alice.request("POST","/api/v1/library/materials",multipart,Map.of("Content-Type","multipart/form-data; boundary="+boundary,"Idempotency-Key",UUID.randomUUID().toString()));
            assertEquals(201,created.statusCode(),created.body());JsonNode material=JSON.readTree(created.body());String id=material.path("id").asText(),asset=material.path("files").get(0).path("id").asText();
            String own="/api/v1/library/materials/"+id;
            assertEquals(404,bob.request("GET",own,null,Map.of()).statusCode());
            assertEquals(404,bob.request("GET",own+"/publications",null,Map.of()).statusCode());
            String body=JSON.writeValueAsString(Map.of("assets",List.of(Map.of("assetId",asset,"provenance","CREATED","rightsBasis","OWN_WORK","rightsEvidence","Synthetic original score created for this isolated test.","attribution","QA168")),"tags",List.of("qa168","fingerstyle")));
            String key=UUID.randomUUID().toString();var headers=Map.of("Content-Type","application/json","If-Match","\"1\"","Idempotency-Key",key);
            assertEquals(404,bob.request("POST",own+"/publications",body,headers).statusCode());
            var published=alice.request("POST",own+"/publications",body,headers);assertEquals(201,published.statusCode(),published.body());
            JsonNode snapshot=JSON.readTree(published.body());String common="/api/v1/community/publications/"+snapshot.path("id").asText();
            assertEquals(200,alice.request("POST",own+"/publications",body,headers).statusCode());
            assertEquals(401,guest.request("GET",common,null,Map.of()).statusCode());
            var read=bob.request("GET",common,null,Map.of());assertEquals(200,read.statusCode(),read.body());assertEquals("QA168 original",JSON.readTree(read.body()).path("title").asText());
            assertFalse(JSON.readTree(read.body()).has("ownerAccountId"));assertFalse(JSON.readTree(read.body()).has("materialId"));
            assertEquals(200,bob.request("GET",common+"/files/"+asset+"/content",null,Map.of()).statusCode());
            var edited=alice.request("PATCH",own,"{\"title\":\"QA168 changed draft\"}",Map.of("Content-Type","application/json","If-Match","\"1\""));assertEquals(200,edited.statusCode(),edited.body());
            var stable=bob.request("GET",common,null,Map.of());assertEquals(JSON.readTree(read.body()),JSON.readTree(stable.body()),"draft edit cannot change snapshot");
            var withdrawn=alice.request("POST",own+"/publications/"+snapshot.path("id").asText()+"/withdraw","{}",Map.of("Content-Type","application/json","If-Match","\""+snapshot.path("revision").asLong()+"\""));assertEquals(200,withdrawn.statusCode(),withdrawn.body());
            assertEquals(404,bob.request("GET",common,null,Map.of()).statusCode());assertEquals(404,bob.request("GET",common+"/files/"+asset+"/content",null,Map.of()).statusCode());
            System.out.println("QA168 LIVE HTTP/PostgreSQL PASS: independent A/B cookie jars, guest deny, publish replay, draft private, snapshot stable, withdraw revokes detail+bytes");
        } finally {
            // Only synthetic accounts made by this test; all paths live under a unique temp root.
            for(String sql:List.of(
                "DELETE FROM library_publication_tags WHERE publication_id IN (SELECT id FROM library_publications WHERE owner_account_id=:a)",
                "DELETE FROM library_publication_events WHERE actor_account_id=:a",
                "DELETE FROM library_publication_assets WHERE publication_id IN (SELECT id FROM library_publications WHERE owner_account_id=:a)",
                "DELETE FROM library_publications WHERE owner_account_id=:a",
                "DELETE FROM library_upload_operations WHERE owner_account_id=:a",
                "DELETE FROM library_assets WHERE material_id IN (SELECT id FROM library_materials WHERE owner_account_id=:a)",
                "DELETE FROM library_materials WHERE owner_account_id=:a",
                "DELETE FROM library_account_usage WHERE account_id=:a")) jdbc.sql(sql).param("a",a).update();
            jdbc.sql("DELETE FROM accounts WHERE id IN (:a,:b)").param("a",a).param("b",b).update();
            try(var paths=Files.walk(ROOT)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
        }
    }
}
