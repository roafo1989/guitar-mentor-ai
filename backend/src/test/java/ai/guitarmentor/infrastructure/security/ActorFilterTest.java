package ai.guitarmentor.infrastructure.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

class ActorFilterTest {
    private final ActorRepository repository = mock(ActorRepository.class);
    private final ActorFilter filter = new ActorFilter(repository, java.time.Duration.ofHours(12));
    private final UUID owner = UUID.randomUUID();
    private final UUID foreign = UUID.randomUUID();

    @AfterEach
    void cleanup() { SecurityContextHolder.clearContext(); }

    @Test
    void anonymousCannotReadPersonalData() throws Exception {
        assertEquals(401, request("/api/v1/learners/" + owner).getStatus());
    }

    @Test
    void authenticatedLearnerCannotReadForeignLearner() throws Exception {
        signIn();
        when(repository.findActive("https://idp.example", "sub-A"))
                .thenReturn(Optional.of(new CurrentActor(UUID.randomUUID(), owner, "LEARNER")));
        assertEquals(404, request("/api/v1/learners/" + foreign).getStatus());
        assertEquals(200, request("/api/v1/learners/" + owner).getStatus());
        assertEquals(403, request("/api/v1/learners").getStatus());
    }

    @Test
    void nestedLearnerEndpointsDenyForeignIdsAcrossMethods() throws Exception {
        signIn();
        when(repository.findActive("https://idp.example", "sub-A"))
                .thenReturn(Optional.of(new CurrentActor(UUID.randomUUID(), owner, "LEARNER")));
        String[] paths = {
            "/instruments/GUITAR/background",
            "/instruments/GUITAR/skills/history",
            "/diagnostic-sessions/" + UUID.randomUUID() + "/continue",
            "/diagnostic-sessions/" + UUID.randomUUID() + "/evidence/" + UUID.randomUUID(),
            "/diagnostic-sessions/" + UUID.randomUUID() + "/tasks/" + UUID.randomUUID() + "/attempts",
            "/diagnostic-sessions/" + UUID.randomUUID() + "/tasks/" + UUID.randomUUID() + "/attempts/" + UUID.randomUUID() + "/assessments",
            "/diagnostic-sessions/" + UUID.randomUUID() + "/tasks/" + UUID.randomUUID() + "/decisions"
        };
        for (String suffix : paths) {
            for (String method : new String[] { "GET", "HEAD", "POST", "PUT" }) {
                assertEquals(404, request(method, "/api/v1/learners/" + foreign + suffix).getStatus(),
                    method + " " + suffix);
            }
        }
    }

    @Test
    void disabledOrUnmappedIdentityDenied() throws Exception {
        signIn();
        when(repository.findActive("https://idp.example", "sub-A")).thenReturn(Optional.empty());
        assertEquals(403, request("/api/v1/me").getStatus());
    }

    @Test
    void expiredAbsoluteSessionIsInvalidatedBeforeAnyApiRead() throws Exception {
        HttpSession session = mock(HttpSession.class);
        when(session.getCreationTime()).thenReturn(System.currentTimeMillis() - 13L * 60 * 60 * 1000);
        var request = spy(new MockHttpServletRequest("GET", "/api/v1/me"));
        doReturn(session).when(request).getSession(false);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(401, response.getStatus());
        assertEquals("{\\"code\\":\\"SESSION_EXPIRED\\"}", response.getContentAsString());
        verify(session).invalidate();
    }

    @Test
    void nonApiResourcesAreNotInOwnershipFilter() throws Exception {
        assertEquals(200, request("/").getStatus());
    }

    private void signIn() {
        var token = new OidcIdToken("id-token", Instant.now().minusSeconds(30),
                Instant.now().plusSeconds(300), Map.of("iss", "https://idp.example",
                        "sub", "sub-A"));
        var principal = new DefaultOidcUser(List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")), token);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a",
                        principal.getAuthorities()));
    }

    private MockHttpServletResponse request(String path) throws Exception {
        return request("GET", path);
    }

    private MockHttpServletResponse request(String method, String path) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
