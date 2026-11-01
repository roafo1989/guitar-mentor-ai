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

/** #204: real TCP, independent cookie jars, real local login/CSRF and PostgreSQL. No MockMvc. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "guitar-mentor.security.enabled=true", "guitar-mentor.library.enabled=true", "guitar-mentor.security.local-http=true",
    "server.address=127.0.0.1", "server.servlet.session.cookie.secure=false",
    "guitar-mentor.library.cursor-secret=qa204-only-isolated-cursor-secret-long-enough"
})
@Import(SecurityHttpIntegrationTest.FakeOidcRegistration.class)
@EnabledIfEnvironmentVariable(named="GUITAR_MENTOR_TEST_POSTGRES",matches="true")
class CommunityWorkLiveHttpPostgresTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final Path ROOT;
    static {try{ROOT=Files.createTempDirectory("qa204-live-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
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
        void login(String name) throws Exception {csrf();var r=request("POST","/api/v1/local-auth/login",JSON.writeValueAsString(Map.of("identifier",name,"password","qa204-synthetic-password")),Map.of("Content-Type","application/json"));assertEquals(200,r.statusCode(),r.body());csrf();}
    }
    UUID account(String login){
        UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO accounts(id,role,status) VALUES(:id,'LEARNER','ACTIVE')").param("id",id).update();
        jdbc.sql("INSERT INTO local_credentials(account_id,email_normalized,login_normalized,password_hash,email_verified_at) VALUES(:id,:email,:login,:hash,now())")
          .param("id",id).param("email",login+"@example.invalid").param("login",login)
          .param("hash",Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode("qa204-synthetic-password")).update();
        return id;
    }
    Map<String,String> headers(String revision) {return Map.of("Content-Type","application/json","If-Match","\""+revision+"\"","Idempotency-Key",UUID.randomUUID().toString());}
    JsonNode upload(Session s,String suffix) throws Exception {
        String boundary="qa204"+suffix;
        String multipart="--"+boundary+"\r\nContent-Disposition: form-data; name=\"metadata\"\r\nContent-Type: application/json\r\n\r\n{\"title\":\"QA204 arrangement\",\"type\":\"ETUDE\"}\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"files\"; filename=\"synthetic.pdf\"\r\nContent-Type: application/pdf\r\n\r\n%PDF-1.7\nSynthetic QA204\n\r\n--"+boundary+"--\r\n";
        var r=s.request("POST","/api/v1/library/materials",multipart,Map.of("Content-Type","multipart/form-data; boundary="+boundary,"Idempotency-Key",UUID.randomUUID().toString()));
        assertEquals(201,r.statusCode(),r.body());return JSON.readTree(r.body());
    }
    JsonNode publish(Session s,JsonNode material) throws Exception {
        String body=JSON.writeValueAsString(Map.of("assets",List.of(Map.of("assetId",material.path("files").get(0).path("id").asText(),"provenance","CREATED","rightsBasis","OWN_WORK","rightsEvidence","Original synthetic score for isolated QA204 test."))));
        var r=s.request("POST","/api/v1/library/materials/"+material.path("id").asText()+"/publications",body,headers("2"));assertEquals(201,r.statusCode(),r.body());return JSON.readTree(r.body());
    }
    @Test void separateCookieJarsSharedIdentityOwnershipSnapshotsAndCsrf() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        UUID a=account("qa204-a-"+suffix),b=account("qa204-b-"+suffix),o=account("qa204-o-"+suffix);
        jdbc.sql("UPDATE accounts SET role='OWNER' WHERE id=:id").param("id",o).update();
        try {
        var alice=new Session();var bob=new Session();var owner=new Session();var guest=new Session();
        alice.login("qa204-a-"+suffix);bob.login("qa204-b-"+suffix);owner.login("qa204-o-"+suffix);
        String candidates="/api/v1/community/work-candidates",title="QA204 shared "+suffix;
        var cr=alice.request("POST",candidates,JSON.writeValueAsString(Map.of("title",title,"artist","Synthetic")),headers("1"));assertEquals(200,cr.statusCode(),cr.body());
        String work=JSON.readTree(cr.body()).path("id").asText(),verify="/api/v1/admin/community/works/"+work+"/verify",detail="/api/v1/community/works/"+work;
        assertEquals(404,bob.request("GET",candidates+"/"+work,null,Map.of()).statusCode());
        assertEquals(403,alice.request("POST",verify,"{\"reason\":\"synthetic reference\"}",headers("1")).statusCode());
        // A valid signed-in session without CSRF cannot mutate identity.
        var noCsrf=alice.client.send(HttpRequest.newBuilder(url(candidates)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"blocked\"}")).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(403,noCsrf.statusCode());
        var vr=owner.request("POST",verify,"{\"reason\":\"manually checked synthetic reference\"}",headers("1"));assertEquals(200,vr.statusCode(),vr.body());
        var ma=upload(alice,suffix+"a");var mb=upload(bob,suffix+"b");
        String la="/api/v1/library/materials/"+ma.path("id").asText()+"/work-link",lb="/api/v1/library/materials/"+mb.path("id").asText()+"/work-link",body="{\"workId\":\""+work+"\"}";
        assertEquals(404,bob.request("PUT",la,body,headers("1")).statusCode());
        var secondAlice=new Session();secondAlice.login("qa204-a-"+suffix);
        var sameHeaders=headers("1");
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->alice.request("PUT",la,body,sameHeaders));
            var second=pool.submit(()->secondAlice.request("PUT",la,body,sameHeaders));
            var one=first.get(30,java.util.concurrent.TimeUnit.SECONDS);var two=second.get(30,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(200,one.statusCode(),one.body());assertEquals(200,two.statusCode(),two.body());assertEquals(JSON.readTree(one.body()),JSON.readTree(two.body()));
        } finally {pool.shutdownNow();}
        assertEquals(412,alice.request("DELETE",la,null,headers("1")).statusCode());
        assertEquals(200,bob.request("PUT",lb,body,headers("1")).statusCode());
        var pa=publish(alice,ma);var pb=publish(bob,mb);
        assertEquals(work,pa.path("workId").asText());
        var read=bob.request("GET",detail,null,Map.of());assertEquals(200,read.statusCode(),read.body());assertEquals(2,JSON.readTree(read.body()).path("publications").size());
        assertFalse(read.body().contains("ownerAccountId"));assertFalse(read.body().contains("storage"));
        assertEquals(401,guest.request("GET",detail,null,Map.of()).statusCode());
        assertEquals(200,alice.request("DELETE",la,null,headers("2")).statusCode());
        assertEquals(2,JSON.readTree(bob.request("GET",detail,null,Map.of()).body()).path("publications").size());
        assertEquals(202,alice.request("DELETE","/api/v1/library/materials/"+ma.path("id").asText(),null,headers("3")).statusCode());
        var remaining=JSON.readTree(bob.request("GET",detail,null,Map.of()).body()).path("publications");assertEquals(1,remaining.size());assertEquals(pb.path("id").asText(),remaining.get(0).path("id").asText());
        String media="/api/v1/community/publications/"+pb.path("id").asText()+"/files/"+mb.path("files").get(0).path("id").asText()+"/content";
        assertEquals(200,bob.request("GET",media,null,Map.of()).statusCode());
        assertEquals(202,bob.request("DELETE","/api/v1/library/materials/"+mb.path("id").asText(),null,headers("2")).statusCode());
        System.out.println("QA204 TCP HTTP/PostgreSQL PASS: independent A/B/OWNER/guest sessions, CSRF, review, BOLA, shared snapshot, detach stability, deletion isolation and B bytes.");
        } finally {
            // Never leave the singleton OWNER or active publications for another test context.
            jdbc.sql("UPDATE library_publications SET state='WITHDRAWN',ended_at=now() WHERE owner_account_id IN (:a,:b) AND state='PUBLISHED'")
                .param("a",a).param("b",b).update();
            jdbc.sql("UPDATE accounts SET role='LEARNER' WHERE id=:id").param("id",o).update();
        }
    }
}
