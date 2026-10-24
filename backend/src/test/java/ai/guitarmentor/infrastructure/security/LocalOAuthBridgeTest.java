package ai.guitarmentor.infrastructure.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

class LocalOAuthBridgeTest {
    @Test
    void validatedOidcCanBeConsumedOnlyOnceByOriginalSession() {
        var bridge = new LocalOAuthBridge();
        var oidc = mock(OidcUser.class);
        var auth = new UsernamePasswordAuthenticationToken(oidc, null, List.of());
        String start = bridge.begin("https-session-a", false);
        assertTrue(bridge.validStart(start));
        assertFalse(bridge.linking(start));
        String ticket = bridge.complete(start, auth);
        assertFalse(bridge.validStart(start));
        assertThrows(SecurityException.class, () -> bridge.complete(start, auth));
        assertSame(oidc, bridge.consume(ticket, "https-session-a"));
        assertThrows(SecurityException.class, () -> bridge.consume(ticket, "https-session-a"));
    }

    @Test
    void wrongSessionBurnsTicketAndCannotReplayOnCorrectOne() {
        var bridge = new LocalOAuthBridge();
        var oidc = mock(OidcUser.class);
        var auth = new UsernamePasswordAuthenticationToken(oidc, null, List.of());
        String start = bridge.begin("original-https-session", false);
        String ticket = bridge.complete(start, auth);
        assertThrows(SecurityException.class, () -> bridge.consume(ticket, "another-browser"));
        assertThrows(SecurityException.class, () -> bridge.consume(ticket, "original-https-session"));
    }

    @Test
    void linkIntentRequiresExplicitOriginalSessionAndValidatedOidc() {
        var bridge = new LocalOAuthBridge();
        String start = bridge.begin("password-reauthed-session", true);
        assertTrue(bridge.linking(start));
        var oidc = mock(OidcUser.class);
        var auth = new UsernamePasswordAuthenticationToken(oidc, null, List.of());
        var ticket = bridge.complete(start, auth);
        assertSame(oidc, bridge.consume(ticket, "password-reauthed-session"));
        String unvalidated = bridge.begin("session", false);
        assertThrows(SecurityException.class, () -> bridge.complete(unvalidated,
                new UsernamePasswordAuthenticationToken("untrusted-email", null, List.of())));
    }

    @Test
    void cannotStartWithoutSessionOrUseMalformedTokens() {
        var bridge = new LocalOAuthBridge();
        assertThrows(SecurityException.class, () -> bridge.begin("", false));
        assertFalse(bridge.validStart("bad"));
        assertThrows(SecurityException.class, () -> bridge.consume("bad", "session"));
    }
}
