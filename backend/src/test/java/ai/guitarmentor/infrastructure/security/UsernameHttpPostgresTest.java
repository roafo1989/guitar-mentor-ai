package ai.guitarmentor.infrastructure.security;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
@SpringBootTest(properties={"guitar-mentor.security.enabled=true","guitar-mentor.local-auth.public-origin=https://app.example.test","guitar-mentor.local-auth.mail-from=no-reply@app.example.test"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="GUITAR_MENTOR_TEST_POSTGRES",matches="true")
@Transactional
class UsernameHttpPostgresTest {
    @Autowired MockMvc mvc;
    @MockitoBean JavaMailSender mail;
    static final AtomicInteger serial=new AtomicInteger();
    final ObjectMapper json=new ObjectMapper();
    RequestPostProcessor ip(){String ip="192.0.2."+serial.incrementAndGet();return req->{req.setRemoteAddr(ip);return req;};}
    record Signed(MockHttpSession session,String login,String email,RequestPostProcessor ip){}
    Signed signup() throws Exception {return signup("123456");}
    Signed signup(String password) throws Exception {
        String login="http"+UUID.randomUUID().toString().substring(0,8), email=login+"@example.test";var ip=ip();
        mvc.perform(post("/api/v1/local-auth/register").with(csrf()).with(ip).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("email",email,"login",login,"password",password,"confirmPassword",password)))).andExpect(status().isAccepted());
        var capture=ArgumentCaptor.forClass(SimpleMailMessage.class);verify(mail,atLeastOnce()).send(capture.capture());
        var matcher=Pattern.compile("token=([a-f0-9]{64})").matcher(capture.getAllValues().getLast().getText());assertTrue(matcher.find());
        mvc.perform(post("/api/v1/local-auth/verify").with(csrf()).with(ip).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("token",matcher.group(1))))).andExpect(status().isOk());
        var loginResult=mvc.perform(post("/api/v1/local-auth/login").with(csrf()).with(ip).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("identifier",login,"password",password)))).andExpect(status().isOk()).andReturn();
        return new Signed((MockHttpSession)loginResult.getRequest().getSession(false),login,email,ip);
    }
    @Test void csrfOwnSessionUnicodeLoginAndEmailFallback() throws Exception {
        var s=signup();String newName="Роман"+UUID.randomUUID().toString().substring(0,8);
        var before=mvc.perform(get("/api/v1/me").session(s.session())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String body=json.writeValueAsString(Map.of("newLogin",newName,"currentPassword","123456"));
        mvc.perform(get("/api/v1/local-auth/username").session(s.session())).andExpect(status().isOk()).andExpect(jsonPath("$.login").value(s.login())).andExpect(jsonPath("$.password").doesNotExist());
        mvc.perform(post("/api/v1/local-auth/username").session(s.session()).with(s.ip()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/local-auth/username").session(s.session()).with(s.ip()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.login").value(newName.toLowerCase(java.util.Locale.ROOT)));
        assertEquals(before,mvc.perform(get("/api/v1/me").session(s.session())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        mvc.perform(post("/api/v1/local-auth/login").with(s.ip()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("identifier",s.login(),"password","123456")))).andExpect(status().isUnauthorized());
        for(String identifier:List.of(newName.toUpperCase(java.util.Locale.ROOT),s.email()))mvc.perform(post("/api/v1/local-auth/login").with(s.ip()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("identifier",identifier,"password","123456")))).andExpect(status().isOk());
    }
    @Test void otherAccountPasswordCannotAuthorizeChangeAndConflictsAreDistinct() throws Exception {
        var first=signup();var other=signup("abcdef");
        mvc.perform(post("/api/v1/local-auth/username").session(first.session()).with(first.ip()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("newLogin",other.login(),"currentPassword","123456")))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LOGIN_UNAVAILABLE"));
        mvc.perform(post("/api/v1/local-auth/username").session(first.session()).with(first.ip()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("newLogin","free"+UUID.randomUUID().toString().substring(0,8),"currentPassword","abcdef")))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/local-auth/username").session(other.session())).andExpect(jsonPath("$.login").value(other.login()));
        mvc.perform(get("/api/v1/local-auth/username")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/local-auth/username").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
    }
    @Test void usernameChangeRemainsRateLimited() throws Exception {
        var s=signup();String body=json.writeValueAsString(Map.of("newLogin","some-new-name","currentPassword","wrong!"));
        for(int i=0;i<8;i++)mvc.perform(post("/api/v1/local-auth/username").session(s.session()).with(s.ip()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/local-auth/username").session(s.session()).with(s.ip()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isTooManyRequests());
    }
}
