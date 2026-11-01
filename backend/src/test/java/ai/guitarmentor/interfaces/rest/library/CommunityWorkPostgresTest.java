package ai.guitarmentor.interfaces.rest.library;

import static org.junit.jupiter.api.Assertions.*;
import ai.guitarmentor.infrastructure.persistence.LibraryObjectStore;
import ai.guitarmentor.infrastructure.security.CurrentActor;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
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

@SpringBootTest(properties={"guitar-mentor.library.enabled=true","guitar-mentor.library.cursor-secret=qa204-synthetic-cursor-secret-longer-than-32"})
@Transactional
@EnabledIfEnvironmentVariable(named="GUITAR_MENTOR_TEST_POSTGRES",matches="true")
class CommunityWorkPostgresTest {
    static final Path ROOT;
    static {try {ROOT=Files.createTempDirectory("qa204-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("guitar-mentor.library.storage-path",ROOT::toString);}
    @Autowired JdbcClient jdbc;
    @Autowired CommunityWorkController works;
    @Autowired LibraryPublicationController publications;
    @Autowired LibraryMaterialController materials;
    @Autowired LibraryObjectStore storage;
    UUID account(String role){UUID id=UUID.randomUUID();jdbc.sql("INSERT INTO accounts(id,role,status) VALUES(:id,:r,'ACTIVE')").param("id",id).param("r",role).update();return id;}
    MockHttpServletRequest req(UUID a,String role){var r=new MockHttpServletRequest();r.setAttribute("guitarMentorActor",new CurrentActor(a,null,role));return r;}
    record Material(UUID id,UUID asset,String key){}
    Material material(UUID a) throws Exception {
        UUID id=UUID.randomUUID(),asset=UUID.randomUUID();var stored=storage.put(new ByteArrayInputStream("%PDF-1.7 synthetic #204".getBytes()),1024);
        jdbc.sql("INSERT INTO library_materials(id,owner_account_id,title,material_type,state) VALUES(:id,:a,'Synthetic arrangement','COMPOSITION','READY')").param("id",id).param("a",a).update();
        jdbc.sql("INSERT INTO library_assets(id,material_id,object_key,original_name,format,byte_size,sha256) VALUES(:id,:m,:k,'synthetic.pdf','PDF',:s,:sha)")
            .param("id",asset).param("m",id).param("k",stored.key()).param("s",stored.length()).param("sha",stored.sha256()).update();
        return new Material(id,asset,stored.key());
    }
    CommunityWorkController.Work candidate(MockHttpServletRequest r,String name){return works.candidate(r,UUID.randomUUID().toString(),new CommunityWorkController.Identity(name,"Synthetic artist")).getBody();}
    LibraryPublicationController.Publication publish(MockHttpServletRequest r,Material m,long rev){return publications.publish(r,m.id(),"\""+rev+"\"",UUID.randomUUID().toString(),new LibraryPublicationController.PublishRequest(List.of(new LibraryPublicationController.AssetRight(m.asset(),"CREATED","OWN_WORK","Synthetic original test score rights.")))).getBody();}
    void error(String code,org.junit.jupiter.api.function.Executable f){assertEquals(code,assertThrows(RuntimeException.class,f).getMessage());}
    @Test void twoAuthorsSnapshotCorrectionDeleteAndMissingBytes() throws Exception {
        UUID a=account("LEARNER"),b=account("LEARNER"),o=account("OWNER"),c=account("LEARNER");
        var ar=req(a,"LEARNER");var br=req(b,"LEARNER");var owner=req(o,"OWNER");var reader=req(c,"LEARNER");
        var w=candidate(ar,"Synthetic shared "+UUID.randomUUID());var duplicate=candidate(br,w.title());assertNotEquals(w.id(),duplicate.id());
        error("RESOURCE_NOT_FOUND",()->works.candidateDetail(br,w.id()));error("FORBIDDEN",()->works.verify(ar,w.id(),"\"1\"",new CommunityWorkController.Reason("verified synthetic")));
        var ma=material(a);var mb=material(b);
        error("WORK_NOT_VERIFIED",()->works.attach(ar,ma.id(),"\"1\"",UUID.randomUUID().toString(),new CommunityWorkController.LinkRequest(w.id())));
        works.verify(owner,w.id(),"\"1\"",new CommunityWorkController.Reason("manual synthetic reference"));
        String key=UUID.randomUUID().toString();var request=new CommunityWorkController.LinkRequest(w.id());
        error("RESOURCE_NOT_FOUND",()->works.attach(br,ma.id(),"\"1\"",UUID.randomUUID().toString(),request));
        assertEquals(2,works.attach(ar,ma.id(),"\"1\"",key,request).getBody().revision());
        assertEquals(2,works.attach(ar,ma.id(),"\"1\"",key,request).getBody().revision());
        error("IDEMPOTENCY_KEY_REUSED",()->works.detach(ar,ma.id(),"\"1\"",key));
        works.attach(br,mb.id(),"\"1\"",UUID.randomUUID().toString(),request);
        error("RESOURCE_NOT_FOUND",()->works.detail(reader,w.id()));
        var pa=publish(ar,ma,2);var pb=publish(br,mb,2);assertEquals(w.id(),pa.workId());assertEquals(w.id(),pb.workId());
        assertEquals(2,works.detail(reader,w.id()).getBody().publications().size());
        works.detach(ar,ma.id(),"\"2\"",UUID.randomUUID().toString());
        assertEquals(w.id(),publications.publishedDetail(reader,pa.id()).getBody().workId());
        assertEquals(2,works.detail(reader,w.id()).getBody().publications().size());
        materials.delete(ar,ma.id(),"\"3\"");
        assertEquals(List.of(pb.id()),works.detail(reader,w.id()).getBody().publications().stream().map(LibraryPublicationController.Publication::id).toList());
        assertTrue(Files.exists(storage.file(mb.key())));
        Files.delete(storage.file(mb.key()));error("RESOURCE_NOT_FOUND",()->works.detail(reader,w.id()));
        assertTrue(works.search(reader,w.title(),20,null).getBody().items().isEmpty());
        assertEquals(1,jdbc.sql("SELECT count(*) FROM community_works WHERE id=:id").param("id",w.id()).query(Integer.class).single());
    }
    @Test void revisionsAndSplitKeepOldPublications() throws Exception {
        UUID a=account("LEARNER"),o=account("OWNER");var ar=req(a,"LEARNER");var owner=req(o,"OWNER");
        var w=candidate(ar,"Split source");works.verify(owner,w.id(),"\"1\"",new CommunityWorkController.Reason("manual reference"));
        var m=material(a);works.attach(ar,m.id(),"\"1\"",UUID.randomUUID().toString(),new CommunityWorkController.LinkRequest(w.id()));var p=publish(ar,m,2);
        error("PRECONDITION_REQUIRED",()->works.detach(ar,m.id(),null,UUID.randomUUID().toString()));
        error("REVISION_CONFLICT",()->works.detach(ar,m.id(),"\"1\"",UUID.randomUUID().toString()));
        var split=new CommunityWorkController.SplitRequest("Corrected identity","Synthetic artist","manual correction",List.of(new CommunityWorkController.SplitItem(m.id(),2)));
        var plan=works.dryRun(owner,w.id(),"\"2\"",split).getBody();
        var target=works.split(owner,w.id(),"\"2\"",plan.planId(),split).getBody();
        assertEquals(target.id(),works.split(owner,w.id(),"\"2\"",plan.planId(),split).getBody().id());
        assertEquals(target.id(),works.link(ar,m.id()).getBody().workId());
        assertEquals(w.id(),publications.publishedDetail(ar,p.id()).getBody().workId());
        assertEquals(3,works.link(ar,m.id()).getBody().revision());
        error("RESOURCE_NOT_FOUND",()->works.detail(ar,target.id()));
    }
    @Test void candidateRetryModerationAndHiddenPublication() throws Exception {
        UUID a=account("LEARNER"),o=account("OWNER");var ar=req(a,"LEARNER");var owner=req(o,"OWNER");
        String key=UUID.randomUUID().toString();var input=new CommunityWorkController.Identity("Candidate retry "+UUID.randomUUID(),"Synthetic");
        var w=works.candidate(ar,key,input).getBody();assertEquals(w.id(),works.candidate(ar,key,input).getBody().id());
        error("IDEMPOTENCY_KEY_REUSED",()->works.candidate(ar,key,new CommunityWorkController.Identity("Different","Synthetic")));
        works.verify(owner,w.id(),"\"1\"",new CommunityWorkController.Reason("manual source reference"));
        error("REVISION_CONFLICT",()->works.metadata(owner,w.id(),"\"1\"",new CommunityWorkController.Metadata("Corrected","Synthetic","manual source")));
        var m=material(a);works.attach(ar,m.id(),"\"1\"",UUID.randomUUID().toString(),new CommunityWorkController.LinkRequest(w.id()));var p=publish(ar,m,2);
        assertEquals(1,works.search(ar,w.title(),1,null).getBody().items().size());
        publications.hide(owner,p.id(),"\"1\"",new LibraryPublicationController.HideRequest("synthetic moderation"));
        error("RESOURCE_NOT_FOUND",()->works.detail(ar,w.id()));
        assertTrue(works.search(ar,w.title(),1,null).getBody().items().isEmpty());
        works.retire(owner,w.id(),"\"2\"",new CommunityWorkController.Reason("manual retirement"));
        works.detach(ar,m.id(),"\"2\"",UUID.randomUUID().toString());
        error("WORK_NOT_VERIFIED",()->works.attach(ar,m.id(),"\"3\"",UUID.randomUUID().toString(),new CommunityWorkController.LinkRequest(w.id())));
        assertEquals(w.id(),jdbc.sql("SELECT work_id FROM library_publications WHERE id=:id").param("id",p.id()).query(UUID.class).single());
    }
}
