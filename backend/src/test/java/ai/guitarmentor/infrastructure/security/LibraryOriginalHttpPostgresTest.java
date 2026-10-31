package ai.guitarmentor.infrastructure.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.transaction.annotation.Transactional;

/** Full protected upload/download path, not just direct calls to a controller. */
@SpringBootTest(properties={
    "guitar-mentor.security.enabled=true",
    "guitar-mentor.library.enabled=true",
    "guitar-mentor.library.cursor-secret=ci-only-library-secret-longer-than-thirty-two-bytes"
})
@AutoConfigureMockMvc
@Import(SecurityHttpIntegrationTest.FakeOidcRegistration.class)
@EnabledIfEnvironmentVariable(named="GUITAR_MENTOR_TEST_POSTGRES",matches="true")
@Transactional
class LibraryOriginalHttpPostgresTest {
    private static final Path ROOT;
    static {
        try { ROOT=Files.createTempDirectory("gm-library-http-"); }
        catch(java.io.IOException ex) { throw new ExceptionInInitializerError(ex); }
    }
    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry r) {
        r.add("guitar-mentor.library.storage-path",()->ROOT.toString());
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @MockitoBean ActorRepository actors;
    @MockitoBean InvitationService invitations;

    private UUID account() {
        UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO accounts(id,role,status) VALUES(:id,'LEARNER','ACTIVE')")
            .param("id",id).update();
        return id;
    }

    @Test void csrfAuthenticatedMultipartAndForeignDownloads() throws Exception {
        UUID alice=account(),bob=account();
        when(actors.findActive("https://issuer.example","alice"))
            .thenReturn(Optional.of(new CurrentActor(alice,null,"LEARNER")));
        when(actors.findActive("https://issuer.example","bob"))
            .thenReturn(Optional.of(new CurrentActor(bob,null,"LEARNER")));
        when(actors.localVersion(alice)).thenReturn(Optional.of(0L));
        when(actors.localVersion(bob)).thenReturn(Optional.of(0L));

        var identityA=oidcLogin().idToken(t -> t.claim("iss","https://issuer.example").subject("alice"));
        var identityB=oidcLogin().idToken(t -> t.claim("iss","https://issuer.example").subject("bob"));
        var pdf="%PDF-1.7\nprivate content\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var metadata=new MockMultipartFile("metadata","","application/json",
            new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(
                java.util.Map.of("title","Private song","type","COMPOSITION")));
        var file=new MockMultipartFile("files","private.pdf","application/pdf",pdf);
        String endpoint="/api/v1/library/materials";
        UUID key=UUID.randomUUID();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .multipart(endpoint).file(metadata).file(file).with(identityA)
            .sessionAttr("guitarMentorAccessVersion",0L)
            .header("Idempotency-Key",key.toString()))
            .andExpect(status().isForbidden());

        String response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .multipart(endpoint).file(metadata).file(file).with(identityA).with(csrf())
            .sessionAttr("guitarMentorAccessVersion",0L)
            .header("Idempotency-Key",key.toString()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.visibility").value("PRIVATE"))
            .andExpect(jsonPath("$.files[0].format").value("PDF"))
            .andReturn().getResponse().getContentAsString();
        var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(response);
        String material=json.get("id").asText(),asset=json.get("files").get(0).get("id").asText();
        String uri=endpoint+"/"+material+"/files/"+asset+"/content";

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(uri)
            .with(identityA).sessionAttr("guitarMentorAccessVersion",0L))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Content-Type-Options","nosniff"))
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(header().string("Content-Type","application/pdf"))
            .andExpect(result -> assertArrayEquals(pdf,result.getResponse().getContentAsByteArray()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head(uri)
            .with(identityA).sessionAttr("guitarMentorAccessVersion",0L))
            .andExpect(status().isOk())
            .andExpect(result -> assertEquals(0,result.getResponse().getContentAsByteArray().length));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(uri)
            .with(identityB).sessionAttr("guitarMentorAccessVersion",0L))
            .andExpect(status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(uri))
            .andExpect(status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(endpoint)
            .with(identityA).sessionAttr("guitarMentorAccessVersion",0L))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].id").value(material));
    }
    @Test void communityMediaFlagOffDoesNotBreakPdfGpLibraryContract() throws Exception {
        UUID owner=account();
        when(actors.findActive("https://issuer.example","media-disabled"))
            .thenReturn(Optional.of(new CurrentActor(owner,null,"LEARNER")));
        when(actors.localVersion(owner)).thenReturn(Optional.of(0L));
        var identity=oidcLogin().idToken(t->t.claim("iss","https://issuer.example").subject("media-disabled"));
        byte[] pdf="%PDF-1.7\nlegacy library remains enabled\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var metadata=new MockMultipartFile("metadata","","application/json",
            new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(
                java.util.Map.of("title","Legacy PDF","type","ETUDE")));
        var original=new MockMultipartFile("files","legacy.pdf","application/pdf",pdf);
        String body=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .multipart("/api/v1/library/materials").file(metadata).file(original).with(identity).with(csrf())
            .sessionAttr("guitarMentorAccessVersion",0L).header("Idempotency-Key",UUID.randomUUID().toString()))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var card=new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        String material=card.path("id").asText(),asset=card.path("files").get(0).path("id").asText();
        var mediaMetadata=new MockMultipartFile("metadata","","application/json",
            new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(java.util.Map.of("role","PHOTO_ORIGINAL")));
        var photo=new MockMultipartFile("files","photo.png","image/png",new byte[]{1,2,3});
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .multipart("/api/v1/library/materials/"+material+"/files").file(mediaMetadata).file(photo)
            .with(identity).with(csrf()).sessionAttr("guitarMentorAccessVersion",0L)
            .header("If-Match","\"1\"").header("Idempotency-Key",UUID.randomUUID().toString()))
            .andExpect(status().isServiceUnavailable());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/library/capabilities")
            .with(identity).sessionAttr("guitarMentorAccessVersion",0L))
            .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false))
            .andExpect(jsonPath("$.roles.length()").value(0))
            .andExpect(jsonPath("$.maxFilesPerRequest").value(0))
            .andExpect(jsonPath("$.maxFilesPerMaterial").value(5))
            .andExpect(jsonPath("$.maxAudio").value(0))
            .andExpect(jsonPath("$.maxRequestBytes").value(0));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
            "/api/v1/library/materials/"+material+"/files/"+asset+"/content")
            .with(identity).sessionAttr("guitarMentorAccessVersion",0L))
            .andExpect(status().isOk()).andExpect(result->assertArrayEquals(pdf,result.getResponse().getContentAsByteArray()));
    }

}
