package ai.guitarmentor.infrastructure.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
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

/** Full Spring Security + MockMvc + real PostgreSQL media lifecycle; not a direct controller mock. */
@SpringBootTest(properties={
    "guitar-mentor.security.enabled=true",
    "guitar-mentor.library.enabled=true",
    "guitar-mentor.library.community-media-enabled=true",
    "guitar-mentor.library.asset-deletion-delay-ms=3600000",
    "guitar-mentor.library.reconcile-delay-ms=3600000",
    "guitar-mentor.library.cursor-secret=ci-only-community-media-secret-at-least-thirty-two-bytes"
})
@AutoConfigureMockMvc
@Import(SecurityHttpIntegrationTest.FakeOidcRegistration.class)
@EnabledIfEnvironmentVariable(named="GUITAR_MENTOR_TEST_POSTGRES",matches="true")
@Transactional
class LibraryMediaHttpPostgresTest {
    private static final Path ROOT;
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String BASE="/api/v1/library/materials";
    static {
        try { ROOT=Files.createTempDirectory("gm-community-media-http-"); }
        catch(java.io.IOException e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void storage(DynamicPropertyRegistry r) {
        r.add("guitar-mentor.library.storage-path",ROOT::toString);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ai.guitarmentor.infrastructure.persistence.LibraryAssetDeletionWorker assetDeletion;
    @Autowired ai.guitarmentor.infrastructure.persistence.LibraryOrphanRecovery recovery;
    @MockitoBean ActorRepository actors;
    @MockitoBean InvitationService invitations;
    @AfterAll static void cleanupStorage() throws Exception {
        if(Files.exists(ROOT)) try(var paths=Files.walk(ROOT)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(Exception ignored){}});
        }
    }

    private UUID account(String sub) { return account(sub,"LEARNER"); }
    private UUID account(String sub,String role) {
        UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO accounts(id,role,status) VALUES(:id,:role,'ACTIVE')")
            .param("id",id).param("role",role).update();
        when(actors.findActive("https://issuer.example",sub)).thenReturn(Optional.of(new CurrentActor(id,null,role)));
        when(actors.localVersion(id)).thenReturn(Optional.of(0L));
        return id;
    }
    private org.springframework.test.web.servlet.request.RequestPostProcessor login(String sub) {
        return oidcLogin().idToken(t->t.claim("iss","https://issuer.example").subject(sub));
    }
    private MockMultipartFile metadata(String role) throws Exception {
        return new MockMultipartFile("metadata","","application/json",JSON.writeValueAsBytes(Map.of("role",role)));
    }
    private JsonNode createPdf(String sub) throws Exception {
        var metadata=new MockMultipartFile("metadata","","application/json",
            JSON.writeValueAsBytes(Map.of("title","Media integration score","type","ETUDE")));
        var pdf=new MockMultipartFile("files","score.pdf","application/pdf","%PDF-1.7\nscore\n".getBytes());
        String body=mvc.perform(MockMvcRequestBuilders.multipart(BASE).file(metadata).file(pdf)
            .with(req->{req.setRemoteAddr("198.51.100."+(Math.abs(sub.hashCode())%200+1));return req;})
            .with(login(sub)).with(csrf()).sessionAttr("guitarMentorAccessVersion",0L)
            .header("Idempotency-Key",UUID.randomUUID().toString()))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(body);
    }

    @Test void mediaUploadReplayStaleAtomicityPreviewAudioRangeAndPublicationMembership() throws Exception {
        UUID alice=account("media-a"); account("media-b");
        JsonNode card=createPdf("media-a");
        String material=card.path("id").asText();
        String photoIdempotency=UUID.randomUUID().toString();
        byte[] png=png(23,19);
        var photoFile=new MockMultipartFile("files","lesson.png","image/png",png);
        String photoPath=BASE+"/"+material+"/files";
        mvc.perform(MockMvcRequestBuilders.multipart(photoPath).file(metadata("PHOTO_ORIGINAL"))
            .file(photoFile).with(login("media-a")).sessionAttr("guitarMentorAccessVersion",0L)
            .header("If-Match","\"1\"").header("Idempotency-Key",UUID.randomUUID().toString()))
            .andExpect(status().isForbidden()); // CSRF is required for media writes
        String photoBody=mvc.perform(MockMvcRequestBuilders.multipart(photoPath)
            .file(metadata("PHOTO_ORIGINAL")).file(photoFile).with(login("media-a")).with(csrf())
            .sessionAttr("guitarMentorAccessVersion",0L).header("If-Match","\"1\"")
            .header("Idempotency-Key",photoIdempotency))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.revision").value(2))
            .andReturn().getResponse().getContentAsString();
        JsonNode photoResult=JSON.readTree(photoBody);
        JsonNode photoAssetView=asset(photoResult,"PHOTO_ORIGINAL");
        assertTrue(photoAssetView.path("previewAvailable").asBoolean());
        String photoAsset=photoAssetView.path("id").asText();
        String privatePreview=photoPath+"/"+photoAsset+"/preview";
        mvc.perform(MockMvcRequestBuilders.get(privatePreview).with(login("media-a"))
            .sessionAttr("guitarMentorAccessVersion",0L)).andExpect(status().isOk())
            .andExpect(header().string("Content-Type","image/png"))
            .andExpect(result->{byte[] preview=result.getResponse().getContentAsByteArray();
                assertTrue(preview.length>0); assertEquals(23,ImageIO.read(new ByteArrayInputStream(preview)).getWidth());});
        mvc.perform(MockMvcRequestBuilders.get(photoPath+"/"+photoAsset+"/content").with(login("media-a"))
            .sessionAttr("guitarMentorAccessVersion",0L))
            .andExpect(status().isOk()).andExpect(result->assertArrayEquals(png,result.getResponse().getContentAsByteArray()));
        mvc.perform(MockMvcRequestBuilders.get(privatePreview).with(login("media-b"))
            .sessionAttr("guitarMentorAccessVersion",0L)).andExpect(status().isNotFound());
        mvc.perform(MockMvcRequestBuilders.get(privatePreview)).andExpect(status().isUnauthorized());

        // Invalid second file must roll back the whole multi-file request and release reservation.
        var valid2=new MockMultipartFile("files","second.png","image/png",png(11,9));
        var invalid=new MockMultipartFile("files","bad.png","image/png",new byte[]{1,2,3});
        mvc.perform(MockMvcRequestBuilders.multipart(photoPath).file(metadata("PHOTO_ORIGINAL"))
            .file(valid2).file(invalid).with(login("media-a")).with(csrf())
            .sessionAttr("guitarMentorAccessVersion",0L).header("If-Match","\"2\"")
            .header("Idempotency-Key",UUID.randomUUID().toString()))
            .andExpect(status().isBadRequest());
        assertEquals(2L,jdbc.sql("SELECT revision FROM library_materials WHERE id=CAST(:id AS uuid)")
            .param("id",material).query(Long.class).single());
        assertEquals(0L,jdbc.sql("SELECT reserved_files FROM library_account_usage WHERE account_id=:id")
            .param("id",alice).query(Long.class).single());
        assertEquals(0L,jdbc.sql("SELECT reserved_bytes FROM library_account_usage WHERE account_id=:id")
            .param("id",alice).query(Long.class).single());

        // Audio request adds two different allowed codecs atomically; replay with the original ETag is valid.
        String audioKey=UUID.randomUUID().toString();
        byte[] mp3=mp3Sample(), wav=wav(1,44100,88200);
        var mp3File=new MockMultipartFile("files","take.mp3","audio/mpeg",mp3);
        var wavFile=new MockMultipartFile("files","synth.wav","audio/wav",wav);
        String audioBody=mvc.perform(MockMvcRequestBuilders.multipart(photoPath).file(metadata("ORIGINAL_RECORDING"))
            .file(mp3File).file(wavFile).with(login("media-a")).with(csrf())
            .sessionAttr("guitarMentorAccessVersion",0L).header("If-Match","\"2\"")
            .header("Idempotency-Key",audioKey))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.revision").value(3))
            .andExpect(jsonPath("$.files.length()").value(4))
            .andReturn().getResponse().getContentAsString();
        JsonNode audioResult=JSON.readTree(audioBody);
        String audioAsset=null, wavAsset=null;
        for(JsonNode file:audioResult.path("files")) {
            if("MP3".equals(file.path("format").asText())) audioAsset=file.path("id").asText();
            if("WAV".equals(file.path("format").asText())) wavAsset=file.path("id").asText();
        }
        assertNotNull(audioAsset); assertNotNull(wavAsset);
        assertEquals(5L,jdbc.sql("SELECT charged_files FROM library_account_usage WHERE account_id=:id")
            .param("id",alice).query(Long.class).single()); // PDF + photo original/preview + two audio originals
        mvc.perform(MockMvcRequestBuilders.multipart(photoPath).file(metadata("ORIGINAL_RECORDING"))
            .file(mp3File).file(wavFile).with(login("media-a")).with(csrf())
            .sessionAttr("guitarMentorAccessVersion",0L).header("If-Match","\"2\"")
            .header("Idempotency-Key",audioKey))
            .andExpect(status().isOk()).andExpect(header().string("Idempotency-Replayed","true"))
            .andExpect(jsonPath("$.revision").value(3));
        mvc.perform(MockMvcRequestBuilders.multipart(photoPath).file(metadata("ORIGINAL_RECORDING"))
            .file(new MockMultipartFile("files","take.mp3","audio/mpeg",mp3Sample()))
            .with(login("media-a")).with(csrf()).sessionAttr("guitarMentorAccessVersion",0L)
            .header("If-Match","\"2\"").header("Idempotency-Key",audioKey))
            .andExpect(status().isConflict());
        mvc.perform(MockMvcRequestBuilders.multipart(photoPath).file(metadata("ORIGINAL_RECORDING"))
            .file(mp3File).with(login("media-a")).with(csrf()).sessionAttr("guitarMentorAccessVersion",0L)
            .header("If-Match","\"2\"").header("Idempotency-Key",UUID.randomUUID().toString()))
            .andExpect(status().isPreconditionFailed());

        String privateAudio=photoPath+"/"+audioAsset+"/content";
        mvc.perform(MockMvcRequestBuilders.get(privateAudio).with(login("media-a"))
            .sessionAttr("guitarMentorAccessVersion",0L).header("Range","bytes=0-9"))
            .andExpect(status().isPartialContent()).andExpect(header().string("Content-Range","bytes 0-9/"+mp3.length))
            .andExpect(result->assertEquals(10,result.getResponse().getContentAsByteArray().length));
        mvc.perform(MockMvcRequestBuilders.get(privateAudio).with(login("media-a"))
            .sessionAttr("guitarMentorAccessVersion",0L).header("Range","bytes=0-1,4-5"))
            .andExpect(status().isRequestedRangeNotSatisfiable());
        mvc.perform(MockMvcRequestBuilders.head(privateAudio).with(login("media-a"))
            .sessionAttr("guitarMentorAccessVersion",0L)).andExpect(status().isOk())
            .andExpect(result->assertEquals(0,result.getResponse().getContentAsByteArray().length));

        String rights=JSON.writeValueAsString(Map.of("assets",List.of(
            right(card.path("files").get(0).path("id").asText()),right(photoAsset),
            right(audioAsset),right(wavAsset))));
        String publication=mvc.perform(MockMvcRequestBuilders.post(BASE+"/"+material+"/publications")
            .with(login("media-a")).with(csrf()).sessionAttr("guitarMentorAccessVersion",0L)
            .header("If-Match","\"3\"").header("Idempotency-Key",UUID.randomUUID().toString())
            .contentType("application/json").content(rights))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.assets.length()").value(4))
            .andReturn().getResponse().getContentAsString();
        String publicationId=JSON.readTree(publication).path("id").asText();
        String catalog="/api/v1/community/publications";
        mvc.perform(MockMvcRequestBuilders.get(catalog).with(login("media-b"))
            .sessionAttr("guitarMentorAccessVersion",0L).param("hasAudio","true"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(MockMvcRequestBuilders.get(catalog).with(login("media-b"))
            .sessionAttr("guitarMentorAccessVersion",0L).param("hasAudio","false"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        String sharedPreview=catalog+"/"+publicationId+"/files/"+photoAsset+"/preview";
        mvc.perform(MockMvcRequestBuilders.get(sharedPreview).with(login("media-b"))
            .sessionAttr("guitarMentorAccessVersion",0L)).andExpect(status().isOk())
            .andExpect(header().string("Content-Type","image/png"));
        mvc.perform(MockMvcRequestBuilders.get(catalog+"/"+publicationId+"/files/"+audioAsset+"/content")
            .with(login("media-b")).sessionAttr("guitarMentorAccessVersion",0L).header("Range","bytes=0-4"))
            .andExpect(status().isPartialContent()).andExpect(header().string("Content-Range","bytes 0-4/"+mp3.length));
        mvc.perform(MockMvcRequestBuilders.delete(photoPath+"/"+audioAsset).with(login("media-a")).with(csrf())
            .sessionAttr("guitarMentorAccessVersion",0L).header("If-Match","\"3\""))
            .andExpect(status().isConflict());
        mvc.perform(MockMvcRequestBuilders.post(BASE+"/"+material+"/publications/"+publicationId+"/withdraw")
            .with(login("media-a")).with(csrf()).sessionAttr("guitarMentorAccessVersion",0L)
            .header("If-Match","\"1\"")).andExpect(status().isOk());
        mvc.perform(MockMvcRequestBuilders.get(sharedPreview).with(login("media-b"))
            .sessionAttr("guitarMentorAccessVersion",0L)).andExpect(status().isNotFound());
        mvc.perform(MockMvcRequestBuilders.delete(photoPath+"/"+audioAsset).with(login("media-a")).with(csrf())
            .sessionAttr("guitarMentorAccessVersion",0L).header("If-Match","\"3\""))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.state").value("DELETING"));
        assertEquals(1,assetDeletion.processBatch(10));
        assertEquals(4L,jdbc.sql("SELECT charged_files FROM library_account_usage WHERE account_id=:id")
            .param("id",alice).query(Long.class).single());
        mvc.perform(MockMvcRequestBuilders.get(privateAudio).with(login("media-a"))
            .sessionAttr("guitarMentorAccessVersion",0L)).andExpect(status().isNotFound());
        String photoObject=jdbc.sql("SELECT object_key FROM library_assets WHERE id=CAST(:id AS uuid)")
            .param("id",photoAsset).query(String.class).single();
        Files.delete(ROOT.resolve(photoObject));
        mvc.perform(MockMvcRequestBuilders.get(photoPath+"/"+photoAsset+"/content").with(login("media-a"))
            .sessionAttr("guitarMentorAccessVersion",0L)).andExpect(status().isNotFound());
        recovery.reconcile();
        mvc.perform(MockMvcRequestBuilders.get(BASE+"/"+material).with(login("media-a"))
            .sessionAttr("guitarMentorAccessVersion",0L)).andExpect(status().isOk())
            .andExpect(result->assertEquals("REJECTED",asset(JSON.readTree(result.getResponse().getContentAsString()),
                "PHOTO_ORIGINAL").path("processingState").asText()));
        mvc.perform(MockMvcRequestBuilders.delete(photoPath+"/"+photoAsset).with(login("media-a")).with(csrf())
            .sessionAttr("guitarMentorAccessVersion",0L).header("If-Match","\"4\""))
            .andExpect(status().isAccepted());
        assertEquals(1,assetDeletion.processBatch(10));
        assertEquals(2L,jdbc.sql("SELECT charged_files FROM library_account_usage WHERE account_id=:id")
            .param("id",alice).query(Long.class).single());
        mvc.perform(MockMvcRequestBuilders.delete(photoPath+"/"+card.path("files").get(0).path("id").asText())
            .with(login("media-a")).with(csrf()).sessionAttr("guitarMentorAccessVersion",0L)
            .header("If-Match","\"5\""))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CATALOG_LAST_SCORE"));
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/library/capabilities").with(login("media-a"))
            .sessionAttr("guitarMentorAccessVersion",0L)).andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.maxFilesPerMaterial").value(7))
            .andExpect(jsonPath("$.maxFilesPerRequest").value(2))
            .andExpect(jsonPath("$.maxPhotosAndScores").value(5))
            .andExpect(jsonPath("$.maxAudio").value(2))
            .andExpect(jsonPath("$.maxRequestBytes").value(20971520));
    }

    @Test void requestAndPerMaterialAssetCapsAreEnforcedWithoutMutation() throws Exception {
        account("caps-a");
        JsonNode photoCard=createPdf("caps-a");
        String photoMaterial=photoCard.path("id").asText();
        byte[] smallPng=png(8,8);
        mvc.perform(MockMvcRequestBuilders.multipart(BASE+"/"+photoMaterial+"/files")
            .file(metadata("PHOTO_ORIGINAL"))
            .file(new MockMultipartFile("files","p1.png","image/png",smallPng))
            .file(new MockMultipartFile("files","p2.png","image/png",smallPng))
            .with(login("caps-a")).with(csrf()).sessionAttr("guitarMentorAccessVersion",0L)
            .header("If-Match","\"1\"").header("Idempotency-Key",UUID.randomUUID().toString()))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.revision").value(2));
        mvc.perform(MockMvcRequestBuilders.multipart(BASE+"/"+photoMaterial+"/files")
            .file(metadata("PHOTO_ORIGINAL"))
            .file(new MockMultipartFile("files","p3.png","image/png",smallPng))
            .file(new MockMultipartFile("files","p4.png","image/png",smallPng))
            .with(login("caps-a")).with(csrf()).sessionAttr("guitarMentorAccessVersion",0L)
            .header("If-Match","\"2\"").header("Idempotency-Key",UUID.randomUUID().toString()))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.revision").value(3));
        mvc.perform(MockMvcRequestBuilders.multipart(BASE+"/"+photoMaterial+"/files")
            .file(metadata("PHOTO_ORIGINAL")).file(new MockMultipartFile("files","p5.png","image/png",smallPng))
            .with(login("caps-a")).with(csrf()).sessionAttr("guitarMentorAccessVersion",0L)
            .header("If-Match","\"3\"").header("Idempotency-Key",UUID.randomUUID().toString()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LIBRARY_MATERIAL_PHOTO_LIMIT"));
        assertEquals(3L,jdbc.sql("SELECT revision FROM library_materials WHERE id=CAST(:id AS uuid)")
            .param("id",photoMaterial).query(Long.class).single());
        assertEquals(5L,jdbc.sql("SELECT count(*) FROM library_assets WHERE material_id=CAST(:id AS uuid)")
            .param("id",photoMaterial).query(Long.class).single());

        JsonNode totalCard=createPdf("caps-a");
        String totalMaterial=totalCard.path("id").asText();
        mvc.perform(MockMvcRequestBuilders.multipart(BASE+"/"+totalMaterial+"/files")
            .file(metadata("PHOTO_ORIGINAL"))
            .file(new MockMultipartFile("files","a.png","image/png",smallPng))
            .file(new MockMultipartFile("files","b.png","image/png",smallPng))
            .with(login("caps-a")).with(csrf()).sessionAttr("guitarMentorAccessVersion",0L)
            .header("If-Match","\"1\"").header("Idempotency-Key",UUID.randomUUID().toString()))
            .andExpect(status().isCreated());
        byte[] mp3=mp3Sample();
        mvc.perform(MockMvcRequestBuilders.multipart(BASE+"/"+totalMaterial+"/files")
            .file(metadata("ORIGINAL_RECORDING"))
            .file(new MockMultipartFile("files","a.mp3","audio/mpeg",mp3))
            .file(new MockMultipartFile("files","b.mp3","audio/mpeg",mp3))
            .with(login("caps-a")).with(csrf()).sessionAttr("guitarMentorAccessVersion",0L)
            .header("If-Match","\"2\"").header("Idempotency-Key",UUID.randomUUID().toString()))
            .andExpect(status().isCreated());
        mvc.perform(MockMvcRequestBuilders.multipart(BASE+"/"+totalMaterial+"/files")
            .file(metadata("PHOTO_ORIGINAL"))
            .file(new MockMultipartFile("files","c.png","image/png",smallPng))
            .file(new MockMultipartFile("files","d.png","image/png",smallPng))
            .with(login("caps-a")).with(csrf()).sessionAttr("guitarMentorAccessVersion",0L)
            .header("If-Match","\"3\"").header("Idempotency-Key",UUID.randomUUID().toString()))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.files.length()").value(7));
        mvc.perform(MockMvcRequestBuilders.multipart(BASE+"/"+totalMaterial+"/files")
            .file(metadata("ORIGINAL_RECORDING"))
            .file(new MockMultipartFile("files","extra.mp3","audio/mpeg",mp3))
            .with(login("caps-a")).with(csrf()).sessionAttr("guitarMentorAccessVersion",0L)
            .header("If-Match","\"4\"").header("Idempotency-Key",UUID.randomUUID().toString()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LIBRARY_MATERIAL_ASSET_LIMIT"));
        mvc.perform(MockMvcRequestBuilders.multipart(BASE+"/"+totalMaterial+"/files")
            .file(metadata("PHOTO_ORIGINAL"))
            .file(new MockMultipartFile("files","1.png","image/png",smallPng))
            .file(new MockMultipartFile("files","2.png","image/png",smallPng))
            .file(new MockMultipartFile("files","3.png","image/png",smallPng))
            .with(login("caps-a")).with(csrf()).sessionAttr("guitarMentorAccessVersion",0L)
            .header("If-Match","\"4\"").header("Idempotency-Key",UUID.randomUUID().toString()))
            .andExpect(status().isBadRequest());
        assertEquals(4L,jdbc.sql("SELECT revision FROM library_materials WHERE id=CAST(:id AS uuid)")
            .param("id",totalMaterial).query(Long.class).single());
        assertEquals(7L,jdbc.sql("SELECT count(*) FROM library_assets WHERE material_id=CAST(:id AS uuid)")
            .param("id",totalMaterial).query(Long.class).single());

        JsonNode aggregateCard=createPdf("caps-a");
        String aggregateMaterial=aggregateCard.path("id").asText();
        byte[] largeWav=wav(2,8000,10_500_000);
        mvc.perform(MockMvcRequestBuilders.multipart(BASE+"/"+aggregateMaterial+"/files")
            .file(metadata("ORIGINAL_RECORDING"))
            .file(new MockMultipartFile("files","large1.wav","audio/wav",largeWav))
            .file(new MockMultipartFile("files","large2.wav","audio/wav",largeWav))
            .with(login("caps-a")).with(csrf()).sessionAttr("guitarMentorAccessVersion",0L)
            .header("If-Match","\"1\"").header("Idempotency-Key",UUID.randomUUID().toString()))
            .andExpect(status().isPayloadTooLarge());
        assertEquals(1L,jdbc.sql("SELECT revision FROM library_materials WHERE id=CAST(:id AS uuid)")
            .param("id",aggregateMaterial).query(Long.class).single());
        assertEquals(1L,jdbc.sql("SELECT count(*) FROM library_assets WHERE material_id=CAST(:id AS uuid)")
            .param("id",aggregateMaterial).query(Long.class).single());
    }

    @Test void addingPhotoAfterPublicationCreatesNewDraftRevisionWithoutChangingPublishedSnapshot() throws Exception {
        account("snapshot-a"); account("snapshot-b");
        JsonNode card=createPdf("snapshot-a");
        String material=card.path("id").asText();
        String scoreId=card.path("files").get(0).path("id").asText();
        String body=mvc.perform(MockMvcRequestBuilders.post(BASE+"/"+material+"/publications")
            .with(login("snapshot-a")).with(csrf()).sessionAttr("guitarMentorAccessVersion",0L)
            .header("If-Match","\"1\"").header("Idempotency-Key",UUID.randomUUID().toString())
            .contentType("application/json").content(JSON.writeValueAsBytes(Map.of("assets",List.of(right(scoreId)))))
        ).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode publication=JSON.readTree(body);
        String publicationId=publication.path("id").asText();
        var photo=new MockMultipartFile("files","later.png","image/png",png(13,15));
        String draftBody=mvc.perform(MockMvcRequestBuilders.multipart(BASE+"/"+material+"/files").file(metadata("PHOTO_ORIGINAL"))
            .file(photo).with(login("snapshot-a")).with(csrf()).sessionAttr("guitarMentorAccessVersion",0L)
            .header("If-Match","\"1\"").header("Idempotency-Key",UUID.randomUUID().toString()))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.revision").value(2))
            .andReturn().getResponse().getContentAsString();
        String photoId=asset(JSON.readTree(draftBody),"PHOTO_ORIGINAL").path("id").asText();
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/community/publications/"+publicationId)
            .with(login("snapshot-b")).sessionAttr("guitarMentorAccessVersion",0L))
            .andExpect(status().isOk()).andExpect(jsonPath("$.sourceRevision").value(1))
            .andExpect(jsonPath("$.assets.length()").value(1))
            .andExpect(jsonPath("$.assets[0].role").value("SCORE_ORIGINAL"));
        mvc.perform(MockMvcRequestBuilders.get(BASE+"/"+material).with(login("snapshot-a"))
            .sessionAttr("guitarMentorAccessVersion",0L)).andExpect(status().isOk())
            .andExpect(jsonPath("$.revision").value(2)).andExpect(jsonPath("$.files.length()").value(2));
        String replacementBody=mvc.perform(MockMvcRequestBuilders.post(BASE+"/"+material+"/publications")
            .with(login("snapshot-a")).with(csrf()).sessionAttr("guitarMentorAccessVersion",0L)
            .header("If-Match","\"2\"").header("Idempotency-Key",UUID.randomUUID().toString())
            .contentType("application/json").content(JSON.writeValueAsBytes(Map.of(
                "assets",List.of(right(scoreId),right(photoId)),"replacePublicationId",publicationId))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String replacementId=JSON.readTree(replacementBody).path("id").asText();
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/community/publications/"+publicationId)
            .with(login("snapshot-b")).sessionAttr("guitarMentorAccessVersion",0L))
            .andExpect(status().isNotFound());
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/community/publications/"+replacementId)
            .with(login("snapshot-b")).sessionAttr("guitarMentorAccessVersion",0L))
            .andExpect(status().isOk()).andExpect(jsonPath("$.sourceRevision").value(2))
            .andExpect(jsonPath("$.assets.length()").value(2));
        account("snapshot-admin","OWNER");
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/community/publications/"+replacementId+"/hide")
            .with(login("snapshot-admin")).with(csrf()).sessionAttr("guitarMentorAccessVersion",0L)
            .header("If-Match","\"1\"").contentType("application/json")
            .content(JSON.writeValueAsBytes(Map.of("reason","Rights evidence needs operator review"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("HIDDEN"));
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/community/publications/"+replacementId+"/files/"+photoId+"/preview")
            .with(login("snapshot-b")).sessionAttr("guitarMentorAccessVersion",0L))
            .andExpect(status().isNotFound());
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/community/publications/"+replacementId+"/files/"+photoId+"/content")
            .with(login("snapshot-b")).sessionAttr("guitarMentorAccessVersion",0L))
            .andExpect(status().isNotFound());
    }

    @Test void synthesizedPreviewIsAnExplicitAudioRoleAndNotAnApprovalSignal() throws Exception {
        account("synth-a");
        JsonNode card=createPdf("synth-a");
        String material=card.path("id").asText();
        byte[] wav=wav(2,48000,192000);
        String body=mvc.perform(MockMvcRequestBuilders.multipart(BASE+"/"+material+"/files").file(metadata("SYNTHESIZED_PREVIEW"))
            .file(new MockMultipartFile("files","preview.wav","audio/wav",wav))
            .with(login("synth-a")).with(csrf()).sessionAttr("guitarMentorAccessVersion",0L)
            .header("If-Match","\"1\"").header("Idempotency-Key",UUID.randomUUID().toString()))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.reviewStatus").value("UNREVIEWED"))
            .andReturn().getResponse().getContentAsString();
        JsonNode audioAsset=asset(JSON.readTree(body),"SYNTHESIZED_PREVIEW");
        assertEquals(1000,audioAsset.path("durationMillis").asLong());
    }

    private static JsonNode asset(JsonNode material,String role) {
        for(JsonNode file:material.path("files"))
            if(role.equals(file.path("role").asText())) return file;
        throw new AssertionError("Asset role not found: "+role);
    }

    private static Map<String,Object> right(String id) {
        return Map.of("assetId",id,"rightsBasis","OWN_WORK","provenance","CREATED",
            "rightsEvidence","My own work, permission to share this individual file.");
    }
    private static byte[] png(int w,int h) throws Exception {
        BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out=new ByteArrayOutputStream(); assertTrue(ImageIO.write(image,"png",out)); image.flush(); return out.toByteArray();
    }
    private static byte[] mp3Sample() throws Exception {
        try(var input=LibraryMediaHttpPostgresTest.class.getResourceAsStream("/exercises/GM-EB01/preview_v08B.mp3")) {
            assertNotNull(input,"tracked real MP3 fixture");
            return input.readAllBytes();
        }
    }
    private static byte[] wav(int channels,int rate,int dataBytes) {
        int block=channels*2;
        ByteBuffer b=ByteBuffer.allocate(44+dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{82,73,70,70}).putInt(36+dataBytes).put(new byte[]{87,65,86,69});
        b.put(new byte[]{102,109,116,32}).putInt(16).putShort((short)1).putShort((short)channels)
            .putInt(rate).putInt(rate*block).putShort((short)block).putShort((short)16);
        b.put(new byte[]{100,97,116,97}).putInt(dataBytes); return b.array();
    }
}
