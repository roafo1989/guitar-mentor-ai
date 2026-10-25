package ai.guitarmentor.infrastructure.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.guitarmentor.infrastructure.persistence.LibraryDeletionWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

/**
 * FT-011-05 / #164 — one release-candidate journey across authenticated HTTP,
 * PostgreSQL and private original file storage, with two distinct learner accounts.
 * It does not substitute for the user's Windows HTTPS/Vue browser acceptance.
 */
@SpringBootTest(properties={
    "guitar-mentor.security.enabled=true",
    "guitar-mentor.library.enabled=true",
    "guitar-mentor.library.cursor-secret=ci-only-library-secret-longer-than-thirty-two-bytes"
})
@AutoConfigureMockMvc
@Import(SecurityHttpIntegrationTest.FakeOidcRegistration.class)
@EnabledIfEnvironmentVariable(named="GUITAR_MENTOR_TEST_POSTGRES",matches="true")
@Transactional
class LibraryAcceptanceHttpPostgresTest {
    private static final Path ROOT;
    private static final String BASE="/api/v1/library/materials";
    private static final ObjectMapper JSON=new ObjectMapper();
    static {
        try {ROOT=Files.createTempDirectory("gm-164-acceptance-");}
        catch(java.io.IOException ex) {throw new ExceptionInInitializerError(ex);}
    }
    @DynamicPropertySource static void disk(DynamicPropertyRegistry registry) {
        registry.add("guitar-mentor.library.storage-path",ROOT::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @MockitoBean ActorRepository actors;
    @MockitoBean InvitationService invitations;

    private UUID account(String sub) {
        UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO accounts(id,role,status) VALUES(:id,'LEARNER','ACTIVE')")
            .param("id",id).update();
        when(actors.findActive("https://issuer.example",sub))
            .thenReturn(Optional.of(new CurrentActor(id,null,"LEARNER")));
        when(actors.localVersion(id)).thenReturn(Optional.of(0L));
        return id;
    }
    private org.springframework.test.web.servlet.request.RequestPostProcessor login(String sub) {
        return oidcLogin().idToken(t -> t.claim("iss","https://issuer.example").subject(sub));
    }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private String create(String sub, byte[] bytes, String name, String type) throws Exception {
        var metadata=new MockMultipartFile("metadata","","application/json",
            JSON.writeValueAsBytes(Map.of("title",name,"type",type,"author","Tester")));
        var file=new MockMultipartFile("files",name+".pdf","application/pdf",bytes);
        return mvc.perform(MockMvcRequestBuilders.multipart(BASE)
                .file(metadata).file(file).with(login(sub)).with(csrf())
                .sessionAttr("guitarMentorAccessVersion",0L)
                .header("Idempotency-Key",UUID.randomUUID().toString()))
            .andExpect(status().isCreated())
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.visibility").value("PRIVATE"))
            .andExpect(jsonPath("$.files[0].sha256").value(sha(bytes)))
            .andReturn().getResponse().getContentAsString();
    }
    private org.springframework.test.web.servlet.ResultActions get(String sub,String path) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(path).with(login(sub))
            .sessionAttr("guitarMentorAccessVersion",0L));
    }

