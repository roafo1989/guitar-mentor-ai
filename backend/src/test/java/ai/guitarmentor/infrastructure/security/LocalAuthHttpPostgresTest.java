package ai.guitarmentor.infrastructure.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** Local-only secure HTTP flow, with no Google IdP or live SMTP service. */
@SpringBootTest(properties={
    "guitar-mentor.security.enabled=true",
    "guitar-mentor.local-auth.public-origin=https://app.example.test",
    "guitar-mentor.local-auth.mail-from=no-reply@app.example.test"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="GUITAR_MENTOR_TEST_POSTGRES",matches="true")
@Transactional
class LocalAuthHttpPostgresTest {
    @Autowired MockMvc mvc;
    @Autowired InvitationService invitations;
    @MockitoBean JavaMailSender mail;

    @Test
    void localRegistrationVerificationAndSessionAccessRequireCsrf() throws Exception {
        String email=UUID.randomUUID()+"@example.test";
        String login="test"+UUID.randomUUID().toString().substring(0,10);
        String register="{\"email\":\""+email
            +"\",\"login\":\""+login+"\",\"password\":\"safe-password-999\","
            +"\"confirmPassword\":\"safe-password-999\"}";
        mvc.perform(post("/api/v1/local-auth/register")
            .contentType(MediaType.APPLICATION_JSON).content(register))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/local-auth/register").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(register))
            .andExpect(status().isAccepted());
        assertFalse(register.contains("invitation"),"Public signup must not require a code");
        // #237 owner decision: pending verification is explicit, never pretend to send another email.
        mvc.perform(post("/api/v1/local-auth/register").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(register))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("EMAIL_VERIFICATION_PENDING"));
        verify(mail,times(1)).send(any(SimpleMailMessage.class));
        var captor=ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail,atLeastOnce()).send(captor.capture());
        var match=Pattern.compile("token=([a-f0-9]{64})")
            .matcher(captor.getAllValues().getLast().getText());
        assertTrue(match.find());
        String verifyBody="{\"token\":\""+match.group(1)+"\"}";
        mvc.perform(post("/api/v1/local-auth/verify").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(verifyBody))
            .andExpect(status().isOk());
        String loginBody="{\"identifier\":\""+login+"\",\"password\":\"safe-password-999\"}";
        var signedIn=mvc.perform(post("/api/v1/local-auth/login").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(loginBody))
            .andExpect(status().isOk()).andReturn();
        var session=(MockHttpSession)signedIn.getRequest().getSession(false);
        assertNotNull(session);
        mvc.perform(get("/api/v1/me").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("LEARNER"));
    }
}
