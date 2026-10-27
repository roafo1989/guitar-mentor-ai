package ai.guitarmentor.infrastructure.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/** #237: real DB + security/MVC + original storage, synthetic IdP and 0.1s WAV. */
@SpringBootTest(properties={
    "guitar-mentor.security.enabled=true", "guitarmentor.learning.enabled=true",
    "guitar-mentor.submissions.enabled=true", "guitar-mentor.submissions.poll-delay-ms=600000"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="GUITAR_MENTOR_TEST_POSTGRES",matches="true")
@Transactional
class GeneralRegressionHttpPostgresTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final Path ROOT;
    static {try {ROOT=Files.createTempDirectory("gm-general-qa-");}
        catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void storage(DynamicPropertyRegistry registry) {
        registry.add("guitar-mentor.submissions.storage-path",ROOT::toString);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired InvitationService admissions;
    record Student(UUID learner,RequestPostProcessor auth) {
        String base(){return "/api/v1/learners/"+learner;}
    }
    Student student() {
        String id=UUID.randomUUID().toString();
        var identity=new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")),
            new OidcIdToken("synthetic",Instant.now().minusSeconds(10),Instant.now().plusSeconds(300),
                Map.of("iss",InvitationService.GOOGLE_ISSUER,"sub",id,"email",id+"@example.test",
                    "email_verified",true,"name","General QA")));
        UUID account=admissions.admitPublicGoogle(identity);
        UUID learner=jdbc.sql("SELECT learner_id FROM accounts WHERE id=:id")
            .param("id",account).query(UUID.class).single();
        return new Student(learner,req->{req=oidcLogin().oidcUser(identity).postProcessRequest(req);
            req.getSession().setAttribute("guitarMentorAccessVersion",0L);return req;});
    }
    MvcResult call(Student s,AbstractMockHttpServletRequestBuilder<?> request,int status) throws Exception {
        return mvc.perform(request.with(s.auth()).with(csrf())).andExpect(status().is(status)).andReturn();
    }
    JsonNode read(MvcResult result) throws Exception {return JSON.readTree(result.getResponse().getContentAsString());}
    MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request,Object payload) throws Exception {
        return request.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsBytes(payload));
    }
    static byte[] wave() {
        byte[] samples=new byte[1600];
        return ByteBuffer.allocate(44+samples.length).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".getBytes()).putInt(36+samples.length).put("WAVEfmt ".getBytes()).putInt(16)
            .putShort((short)1).putShort((short)1).putInt(8000).putInt(16000)
            .putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(samples.length).put(samples).array();
    }

    @Test void profileToArchivedLessonAndSuccessorAcrossActualHttpContracts() throws Exception {
        var a=student();var b=student();
        call(a,get(a.base()),200);
        call(a,body(put(a.base()+"/preferences"),Map.of("instrumentCode","ACOUSTIC_GUITAR",
            "preferences",List.of(Map.of("styleCode","BLUES","rating","LIKE")))),200);
        String bg=a.base()+"/instruments/ACOUSTIC_GUITAR/background";
        call(a,get(bg),204);
        var background=Map.of("experienceLevel","BEGINNER","yearsPlaying",1,"skills",List.of("ARPEGGIOS"),
            "knownPieces",List.of(Map.of("title","Yesterday","artist","The Beatles")),
            "goals",List.of("FINGERSTYLE"),"practiceMinutes",30,"sessionsPerWeek",3,"notes","QA retained answers");
        call(a,body(put(bg),background),200);
        assertEquals(JSON.valueToTree(background),read(call(a,get(bg),200)));
        call(b,get(bg),404);
        call(a,get(a.base()+"/instruments/ACOUSTIC_GUITAR/skills"),200);
        String sessions=a.base()+"/diagnostic-sessions";
        call(a,get(sessions+"/current?instrumentCode=ACOUSTIC_GUITAR"),204);
        String session=read(call(a,body(post(sessions),Map.of("instrumentCode","ACOUSTIC_GUITAR")),201)).path("id").asText();
        assertEquals(session,read(call(a,body(post(sessions),Map.of("instrumentCode","ACOUSTIC_GUITAR")),200)).path("id").asText());
        String intake=sessions+"/"+session;
        call(a,get(intake+"/evidence"),200);
        call(a,post(intake+"/continue"),200);
        call(a,get(intake+"/exercise-recommendations"),200);
        var task=read(call(a,body(post(intake+"/tasks"),Map.of("exerciseCode","GM-EB01")),201));
        String taskPath=intake+"/tasks/"+task.path("id").asText();
        assertEquals(task,read(call(a,get(intake+"/tasks"),200)));
        call(b,get(taskPath+"/learning"),404);
        var initial=read(call(a,get(taskPath+"/learning"),200));
        assertEquals("ACTIVE",initial.path("taskState").asText());
        assertTrue(initial.path("availableActions").isEmpty());
        var file=new MockMultipartFile("file","synthetic.wav","audio/wav",wave());
        String uploadKey=UUID.randomUUID().toString();
        var uploaded=read(call(a,multipart(taskPath+"/attempts").file(file).param("requestId",uploadKey),201));
        var replay=read(call(a,multipart(taskPath+"/attempts").file(file).param("requestId",uploadKey),201));
        assertEquals(uploaded.path("id"),replay.path("id"));
        var page=read(call(a,get(taskPath+"/attempts/page?limit=20"),200));
        assertEquals(1,page.path("items").size());
        String decisionKey=UUID.randomUUID().toString();
        var decision=Map.of("requestId",decisionKey,"attemptId",uploaded.path("id").asText(),
            "action","COMPLETE","expectedVersion",initial.path("version").asLong(),"reason","I choose to continue");
        var completed=read(call(a,body(post(taskPath+"/decisions"),decision),201));
        assertEquals("COMPLETED_BY_LEARNER",completed.path("taskState").asText());
        assertEquals(completed.path("decisionId"),read(call(a,body(post(taskPath+"/decisions"),decision),200)).path("decisionId"));
        call(a,multipart(taskPath+"/attempts").file(file).param("requestId",UUID.randomUUID().toString()),409);
        assertEquals(0L,jdbc.sql("SELECT count(*) FROM learner_skill_signal_history WHERE source_type<>'SELF_REPORTED' "
            +"AND instrument_profile_id IN (SELECT id FROM instrument_profiles WHERE learner_id=:id)")
            .param("id",a.learner()).query(Long.class).single());
        var nextRequest=Map.of("requestId",UUID.randomUUID().toString(),
            "expectedVersion",completed.path("version").asLong(),"exerciseCode",task.path("exercise").path("code").asText(),
            "exerciseVersion",task.path("exercise").path("version").asText());
        var next=read(call(a,body(post(taskPath+"/next-lesson"),nextRequest),201));
        assertEquals(next.path("transitionId"),read(call(a,body(post(taskPath+"/next-lesson"),nextRequest),200)).path("transitionId"));
        assertEquals("ARCHIVED",read(call(a,get(intake),200)).path("status").asText());
        assertEquals(next.path("session").path("id"),read(call(a,get(sessions+"/current?instrumentCode=ACOUSTIC_GUITAR"),200)).path("id"));
        var history=read(call(a,get(a.base()+"/lessons?instrumentCode=ACOUSTIC_GUITAR&limit=1"),200));
        assertEquals(1,history.path("items").size());assertFalse(history.path("nextCursor").isNull());
        call(a,get(taskPath+"/decisions"),200);
        call(a,get(taskPath+"/learning/evidence"),200);
        assertEquals(JSON.valueToTree(background),read(call(a,get(bg),200)));
        System.out.println("GENERAL_QA_LEARNING_CHAIN PASS profile/onboarding/intake/assignment/upload/replay/choice/successor/history/ownership");
    }

    @Test void allPublishedCurrentExercisePreviewsAndVersionedResourcesResolve() throws Exception {
        var a=student();var catalog=read(call(a,get("/api/v1/exercises?instrumentCode=ACOUSTIC_GUITAR&techniqueCode=FINGERSTYLE"),200));
        assertTrue(catalog.isArray());assertFalse(catalog.isEmpty());int checked=0;
        var broken=new java.util.ArrayList<String>();
        for(var exercise:catalog) {
            String base="/api/v1/exercises/"+exercise.path("code").asText()+"/versions/"+exercise.path("version").asText();
            call(a,get(base),200);
            for(String field:List.of("previewPdfUrl","previewAudioUrl","scoreUrl","eventsUrl")) {
                if(!exercise.path(field).isTextual())continue;
                String url=exercise.path(field).asText();if(url.isBlank())continue;
                var result=mvc.perform(get(url).with(a.auth())).andReturn();
                int code=result.getResponse().getStatus();
                System.out.println("GENERAL_QA_RESOURCE "+url+" HTTP="+code);
                if(code!=200 || result.getResponse().getContentAsByteArray().length==0)broken.add(url+" HTTP="+code);
                checked++;
            }
        }
        assertTrue(checked>0);
        System.out.println("GENERAL_QA_CATALOG exercises="+catalog.size()+" resources="+checked+" broken="+broken);
        assertTrue(broken.isEmpty(),"Visible catalog has unavailable advertised resources: "+broken);
    }

    @Test void observeNewLearnerReadOnlyScreensAndNoPersistentSideEffects() throws Exception {
        var a=student();
        for(String path:List.of("/instruments/ACOUSTIC_GUITAR/background","/instruments/ACOUSTIC_GUITAR/skills",
                "/lessons?instrumentCode=ACOUSTIC_GUITAR","/diagnostic-sessions/current?instrumentCode=ACOUSTIC_GUITAR")) {
            var result=mvc.perform(get(a.base()+path).with(a.auth())).andReturn();
            assertTrue(result.getResponse().getStatus()<500,"Read-only initial screen must not throw 5xx");
            // Observation, not an acceptance assertion that 404 is correct. Known failures go in #237 report.
            System.out.println("GENERAL_QA_INITIAL_OBSERVATION "+path+" HTTP="+result.getResponse().getStatus());
        }
        assertEquals(0L,jdbc.sql("SELECT count(*) FROM instrument_profiles WHERE learner_id=:id")
            .param("id",a.learner()).query(Long.class).single());
    }
}