    @Test void ownedJourneyAndForeignActorCannotReadChangeOrDelete() throws Exception {
        UUID alice=account("ft164-alice");
        account("ft164-bob");
        byte[] original="%PDF-1.7\nFT-011-05 unique original — do not share\n".getBytes(StandardCharsets.UTF_8);
        JsonNode created=JSON.readTree(create("ft164-alice",original,"FT164 Disposable","ETUDE"));
        String id=created.get("id").asText();
        String asset=created.path("files").get(0).get("id").asText();
        String item=BASE+"/"+id;
        String content=item+"/files/"+asset+"/content";

        get("ft164-alice",BASE)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].id").value(id));
        get("ft164-bob",BASE)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(0));
        get("ft164-alice",item)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("FT164 Disposable"));
        get("ft164-bob",item).andExpect(status().isNotFound());
        get("ft164-alice",content)
            .andExpect(status().isOk())
            .andExpect(result -> assertArrayEquals(original,
                result.getResponse().getContentAsByteArray()));
        get("ft164-bob",content).andExpect(status().isNotFound());
        mvc.perform(MockMvcRequestBuilders.head(content).with(login("ft164-alice"))
            .sessionAttr("guitarMentorAccessVersion",0L))
            .andExpect(status().isOk())
            .andExpect(result -> assertEquals(0,result.getResponse().getContentAsByteArray().length));
        get("ft164-alice",content)
            .andExpect(result -> assertEquals(sha(original),
                sha(result.getResponse().getContentAsByteArray())));

        mvc.perform(MockMvcRequestBuilders.patch(item).with(login("ft164-bob")).with(csrf())
            .sessionAttr("guitarMentorAccessVersion",0L)
            .header("If-Match","\"1\"").contentType("application/json")
            .content("{\"title\":\"foreign edit\"}"))
            .andExpect(status().isNotFound());
        mvc.perform(MockMvcRequestBuilders.delete(item).with(login("ft164-bob")).with(csrf())
            .sessionAttr("guitarMentorAccessVersion",0L)
            .header("If-Match","\"1\""))
            .andExpect(status().isNotFound());

        mvc.perform(MockMvcRequestBuilders.patch(item).with(login("ft164-alice")).with(csrf())
            .sessionAttr("guitarMentorAccessVersion",0L)
            .header("If-Match","\"1\"").contentType("application/json")
            .content("{\"title\":\"Edited disposable\",\"type\":\"COMPOSITION\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Edited disposable"))
            .andExpect(jsonPath("$.revision").value(2));
        mvc.perform(MockMvcRequestBuilders.patch(item).with(login("ft164-alice")).with(csrf())
            .sessionAttr("guitarMentorAccessVersion",0L)
            .header("If-Match","\"1\"").contentType("application/json")
            .content("{\"title\":\"stale overwrite\"}"))
            .andExpect(status().isPreconditionFailed());
        // No CSRF token: destructive changes must fail without modifying the card.
        mvc.perform(MockMvcRequestBuilders.delete(item).with(login("ft164-alice"))
            .sessionAttr("guitarMentorAccessVersion",0L)
            .header("If-Match","\"2\""))
            .andExpect(status().isForbidden());
        get("ft164-alice",item).andExpect(jsonPath("$.title").value("Edited disposable"));

        mvc.perform(MockMvcRequestBuilders.delete(item).with(login("ft164-alice")).with(csrf())
            .sessionAttr("guitarMentorAccessVersion",0L)
            .header("If-Match","\"2\""))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.state").value("DELETING"));
        get("ft164-alice",item).andExpect(status().isNotFound());
        get("ft164-alice",content).andExpect(status().isNotFound());
        get("ft164-bob",content).andExpect(status().isNotFound());
        mvc.perform(MockMvcRequestBuilders.delete(item).with(login("ft164-alice")).with(csrf())
            .sessionAttr("guitarMentorAccessVersion",0L)
            .header("If-Match","\"2\""))
            .andExpect(status().isAccepted());
        assertEquals(1L,jdbc.sql("SELECT count(*) FROM library_deletion_jobs WHERE material_id=:id")
            .param("id",UUID.fromString(id)).query(Long.class).single());
        assertEquals("DELETING",jdbc.sql("SELECT state FROM library_materials WHERE id=:id")
            .param("id",UUID.fromString(id)).query(String.class).single());
        assertEquals(1L,jdbc.sql("SELECT charged_files FROM library_account_usage WHERE account_id=:id")
            .param("id",alice).query(Long.class).single());
    }

    @Test void anonymousRequestsAndUnsupportedPdfAreRejected() throws Exception {
        account("ft164-other");
        get("ft164-other",BASE).andExpect(status().isOk());
        mvc.perform(MockMvcRequestBuilders.get(BASE)).andExpect(status().isUnauthorized());
        var metadata=new MockMultipartFile("metadata","","application/json",
            JSON.writeValueAsBytes(Map.of("title","Invalid","type","COMPOSITION")));
        var fakePdf=new MockMultipartFile("files","fake.pdf","application/pdf",new byte[]{1,2,3});
        mvc.perform(MockMvcRequestBuilders.multipart(BASE)
            .file(metadata).file(fakePdf).with(login("ft164-other")).with(csrf())
            .sessionAttr("guitarMentorAccessVersion",0L)
            .header("Idempotency-Key",UUID.randomUUID().toString()))
            .andExpect(status().isBadRequest());
    }
}
