package ai.guitarmentor.interfaces.rest.library;

import ai.guitarmentor.infrastructure.persistence.LibraryObjectStore;
import ai.guitarmentor.infrastructure.security.CurrentActor;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

/** #165 real PostgreSQL, FS bytes and independent accounts: no publication by upload alone. */
@SpringBootTest(properties={
    "guitar-mentor.library.enabled=true",
    "guitar-mentor.library.cursor-secret=tests-only-stable-secret-must-be-longer-than-32"
})
@Transactional
@EnabledIfEnvironmentVariable(named="GUITAR_MENTOR_TEST_POSTGRES",matches="true")
class LibraryPublicationPostgresTest {
    private static final Path ROOT;
    static {
        try {ROOT=Files.createTempDirectory("gm-publication-tests-");}
        catch(Exception e) {throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) {
        registry.add("guitar-mentor.library.storage-path",()->ROOT.toString());
    }
    @Autowired JdbcClient jdbc;
    @Autowired LibraryObjectStore storage;
    @Autowired LibraryPublicationController publications;
    @Autowired LibraryMaterialController materials;

    private UUID newAccount(String role) {
        UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO accounts(id,role,status) VALUES(:id,:role,'ACTIVE')")
            .param("id",id).param("role",role).update();
        return id;
    }
    private MockHttpServletRequest req(UUID owner,String role) {
        var request=new MockHttpServletRequest();
        request.setAttribute("guitarMentorActor",new CurrentActor(owner,null,role));
        return request;
    }
    private record Fixture(UUID material,UUID asset,byte[] bytes) {}
    private Fixture material(UUID owner,String title) throws Exception {
        UUID material=UUID.randomUUID(),file=UUID.randomUUID();
        byte[] bytes=("%PDF-1.7\\n"+title+"\\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var stored=storage.put(new ByteArrayInputStream(bytes),20*1024*1024);
        jdbc.sql("""
            INSERT INTO library_materials(id,owner_account_id,title,material_type,state,revision)
            VALUES(:id,:owner,:title,'COMPOSITION','READY',1)
            """).param("id",material).param("owner",owner).param("title",title).update();
        jdbc.sql("""
            INSERT INTO library_assets(id,material_id,object_key,original_name,format,byte_size,sha256)
            VALUES(:id,:material,:key,'score.pdf','PDF',:bytes,:sha)
            """).param("id",file).param("material",material).param("key",stored.key())
                .param("bytes",stored.length()).param("sha",stored.sha256()).update();
        return new Fixture(material,file,bytes);
    }
    private LibraryPublicationController.PublishRequest rights(UUID asset) {
        return new LibraryPublicationController.PublishRequest(List.of(
            new LibraryPublicationController.AssetRight(asset,"CREATED","OWN_WORK",
                "I composed and arranged this original score.")));
    }
    private String code(Executable operation) {
        return assertThrows(RuntimeException.class,operation::run).getMessage();
    }
    interface Executable {void run() throws Exception;}

    @Test void explicitPublishOnlyAndWithdrawImmediatelyStopsForeignDownload() throws Exception {
        UUID alice=newAccount("LEARNER"),bob=newAccount("LEARNER");
        var f=material(alice,"Private draft");
        assertEquals(0L,jdbc.sql("SELECT count(*) FROM library_publications WHERE material_id=:id")
            .param("id",f.material()).query(Long.class).single());
        assertEquals("LIBRARY_MATERIAL_NOT_FOUND",
            code(()->publications.publish(req(bob,"LEARNER"),f.material(),"\"1\"",rights(f.asset()))));
        assertEquals("PUBLICATION_INVALID_RIGHTS",
            code(()->publications.publish(req(alice,"LEARNER"),f.material(),"\"1\"",
                new LibraryPublicationController.PublishRequest(List.of()))));
        var response=publications.publish(req(alice,"LEARNER"),f.material(),"\"1\"",rights(f.asset()));
        assertEquals(201,response.getStatusCode().value());
        UUID pub=response.getBody().id();
        assertEquals("PUBLISHED",response.getBody().state());
        assertEquals("Private draft",publications.publishedDetail(req(bob,"LEARNER"),pub)
            .getBody().title());
        assertNull(publications.publishedDetail(req(bob,"LEARNER"),pub)
            .getBody().assets().getFirst().rightsEvidence(),
            "Permission/legal evidence is private to the owner, not published as PII");
        var content=publications.publishedContent(req(bob,"LEARNER"),pub,f.asset());
        assertEquals(200,content.getStatusCode().value());
        var file=(org.springframework.core.io.FileSystemResource)content.getBody();
        assertArrayEquals(f.bytes(),Files.readAllBytes(file.getFile().toPath()));
        assertEquals("no-store",content.getHeaders().getCacheControl());
        assertEquals("PUBLICATION_ALREADY_ACTIVE",code(()->publications.publish(
            req(alice,"LEARNER"),f.material(),"\"1\"",rights(f.asset()))));
        assertEquals(204,publications.withdraw(req(alice,"LEARNER"),f.material(),pub)
            .getStatusCode().value());
        assertEquals("PUBLICATION_NOT_FOUND",
            code(()->publications.publishedDetail(req(bob,"LEARNER"),pub)));
        assertEquals("PUBLICATION_NOT_FOUND",
            code(()->publications.publishedContent(req(bob,"LEARNER"),pub,f.asset())));
        assertEquals("PUBLICATION_NOT_FOUND",
            code(()->publications.withdraw(req(alice,"LEARNER"),f.material(),pub)));
        var history=publications.ownerHistory(req(alice,"LEARNER"),f.material()).getBody();
        assertEquals(1,history.size());
        assertEquals("WITHDRAWN",history.getFirst().state());
        assertEquals("LIBRARY_MATERIAL_NOT_FOUND",
            code(()->publications.ownerHistory(req(bob,"LEARNER"),f.material())));
        assertEquals(2L,jdbc.sql("""
            SELECT count(*) FROM library_publication_events WHERE publication_id=:id
            """).param("id",pub).query(Long.class).single());
    }

    @Test void snapshotDoesNotChangeOnEditAndNextPublishGetsNewVersion() throws Exception {
        UUID alice=newAccount("LEARNER"),bob=newAccount("LEARNER");
        var f=material(alice,"First title");
        UUID v1=publications.publish(req(alice,"LEARNER"),f.material(),"\"1\"",rights(f.asset()))
            .getBody().id();
        var changed=materials.edit(req(alice,"LEARNER"),f.material(),"\"1\"",
            Map.of("title","Changed private draft"));
        assertEquals(200,changed.getStatusCode().value());
        assertEquals("First title",publications.publishedDetail(req(bob,"LEARNER"),v1)
            .getBody().title());
        assertEquals("LIBRARY_REVISION_CONFLICT",
            code(()->publications.publish(req(alice,"LEARNER"),f.material(),"\"1\"",rights(f.asset()))));
        publications.withdraw(req(alice,"LEARNER"),f.material(),v1);
        var v2=publications.publish(req(alice,"LEARNER"),f.material(),"\"2\"",rights(f.asset()))
            .getBody();
        assertEquals(2L,v2.version());
        assertEquals("Changed private draft",v2.title());
        assertEquals("PUBLICATION_NOT_FOUND",code(()->publications.publishedDetail(req(bob,"LEARNER"),v1)));
    }

    @Test void moderatorHideWithAuditAndOwnerDeleteRevokesInSameTransaction() throws Exception {
        UUID alice=newAccount("LEARNER"),bob=newAccount("LEARNER"),admin=newAccount("OWNER");
        var f=material(alice,"Moderation test");
        UUID pub=publications.publish(req(alice,"LEARNER"),f.material(),"\"1\"",rights(f.asset()))
            .getBody().id();
        assertEquals("PUBLICATION_MODERATION_FORBIDDEN",
            code(()->publications.hide(req(bob,"LEARNER"),pub,
                new LibraryPublicationController.HideRequest("Documented rights concern"))));
        assertEquals(204,publications.hide(req(admin,"OWNER"),pub,
            new LibraryPublicationController.HideRequest("Documented rights concern")).getStatusCode().value());
        assertEquals("PUBLICATION_NOT_FOUND",code(()->publications.publishedContent(
            req(bob,"LEARNER"),pub,f.asset())));
        assertEquals("HIDDEN",publications.ownerHistory(req(alice,"LEARNER"),f.material())
            .getBody().getFirst().state());
        assertEquals("HIDE",jdbc.sql("""
            SELECT action FROM library_publication_events
            WHERE publication_id=:id ORDER BY created_at DESC LIMIT 1
            """).param("id",pub).query(String.class).single());
        var other=material(alice,"To delete");
        UUID live=publications.publish(req(alice,"LEARNER"),other.material(),"\"1\"",
            rights(other.asset())).getBody().id();
        assertEquals(202,materials.delete(req(alice,"LEARNER"),other.material(),"\"1\"")
            .getStatusCode().value());
        assertEquals("PUBLICATION_NOT_FOUND",
            code(()->publications.publishedContent(req(bob,"LEARNER"),live,other.asset())));
        assertEquals("WITHDRAWN",publications.ownerHistory(req(alice,"LEARNER"),other.material())
            .getBody().getFirst().state());
        assertEquals("DELETE_WITHDRAW",jdbc.sql("""
            SELECT action FROM library_publication_events
            WHERE publication_id=:id ORDER BY created_at DESC LIMIT 1
            """).param("id",live).query(String.class).single());
    }
}
