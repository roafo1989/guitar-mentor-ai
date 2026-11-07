package ai.guitarmentor.infrastructure.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties={"guitar-mentor.security.enabled=true","guitar-mentor.library.enabled=true",
    "guitar-mentor.library.cursor-secret=ci196-only-library-secret-longer-than-thirty-two-bytes"})
@AutoConfigureMockMvc
@Import(SecurityHttpIntegrationTest.FakeOidcRegistration.class)
@EnabledIfEnvironmentVariable(named="GUITAR_MENTOR_TEST_POSTGRES",matches="true")
@Transactional
class MusicCatalogOriginalHttpPostgresTest {
    static final Path ROOT;
    static {try {ROOT=Files.createTempDirectory("gm196-http-");} catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void storage(DynamicPropertyRegistry r) {r.add("guitar-mentor.library.storage-path",()->ROOT.toString());}
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @MockitoBean ActorRepository actors;
    @MockitoBean InvitationService invitations;
    final com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();
    UUID account(String subject) {
        UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO accounts(id,role,status) VALUES(:id,'LEARNER','ACTIVE')").param("id",id).update();
        when(actors.findActive("https://issuer.example",subject)).thenReturn(Optional.of(new CurrentActor(id,null,"LEARNER")));
        when(actors.localVersion(id)).thenReturn(Optional.of(0L));
        seed(jdbc,id);
        return id;
    }
    static void seed(JdbcClient jdbc,UUID owner) {
        jdbc.sql("INSERT INTO music_catalog_works(owner_account_id,source_work_id,title,identity_status,grouping_method) VALUES(:o,'w196','Synthetic 196','CANDIDATE','test')").param("o",owner).update();
        jdbc.sql("INSERT INTO music_catalog_arrangements(owner_account_id,source_arrangement_id,source_work_id,name,identity_status,parser_status) VALUES(:o,'a196','w196','Guitar','UNRESOLVED','METADATA_ONLY')").param("o",owner).update();
        for(String file:List.of("pdf196","gp196","empty196"))
            jdbc.sql("INSERT INTO music_catalog_source_files(owner_account_id,source_file_id,source_arrangement_id,relative_path,original_filename,source_group,extension,size_bytes,rights_status) VALUES(:o,:f,'a196',:f,:f,'synthetic','.pdf',0,'course_private')")
                .param("o",owner).param("f",file).update();
    }
    org.springframework.test.web.servlet.request.RequestPostProcessor identity(String subject) {
        return oidcLogin().idToken(t->t.claim("iss","https://issuer.example").subject(subject));
    }
    com.fasterxml.jackson.databind.JsonNode upload(String subject,String name,byte[] bytes) throws Exception {
        String body=mvc.perform(multipart("/api/v1/library/materials")
            .file(new MockMultipartFile("metadata","","application/json","{\"title\":\"Synthetic original\",\"type\":\"COMPOSITION\"}".getBytes()))
            .file(new MockMultipartFile("files",name,"application/octet-stream",bytes))
            .with(identity(subject)).with(csrf()).sessionAttr("guitarMentorAccessVersion",0L)
            .header("Idempotency-Key",UUID.randomUUID().toString())).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }
    String link(com.fasterxml.jackson.databind.JsonNode card) {return "{\"materialId\":\""+card.path("id").asText()+"\",\"assetId\":\""+card.path("files").get(0).path("id").asText()+"\"}";}
    String uri(com.fasterxml.jackson.databind.JsonNode card) {return card.path("files").get(0).path("downloadUrl").asText();}
    String endpoint(String file) {return "/api/v1/music-catalog/files/"+file+"/original";}
    com.fasterxml.jackson.databind.JsonNode detail(String subject) throws Exception {
        String body=mvc.perform(get("/api/v1/music-catalog/works/w196").with(identity(subject)).sessionAttr("guitarMentorAccessVersion",0L))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("relative_path"));assertFalse(body.contains("object_key"));
        return json.readTree(body).path("arrangements").get(0).path("files");
    }
    com.fasterxml.jackson.databind.JsonNode source(com.fasterxml.jackson.databind.JsonNode files,String id) {
        for(var f:files)if(f.path("id").asText().equals(id))return f;
        throw new AssertionError(id);
    }
    @Test void explicitPdfGpLinksOwnerIsolationCsrfAndDeletion() throws Exception {
        UUID alice=account("cat196-alice"),bob=account("cat196-bob");
        jdbc.sql("INSERT INTO music_catalog_source_files(owner_account_id,source_file_id,source_arrangement_id,relative_path,original_filename,source_group,extension,size_bytes,rights_status) VALUES(:o,'alice-only','a196','alice-only','only.pdf','synthetic','.pdf',0,'UNKNOWN')").param("o",alice).update();
        byte[] pdf="%PDF-1.7\nsynthetic private original\n".getBytes();
        var a=upload("cat196-alice","original.pdf",pdf);
        var b=upload("cat196-bob","foreign.pdf",pdf);
        byte[] gp=Files.readAllBytes(Path.of("../fixtures/research/catalog-169/generated/gm169-synthetic.gp5"));
        var guitar=upload("cat196-alice","original.gp5",gp);
        assertEquals("UNAVAILABLE",source(detail("cat196-alice"),"pdf196").path("originalAvailability").asText());
        assertTrue(source(detail("cat196-alice"),"pdf196").path("original").isNull());
        mvc.perform(put(endpoint("pdf196")).with(identity("cat196-alice")).sessionAttr("guitarMentorAccessVersion",0L).contentType("application/json").content(link(a))).andExpect(status().isForbidden());
        mvc.perform(put(endpoint("pdf196")).with(csrf()).contentType("application/json").content(link(a))).andExpect(status().isUnauthorized());
        mvc.perform(put(endpoint("pdf196")).with(identity("cat196-alice")).with(csrf()).sessionAttr("guitarMentorAccessVersion",0L).contentType("application/json").content(link(b))).andExpect(status().isNotFound());
        // Same source IDs exist for Bob: both sides must be scoped independently.
        mvc.perform(put(endpoint("pdf196")).with(identity("cat196-bob")).with(csrf()).sessionAttr("guitarMentorAccessVersion",0L).contentType("application/json").content(link(a))).andExpect(status().isNotFound());
        mvc.perform(put(endpoint("missing")).with(identity("cat196-alice")).with(csrf()).sessionAttr("guitarMentorAccessVersion",0L).contentType("application/json").content(link(a))).andExpect(status().isNotFound());
        mvc.perform(put(endpoint("alice-only")).with(identity("cat196-bob")).with(csrf()).sessionAttr("guitarMentorAccessVersion",0L).contentType("application/json").content(link(b))).andExpect(status().isNotFound());
        String mixed=link(a).replace(a.path("files").get(0).path("id").asText(),b.path("files").get(0).path("id").asText());
        mvc.perform(put(endpoint("pdf196")).with(identity("cat196-alice")).with(csrf()).sessionAttr("guitarMentorAccessVersion",0L).contentType("application/json").content(mixed)).andExpect(status().isNotFound());
        for(String invalid:List.of("{\"path\":\"D:\\\\secret.pdf\"}",link(a).replace("}",",\"url\":\"https://example.invalid/file\"}"),"{\"materialId\":null,\"assetId\":123}"))
            mvc.perform(put(endpoint("pdf196")).with(identity("cat196-alice")).with(csrf()).sessionAttr("guitarMentorAccessVersion",0L).contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
        for(var pair:Map.of("pdf196",a,"gp196",guitar).entrySet()) {
            for(int retry=0;retry<2;retry++)
                mvc.perform(put(endpoint(pair.getKey())).with(identity("cat196-alice")).with(csrf()).sessionAttr("guitarMentorAccessVersion",0L).contentType("application/json").content(link(pair.getValue())))
                    .andExpect(status().isNoContent()).andExpect(header().string("Cache-Control","no-store"));
            var linked=source(detail("cat196-alice"),pair.getKey());
            assertEquals("AVAILABLE",linked.path("originalAvailability").asText());
            assertEquals(pair.getValue().path("id").asText(),linked.path("original").path("materialId").asText());
            mvc.perform(get(uri(pair.getValue())).with(identity("cat196-alice")).sessionAttr("guitarMentorAccessVersion",0L))
                .andExpect(status().isOk()).andExpect(result->assertArrayEquals(pair.getKey().equals("pdf196")?pdf:gp,result.getResponse().getContentAsByteArray()));
            mvc.perform(get(uri(pair.getValue())).with(identity("cat196-bob")).sessionAttr("guitarMentorAccessVersion",0L)).andExpect(status().isNotFound());
            mvc.perform(get(uri(pair.getValue()))).andExpect(status().isUnauthorized());
        }
        assertEquals("UNAVAILABLE",source(detail("cat196-alice"),"empty196").path("originalAvailability").asText());
        assertEquals("UNAVAILABLE",source(detail("cat196-bob"),"pdf196").path("originalAvailability").asText());
        // Rebinding is explicit; no double quota charge and no change to rights/visibility.
        var usage=jdbc.sql("SELECT charged_files FROM library_account_usage WHERE account_id=:o").param("o",alice).query(Integer.class).single();
        assertEquals(2,usage);
        assertEquals("course_private",source(detail("cat196-alice"),"pdf196").path("rightsStatus").asText());
        jdbc.sql("UPDATE library_materials SET state='DELETING' WHERE id=:id").param("id",UUID.fromString(a.path("id").asText())).update();
        assertEquals("UNAVAILABLE",source(detail("cat196-alice"),"pdf196").path("originalAvailability").asText());
        mvc.perform(get(uri(a)).with(identity("cat196-alice")).sessionAttr("guitarMentorAccessVersion",0L)).andExpect(status().isNotFound());
        mvc.perform(put(endpoint("pdf196")).with(identity("cat196-alice")).with(csrf()).sessionAttr("guitarMentorAccessVersion",0L).contentType("application/json").content(link(a))).andExpect(status().isNotFound());
        jdbc.sql("UPDATE library_assets SET processing_state='REJECTED' WHERE id=:id").param("id",UUID.fromString(guitar.path("files").get(0).path("id").asText())).update();
        assertEquals("UNAVAILABLE",source(detail("cat196-alice"),"gp196").path("originalAvailability").asText());
        mvc.perform(put(endpoint("gp196")).with(identity("cat196-alice")).with(csrf()).sessionAttr("guitarMentorAccessVersion",0L).contentType("application/json").content(link(guitar))).andExpect(status().isNotFound());
        jdbc.sql("UPDATE library_assets SET processing_state='READY',role='DERIVED_SCORE_PDF',format='PDF',media_type='application/pdf' WHERE id=:id").param("id",UUID.fromString(guitar.path("files").get(0).path("id").asText())).update();
        mvc.perform(put(endpoint("gp196")).with(identity("cat196-alice")).with(csrf()).sessionAttr("guitarMentorAccessVersion",0L).contentType("application/json").content(link(guitar))).andExpect(status().isNotFound());
        // Defense in depth: even a corrupt database link cannot disclose foreign references.
        jdbc.sql("UPDATE music_catalog_source_files SET original_asset_id=:id WHERE owner_account_id=:o AND source_file_id='empty196'")
            .param("id",UUID.fromString(b.path("files").get(0).path("id").asText())).param("o",alice).update();
        var hidden=source(detail("cat196-alice"),"empty196");
        assertEquals("UNAVAILABLE",hidden.path("originalAvailability").asText());assertTrue(hidden.path("original").isNull());
    }
}
