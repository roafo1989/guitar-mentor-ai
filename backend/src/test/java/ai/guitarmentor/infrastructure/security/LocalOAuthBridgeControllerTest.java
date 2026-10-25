package ai.guitarmentor.infrastructure.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

class LocalOAuthBridgeControllerTest {
    private MockHttpServletRequest request() {
        var req = new MockHttpServletRequest();
        req.setScheme("https");
        req.setServerName("guitar-mentor");
        req.addHeader("Origin", "https://guitar-mentor");
        return req;
    }

    @Test
    void onlyShortHttpsOriginMayStartAFlow() {
        var bridge = new LocalOAuthBridge();
        var controller = new LocalOAuthBridgeController(bridge, mock(ActorRepository.class),
                mock(GoogleLinkService.class), true, "https://guitar-mentor");
        var req = new MockHttpServletRequest();
        req.addHeader("Origin", "http://localhost:5173");
        assertEquals(403, controller.start(req).getStatusCode().value());
        assertNull(req.getSession(false), "Invalid origin must not allocate a session");
        req = request();
        var start = controller.start(req);
        assertEquals(200, start.getStatusCode().value());
        assertNotNull(req.getSession(false));
        assertTrue(start.getBody().get("beginUrl").startsWith("http://localhost:5173/oauth-bridge/begin?nonce="));
    }

    @Test
    void devOriginIsAllowedButOldOriginIsRejected() {
        var bridge = new LocalOAuthBridge();
        var controller = new LocalOAuthBridgeController(bridge, mock(ActorRepository.class),
                mock(GoogleLinkService.class), true, "https://guitar-mentor-dev");
        var req = request();
        assertEquals(403, controller.start(req).getStatusCode().value());
        req.setServerName("guitar-mentor-dev");
        req.removeHeader("Origin");
        req.addHeader("Origin", "https://guitar-mentor-dev");
        assertEquals(200, controller.start(req).getStatusCode().value());
    }

    @Test
    void completedGoogleLoginRotatesSessionAndSavesOnlyServerVerifiedIdentity() throws Exception {
        var bridge = new LocalOAuthBridge();
        var actors = mock(ActorRepository.class);
        var controller = new LocalOAuthBridgeController(bridge, actors, mock(GoogleLinkService.class), true, "https://guitar-mentor");
        var req = request();
        String oldId = req.getSession(true).getId();
        var oidc = mock(OidcUser.class);
        when(oidc.getIssuer()).thenReturn(URI.create("https://accounts.google.com").toURL());
        when(oidc.getSubject()).thenReturn("google-user-sub");
        UUID account = UUID.randomUUID();
        when(actors.findActive("https://accounts.google.com", "google-user-sub"))
            .thenReturn(Optional.of(new CurrentActor(account, UUID.randomUUID(), "LEARNER")));
        when(actors.localVersion(account)).thenReturn(Optional.of(5L));
        String nonce = bridge.begin(oldId, false);
        String ticket = bridge.complete(nonce, new UsernamePasswordAuthenticationToken(oidc,null,List.of()));
        try {
            var res = controller.consume(new LocalOAuthBridgeController.Redeem(ticket),
                    req, new MockHttpServletResponse());
            assertEquals(200,res.getStatusCode().value());
            assertNotEquals(oldId, req.getSession().getId());
            assertSame(oidc, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
            assertEquals(5L,req.getSession().getAttribute("guitarMentorAccessVersion"));
            assertEquals(403,controller.consume(new LocalOAuthBridgeController.Redeem(ticket),
                    req, new MockHttpServletResponse()).getStatusCode().value());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void stolenTicketCannotLogInDifferentHttpsSession() {
        var bridge = new LocalOAuthBridge();
        var controller = new LocalOAuthBridgeController(bridge, mock(ActorRepository.class),
                mock(GoogleLinkService.class), true, "https://guitar-mentor");
        var validSession = request();
        String nonce = bridge.begin(validSession.getSession(true).getId(),false);
        var ticket = bridge.complete(nonce,
                new UsernamePasswordAuthenticationToken(mock(OidcUser.class),null,List.of()));
        var attacker = request();
        attacker.getSession(true);
        assertEquals(403,controller.consume(new LocalOAuthBridgeController.Redeem(ticket),
                attacker, new MockHttpServletResponse()).getStatusCode().value());
        assertEquals(403,controller.consume(new LocalOAuthBridgeController.Redeem(ticket),
                validSession, new MockHttpServletResponse()).getStatusCode().value());
    }
}
