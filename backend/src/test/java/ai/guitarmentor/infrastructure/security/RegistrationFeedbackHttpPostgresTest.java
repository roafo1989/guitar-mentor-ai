package ai.guitarmentor.infrastructure.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/** Owner-requested explicit signup feedback; synthetic identities and mail transport only. */
@SpringBootTest(properties={"guitar-mentor.security.enabled=true",
    "guitar-mentor.local-auth.public-origin=https://app.example.test",
    "guitar-mentor.local-auth.mail-from=no-reply@app.example.test"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="GUITAR_MENTOR_TEST_POSTGRES",matches="true")
class RegistrationFeedbackHttpPostgresTest {
    @Autowired MockMvc mvc;
    @Autowired LocalCredentialService local;
    @Autowired JdbcClient jdbc;
    @MockitoBean JavaMailSender mail;
    static final ObjectMapper JSON=new ObjectMapper();
    static final AtomicInteger REMOTE=new AtomicInteger();
    String login(){return "feedback"+UUID.randomUUID().toString().substring(0,8);}
    ResultActions signup(String email,String login) throws Exception {
        return mvc.perform(post("/api/v1/local-auth/register").with(csrf()).with(req->{
            req.setRemoteAddr("2001:db8::"+Integer.toHexString(REMOTE.incrementAndGet()));return req;
        }).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsBytes(Map.of(
            "email",email,"login",login,"password","123456","confirmPassword","123456"))));
    }
    List<String> tokens() {
        var sent=ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail,atLeastOnce()).send(sent.capture());
        return sent.getAllValues().stream().map(message->{
            var matcher=Pattern.compile("token=([a-f0-9]{64})").matcher(message.getText());
            assertTrue(matcher.find());return matcher.group(1);
        }).toList();
    }
    void verifyToken(String token) throws Exception {
        mvc.perform(post("/api/v1/local-auth/verify").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsBytes(Map.of("token",token))))
            .andExpect(status().isOk());
    }
    @Test @Transactional void activeAndPendingEmailAndLoginGetAccurateConflictWithoutSendingMail() throws Exception {
        String email=UUID.randomUUID()+"@example.test", name=login();
        signup(email,name).andExpect(status().isAccepted()).andExpect(jsonPath("$.code").value("VERIFY_EMAIL"));
        String token=tokens().getLast();clearInvocations(mail);
        signup(email.toUpperCase(java.util.Locale.ROOT),login()).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("EMAIL_VERIFICATION_PENDING"));
        signup(UUID.randomUUID()+"@example.test",name.toUpperCase(java.util.Locale.ROOT))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LOGIN_UNAVAILABLE"));
        assertEquals(1L,jdbc.sql("SELECT count(*) FROM pending_local_registrations WHERE email_normalized=:email")
            .param("email",email).query(Long.class).single());
        verifyToken(token);
        signup(email.toUpperCase(java.util.Locale.ROOT),login()).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("EMAIL_IN_USE")).andExpect(jsonPath("$.email").doesNotExist());
        signup(UUID.randomUUID()+"@example.test",name).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("LOGIN_UNAVAILABLE"));
        verifyNoInteractions(mail);
        assertEquals(1L,jdbc.sql("SELECT count(*) FROM local_credentials WHERE email_normalized=:email")
            .param("email",email).query(Long.class).single());
        assertEquals(0L,jdbc.sql("SELECT count(*) FROM pending_local_registrations WHERE email_normalized=:email")
            .param("email",email).query(Long.class).single());
    }
    @Test @Transactional void pendingPasswordLinkReservesEmailAndUsernameWithoutCreatingRegistration() throws Exception {
        UUID learner=UUID.randomUUID(),account=UUID.randomUUID();
        jdbc.sql("INSERT INTO learners(id,display_name,created_at) VALUES (:id,'QA feedback',now())").param("id",learner).update();
        jdbc.sql("INSERT INTO accounts(id,learner_id,role,status) VALUES (:id,:learner,'LEARNER','ACTIVE')")
            .param("id",account).param("learner",learner).update();
        String email=UUID.randomUUID()+"@example.test",name=login();
        local.beginLink(account,email,name,"123456");clearInvocations(mail);
        signup(email,login()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_IN_USE"));
        signup(UUID.randomUUID()+"@example.test",name).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("LOGIN_UNAVAILABLE"));
        verifyNoInteractions(mail);
        assertEquals(1L,jdbc.sql("SELECT count(*) FROM pending_local_links WHERE account_id=:id").param("id",account).query(Long.class).single());
        assertEquals(0L,jdbc.sql("SELECT count(*) FROM pending_local_registrations WHERE email_normalized=:email")
            .param("email",email).query(Long.class).single());
    }
    @Test @Transactional void expiredReservationCanBeRetriedAndOnlyLatestVerificationWorks() throws Exception {
        String email=UUID.randomUUID()+"@example.test",name=login();
        signup(email,name).andExpect(status().isAccepted());String old=tokens().getLast();
        jdbc.sql("UPDATE pending_local_registrations SET expires_at=now()-interval '1 minute' WHERE email_normalized=:email")
            .param("email",email).update();
        signup(email,name).andExpect(status().isAccepted());String latest=tokens().getLast();
        assertNotEquals(old,latest);verify(mail,times(2)).send(any(SimpleMailMessage.class));
        mvc.perform(post("/api/v1/local-auth/verify").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsBytes(Map.of("token",old)))).andExpect(status().isBadRequest());
        verifyToken(latest);
    }
    @Test void concurrentRegistrationsProduceOneMailAndPreciseConflict() throws Exception {
        race(true);race(false);
    }
    void race(boolean sameEmail) throws Exception {
        clearInvocations(mail);
        String suffix=UUID.randomUUID().toString().substring(0,8),name="race"+suffix;
        String email="race"+suffix+"@example.test",secondEmail=sameEmail?email:"other"+suffix+"@example.test";
        String secondLogin=sameEmail?"other"+suffix:name;
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        try {
            var first=executor.submit(()->registerAfterStart(email,name,ready,start));
            var second=executor.submit(()->registerAfterStart(secondEmail,secondLogin,ready,start));
            assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();
            var results=List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS));
            assertEquals(1,results.stream().filter("OK"::equals).count());
            assertTrue(results.contains(sameEmail?"EMAIL_VERIFICATION_PENDING":"LOGIN_UNAVAILABLE"));
            verify(mail,times(1)).send(any(SimpleMailMessage.class));
            assertEquals(1L,jdbc.sql("SELECT count(*) FROM pending_local_registrations WHERE email_normalized IN (:a,:b)")
                .param("a",email).param("b",secondEmail).query(Long.class).single());
        } finally {
            executor.shutdownNow();
            jdbc.sql("DELETE FROM pending_local_registrations WHERE email_normalized IN (:a,:b)")
                .param("a",email).param("b",secondEmail).update();
        }
    }
    String registerAfterStart(String email,String login,CountDownLatch ready,CountDownLatch start) throws Exception {
        ready.countDown();assertTrue(start.await(10,TimeUnit.SECONDS));
        try {local.registerPublic(email,login,"123456");return "OK";}
        catch(LocalCredentialService.RegistrationConflict conflict){return conflict.code();}
    }
}
