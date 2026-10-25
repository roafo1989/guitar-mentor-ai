package ai.guitarmentor.infrastructure.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
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

/** Authenticated real HTTP chain with CSRF and CurrentActor + publication access. */
@SpringBootTest(properties={
    "guitar-mentor.security.enabled=true",
    "guitar-mentor.library.enabled=true",
    "guitar-mentor.library.cursor-secret=ci-only-publication-cursor-secret-long-enough"
})
@AutoConfigureMockMvc
@Import(SecurityHttpIntegrationTest.FakeOidcRegistration.class)
@EnabledIfEnvironmentVariable(named="GUITAR_MENTOR_TEST_POSTGRES",matches="true")
@Transactional
class LibraryPublicationHttpPostgresTest {
    private static final Path ROOT;
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String BASE="/api/v1/library/materials";
    static {
        try { ROOT=Files.createTempDirectory("gm-publication-http-"); }
        catch(java.io.IOException e){throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
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
        return oidcLogin().idToken(t->t.claim("iss","https://issuer.example").subject(sub));
    }
    @Test void publisherAndReaderSeparatedAndWithdrawRevokesDirectUrl() throws Exception {
        account("pub-a");account("pub-b");
        byte[] bytes="%PDF-1.7\nOwn score data".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var metadata=new MockMultipartFile("metadata","","application/json",
            JSON.writeValueAsBytes(Map.of("title","A's new score","type","COMPOSITION")));
        var file=new MockMultipartFile("files","score.pdf","application/pdf",bytes);
        var created=mvc.perform(MockMvcRequestBuilders.multipart(BASE).file(metadata).file(file)
            .with(login("pub-a")).with(csrf()).sessionAttr("guitarMentorAccessVersion",0L)
            .header("Idempotency-Key",UUID.randomUUID().toString()))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        JsonNode card=JSON.readTree(created);
        String id=card.path("id").asText();
        String asset=card.path("files").get(0).path("id").asText();
        String publishPath=BASE+"/"+id+"/publications";
        String publicationIdempotency=UUID.randomUUID().toString();

        String rights=JSON.writeValueAsString(Map.of("assets",java.util.List.of(
            Map.of("assetId",asset,"rightsBasis","OWN_WORK",
                "provenance","CREATED","rightsEvidence","My own original score, I authorize sharing."))));

        mvc.perform(MockMvcRequestBuilders.post(publishPath).with(login("pub-a"))
            .sessionAttr("guitarMentorAccessVersion",0L)
            .header("If-Match","\"1\"").header("Idempotency-Key",publicationIdempotency)
            .contentType("application/json").content(rights))
            .andExpect(status().isForbidden()); // CSRF
        mvc.perform(MockMvcRequestBuilders.post(publishPath).with(login("pub-b")).with(csrf())
            .sessionAttr("guitarMentorAccessVersion",0L)
            .header("If-Match","\"1\"").header("Idempotency-Key",publicationIdempotency)
            .contentType("application/json").content(rights))
            .andExpect(status().isNotFound());
        String published=mvc.perform(MockMvcRequestBuilders.post(publishPath)
            .with(login("pub-a")).with(csrf())
            .sessionAttr("guitarMentorAccessVersion",0L)
            .header("If-Match","\"1\"").header("Idempotency-Key",publicationIdempotency)
            .contentType("application/json").content(rights))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.state").value("PUBLISHED"))
            .andReturn().getResponse().getContentAsString();
        // Retrying an acknowledged publication must return the same UUID, not a new snapshot.
        mvc.perform(MockMvcRequestBuilders.post(publishPath).with(login("pub-a")).with(csrf())
            .sessionAttr("guitarMentorAccessVersion",0L)
            .header("If-Match","\"1\"").header("Idempotency-Key",publicationIdempotency)
            .contentType("application/json").content(rights))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(JSON.readTree(published).path("id").asText()));
        mvc.perform(MockMvcRequestBuilders.post(publishPath).with(login("pub-a")).with(csrf())
            .sessionAttr("guitarMentorAccessVersion",0L)
            .header("If-Match","\"1\"").contentType("application/json").content(rights))
            .andExpect(status().isPreconditionRequired());
        JsonNode snapshot=JSON.readTree(published);
        String shared="/api/v1/community/publications/"+snapshot.path("id").asText();
        String content=shared+"/files/"+asset+"/content";

        mvc.perform(MockMvcRequestBuilders.get(shared).with(login("pub-b"))
            .sessionAttr("guitarMentorAccessVersion",0L))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.assets[0].rightsEvidence").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.title").value("A's new score"));
        mvc.perform(MockMvcRequestBuilders.get(content).with(login("pub-b"))
            .sessionAttr("guitarMentorAccessVersion",0L))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(result->assertArrayEquals(bytes,result.getResponse().getContentAsByteArray()));
        mvc.perform(MockMvcRequestBuilders.get(content)).andExpect(status().isUnauthorized());
        mvc.perform(MockMvcRequestBuilders.post(
            BASE+"/"+id+"/publications/"+snapshot.path("id").asText()+"/withdraw")
            .with(login("pub-b")).with(csrf())
            .sessionAttr("guitarMentorAccessVersion",0L).header("If-Match","\"1\""))
            .andExpect(status().isNotFound());
        mvc.perform(MockMvcRequestBuilders.post(
            BASE+"/"+id+"/publications/"+snapshot.path("id").asText()+"/withdraw")
            .with(login("pub-a")).with(csrf())
            .sessionAttr("guitarMentorAccessVersion",0L).header("If-Match","\"1\""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("WITHDRAWN"));
        mvc.perform(MockMvcRequestBuilders.get(content).with(login("pub-b"))
            .sessionAttr("guitarMentorAccessVersion",0L))
            .andExpect(status().isNotFound());
        mvc.perform(MockMvcRequestBuilders.get(shared).with(login("pub-b"))
            .sessionAttr("guitarMentorAccessVersion",0L))
            .andExpect(status().isNotFound());
    }
}
