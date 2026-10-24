package ai.guitarmentor.infrastructure.security;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Full Spring Security filter chain + MVC with synthetic signed-in actors.
 * OIDC protocol negotiation itself requires an external QA IdP and is NOT tested here.
 */
@SpringBootTest(properties = {
    "guitar-mentor.security.enabled=true",
    "guitar-mentor.security.absolute-session-minutes=720"
})
@AutoConfigureMockMvc
@Import(SecurityHttpIntegrationTest.FakeOidcRegistration.class)
class SecurityHttpIntegrationTest {
    @Autowired MockMvc mvc;
    @MockitoBean ActorRepository actors;

    private final UUID learnerA = UUID.randomUUID();
    private final UUID learnerB = UUID.randomUUID();

    @Test
    void anonymousAccessToPrivateResourcesIsRejected() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/learners/" + learnerA)).andExpect(status().isUnauthorized());
        mvc.perform(head("/api/v1/learners/" + learnerA
                + "/diagnostic-sessions/" + UUID.randomUUID()
                + "/tasks/" + UUID.randomUUID() + "/attempts"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void oidcActorCannotReadOrWriteAnotherLearnersResources() throws Exception {
        activeA();
        var principal = oidcLogin().idToken(t -> t.claim("iss", "https://issuer.example").subject("user-a"));
        String base = "/api/v1/learners/" + learnerB;
        mvc.perform(get(base)).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
            .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get(base).with(principal)).andExpect(status().isNotFound());
        mvc.perform(get(base + "/diagnostic-sessions/" + UUID.randomUUID()
                + "/tasks/" + UUID.randomUUID() + "/attempts/page").with(principal))
            .andExpect(status().isNotFound());
        mvc.perform(post(base + "/diagnostic-sessions").with(principal).with(csrf()))
            .andExpect(status().isNotFound());
    }

    @Test
    void knownActorIsResolvedForMeButUnknownActorFailsClosed() throws Exception {
        activeA();
        var principal = oidcLogin().idToken(t -> t.claim("iss", "https://issuer.example").subject("user-a"));
        mvc.perform(get("/api/v1/me").with(principal)).andExpect(status().isOk())
            .andExpect(jsonPath("$.learnerId").value(learnerA.toString()));
        mvc.perform(get("/api/v1/csrf").with(principal)).andExpect(status().isOk())
            .andExpect(jsonPath("$.headerName").isString());
        var unknown = oidcLogin().idToken(t -> t.claim("iss", "https://issuer.example").subject("unknown"));
        mvc.perform(get("/api/v1/me").with(unknown)).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ADMITTED"));
    }

    @Test
    void csrfIsRequiredEvenForKnownActorAndLogout() throws Exception {
        activeA();
        var principal = oidcLogin().idToken(t -> t.claim("iss", "https://issuer.example").subject("user-a"));
        mvc.perform(post("/api/v1/learners").with(principal)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/learners").with(principal).with(csrf()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("INVITATION_REQUIRED"));
        mvc.perform(post("/logout").with(principal)).andExpect(status().isForbidden());
        mvc.perform(post("/logout").with(principal).with(csrf()))
            .andExpect(status().isNoContent());
    }

    @Test
    void actuatorNeverExposesHealthThroughApplicationApi() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isForbidden());
    }

    private void activeA() {
        when(actors.findActive("https://issuer.example", "user-a"))
            .thenReturn(Optional.of(new CurrentActor(UUID.randomUUID(), learnerA, "LEARNER")));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeOidcRegistration {
        @Bean
        ClientRegistrationRepository clientRegistrationRepository() {
            ClientRegistration registration = ClientRegistration.withRegistrationId("guitar-mentor")
                .clientId("integration-test")
                .clientSecret("test-only")
                .clientName("synthetic-oidc")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid")
                .authorizationUri("https://issuer.example/authorize")
                .tokenUri("https://issuer.example/token")
                .jwkSetUri("https://issuer.example/keys")
                .issuerUri("https://issuer.example")
                .build();
            return new InMemoryClientRegistrationRepository(registration);
        }
    }
}
