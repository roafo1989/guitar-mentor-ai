package ai.guitarmentor.infrastructure.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/** Real PostgreSQL + MVC + security; Google principal and SMTP transport are synthetic. */
@SpringBootTest(properties = {
    "guitar-mentor.security.enabled=true",
    "guitar-mentor.local-auth.public-origin=https://app.example.test",
    "guitar-mentor.local-auth.mail-from=no-reply@app.example.test"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "GUITAR_MENTOR_TEST_POSTGRES", matches = "true")
class InitialBackgroundHttpPostgresTest {
    @Autowired MockMvc mvc;
    @Autowired InvitationService admissions;
    @Autowired JdbcClient jdbc;
    @MockitoBean JavaMailSender mail;
    final ObjectMapper json = new ObjectMapper();
    static final String BODY = """
        {"experienceLevel":"BEGINNER","yearsPlaying":1,"skills":["FINGERSTYLE_MELODY"],
        "knownPieces":[{"title":"Yesterday","artist":"The Beatles"}],
        "goals":["FINGERSTYLE"],"practiceMinutes":30,"sessionsPerWeek":3,"notes":"keep me"}
        """;
    static final String PREFERENCES = """
        {"instrumentCode":"ACOUSTIC_GUITAR","preferences":[{"styleCode":"BLUES","rating":"LIKE"}]}
        """;
    record Student(UUID learner, UUID account, RequestPostProcessor auth) {
        String path() { return "/api/v1/learners/" + learner; }
        String background() { return path() + "/instruments/ACOUSTIC_GUITAR/background"; }
    }

    Student google() {
        String suffix = UUID.randomUUID().toString();
        var identity = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")),
            new OidcIdToken("synthetic", Instant.now().minusSeconds(10), Instant.now().plusSeconds(300),
                Map.of("iss", InvitationService.GOOGLE_ISSUER, "sub", suffix,
                    "email", suffix + "@example.test", "email_verified", true, "name", "QA235")));
        UUID account = admissions.admitPublicGoogle(identity);
        UUID learner = jdbc.sql("SELECT learner_id FROM accounts WHERE id=:id")
            .param("id", account).query(UUID.class).single();
        return new Student(learner, account, req -> {
            req = oidcLogin().oidcUser(identity).postProcessRequest(req);
            req.getSession().setAttribute("guitarMentorAccessVersion", 0L);
            return req;
        });
    }

    @Test @Transactional
    void publicGoogleBareLearnerCanOpenSaveAndReloadWithoutPreferencesSave() throws Exception {
        var student = google();
        emptyThenSaveAndReload(student);
    }

    @Test @Transactional
    void publicLocalRegistrationAndLoginCanOpenSaveAndReloadWithoutPreferencesSave() throws Exception {
        String login = "qa235" + UUID.randomUUID().toString().substring(0, 8);
        String signup = json.writeValueAsString(Map.of("email", login + "@example.test",
            "login", login, "password", "safe-password-999", "confirmPassword", "safe-password-999"));
        mvc.perform(post("/api/v1/local-auth/register").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(signup)).andExpect(status().isAccepted());
        var sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail, atLeastOnce()).send(sent.capture());
        var token = Pattern.compile("token=([a-f0-9]{64})").matcher(sent.getAllValues().getLast().getText());
        assertTrue(token.find());
        mvc.perform(post("/api/v1/local-auth/verify").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("token", token.group(1))))).andExpect(status().isOk());
        var signed = mvc.perform(post("/api/v1/local-auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("identifier", login, "password", "safe-password-999"))))
            .andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) signed.getRequest().getSession(false);
        assertNotNull(session);
        UUID account = jdbc.sql("SELECT account_id FROM local_credentials WHERE login_normalized=:login")
            .param("login", login).query(UUID.class).single();
        UUID learner = jdbc.sql("SELECT learner_id FROM accounts WHERE id=:id")
            .param("id", account).query(UUID.class).single();
        emptyThenSaveAndReload(new Student(learner, account, req -> { req.setSession(session); return req; }));
    }

    void emptyThenSaveAndReload(Student student) throws Exception {
        mvc.perform(get(student.path()).with(student.auth())).andExpect(status().isOk())
            .andExpect(jsonPath("$.instruments").isEmpty());
        assertEquals(0, profiles(student));
        mvc.perform(get(student.background()).with(student.auth())).andExpect(status().isNoContent());
        assertEquals(0, profiles(student), "GET must not initialize persisted data");
        mvc.perform(put(student.background()).with(student.auth()).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isOk());
        assertEquals(1, profiles(student));
        mvc.perform(get(student.background()).with(student.auth())).andExpect(status().isOk())
            .andExpect(jsonPath("$.notes").value("keep me"))
            .andExpect(jsonPath("$.knownPieces[0].title").value("Yesterday"));
        mvc.perform(get(student.path()).with(student.auth())).andExpect(status().isOk())
            .andExpect(jsonPath("$.instruments[0].techniqueCode").value("FINGERSTYLE"));
    }

    @Test @Transactional
    void newLearnerCanStartDiagnosticIntakeBeforeAnyOtherSave() throws Exception {
        var student = google();
        mvc.perform(get(student.path() + "/diagnostic-sessions/current?instrumentCode=ACOUSTIC_GUITAR")
            .with(student.auth())).andExpect(status().isNoContent());
        assertEquals(0, profiles(student));
        var first = mvc.perform(post(student.path() + "/diagnostic-sessions").with(student.auth()).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"instrumentCode\":\"ACOUSTIC_GUITAR\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("COLLECTING_EVIDENCE"))
            .andReturn().getResponse().getContentAsString();
        var again = mvc.perform(post(student.path() + "/diagnostic-sessions").with(student.auth()).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"instrumentCode\":\"ACOUSTIC_GUITAR\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(json.readTree(first).get("id"), json.readTree(again).get("id"));
        assertEquals(1, profiles(student));
        mvc.perform(get(student.background()).with(student.auth())).andExpect(status().isNoContent());
        mvc.perform(put(student.background()).with(student.auth()).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isOk());
        assertEquals(1, profiles(student));
    }

    @Test @Transactional
    void protectedResourcesAndInvalidWritesNeverInitializeEmptyProfiles() throws Exception {
        var owner = google(); var other = google();
        mvc.perform(get(owner.background())).andExpect(status().isUnauthorized());
        mvc.perform(get(owner.background()).with(other.auth())).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(put(owner.background()).with(other.auth()).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isNotFound());
        mvc.perform(get(owner.background()).with(oidcLogin().idToken(t ->
            t.claim("iss", InvitationService.GOOGLE_ISSUER).subject("unadmitted"))))
            .andExpect(status().isForbidden());
        mvc.perform(put(owner.background()).with(owner.auth())
            .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(put(owner.background()).with(owner.auth()).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("\"practiceMinutes\":30", "\"practiceMinutes\":0")))
            .andExpect(status().isBadRequest());
        String unsupported = owner.background().replace("ACOUSTIC_GUITAR", "TYPO");
        mvc.perform(get(unsupported).with(owner.auth())).andExpect(status().isNotFound());
        mvc.perform(put(unsupported).with(owner.auth()).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isNotFound());
        assertEquals(0, profiles(owner)); assertEquals(0, profiles(other));
    }

    @Test @Transactional
    void preferencesAndRepeatedReadsPreserveExistingBackgroundAndSkillHistory() throws Exception {
        var student = google(); emptyThenSaveAndReload(student);
        String before = mvc.perform(get(student.background()).with(student.auth()))
            .andReturn().getResponse().getContentAsString();
        var history = jdbc.sql("SELECT * FROM learner_skill_signal_history WHERE instrument_profile_id IN "
            + "(SELECT id FROM instrument_profiles WHERE learner_id=:id)")
            .param("id", student.learner()).query().listOfRows();
        assertEquals(1, history.size());
        mvc.perform(put(student.path() + "/preferences").with(student.auth()).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(PREFERENCES)).andExpect(status().isOk());
        mvc.perform(put(student.background()).with(student.auth()).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isOk());
        assertEquals(json.readTree(before), json.readTree(mvc.perform(get(student.background()).with(student.auth()))
            .andReturn().getResponse().getContentAsString()));
        assertEquals(history, jdbc.sql("SELECT * FROM learner_skill_signal_history WHERE instrument_profile_id IN "
            + "(SELECT id FROM instrument_profiles WHERE learner_id=:id)")
            .param("id", student.learner()).query().listOfRows());
        assertEquals(1, profiles(student));
    }

    @Test void concurrentFirstBackgroundWritesCreateOneInstrumentAndOneClaim() throws Exception { race(false); }
    @Test void concurrentFirstBackgroundAndPreferencesPreserveBoth() throws Exception { race(true); }

    void race(boolean preferences) throws Exception {
        var student = google();
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> writeAfterStart(student, false, ready, start));
            var second = pool.submit(() -> writeAfterStart(student, preferences, ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            assertEquals(200, first.get(30, TimeUnit.SECONDS));
            assertEquals(200, second.get(30, TimeUnit.SECONDS));
            assertEquals(1, profiles(student));
            assertEquals(1L, jdbc.sql("SELECT count(*) FROM learning_backgrounds WHERE instrument_profile_id IN "
                + "(SELECT id FROM instrument_profiles WHERE learner_id=:id)")
                .param("id", student.learner()).query(Long.class).single());
            assertEquals(1L, jdbc.sql("SELECT count(*) FROM learner_skill_signal_history WHERE instrument_profile_id IN "
                + "(SELECT id FROM instrument_profiles WHERE learner_id=:id)")
                .param("id", student.learner()).query(Long.class).single());
            mvc.perform(get(student.background()).with(student.auth())).andExpect(status().isOk())
                .andExpect(jsonPath("$.notes").value("keep me"));
            if (preferences) mvc.perform(get(student.path()).with(student.auth())).andExpect(status().isOk())
                .andExpect(jsonPath("$.instruments[0].preferences[0].styleCode").value("BLUES"));
        } finally {
            start.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(35, TimeUnit.SECONDS));
            jdbc.sql("DELETE FROM account_identities WHERE account_id=:id").param("id", student.account()).update();
            jdbc.sql("DELETE FROM accounts WHERE id=:id").param("id", student.account()).update();
            jdbc.sql("DELETE FROM learners WHERE id=:id").param("id", student.learner()).update();
        }
    }

    int writeAfterStart(Student student, boolean preferences, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timed out");
        return mvc.perform(put(preferences ? student.path() + "/preferences" : student.background())
            .with(student.auth()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(preferences ? PREFERENCES : BODY)).andReturn().getResponse().getStatus();
    }

    long profiles(Student student) {
        return jdbc.sql("SELECT count(*) FROM instrument_profiles WHERE learner_id=:id")
            .param("id", student.learner()).query(Long.class).single();
    }
}
