package ai.guitarmentor.infrastructure.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
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
    private final ActorFilter filter = new ActorFilter(repository);
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
    void disabledOrUnmappedIdentityDenied() throws Exception {
        signIn();
        when(repository.findActive("https://idp.example", "sub-A")).thenReturn(Optional.empty());
        assertEquals(403, request("/api/v1/me").getStatus());
    }

    @Test
    void nonApiResourcesAreNotInOwnershipFilter() throws Exception {
        assertEquals(200, request("/").getStatus());
    }

    private void signIn() {
        var token = new OidcIdToken("id-token", Instant.now().minusSeconds(30),
                Instant.now().plusSeconds(300), Map.of("iss", "https://idp.example",
                        "sub", "sub-A"));
        var principal = new DefaultOidcUser(List.of(), token);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a",
                        principal.getAuthorities()));
    }

    private MockHttpServletResponse request(String path) throws Exception {
        var request = new MockHttpServletRequest("GET", path);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
