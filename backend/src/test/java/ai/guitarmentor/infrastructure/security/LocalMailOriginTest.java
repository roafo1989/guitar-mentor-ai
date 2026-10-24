package ai.guitarmentor.infrastructure.security;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class LocalMailOriginTest {
    @Test
    void httpsOriginAllowedByDefault() {
        assertTrue(LocalCredentialService.allowedMailOrigin("https://guitar-mentor",false,"127.0.0.1"));
        assertTrue(LocalCredentialService.allowedMailOrigin("https://example.test:8443",false,"0.0.0.0"));
    }

    @Test
    void localhostEmailLinkRequiresExplicitSecuredLoopbackQaMode() {
        assertTrue(LocalCredentialService.allowedMailOrigin("http://localhost:5173",true,"127.0.0.1"));
        assertTrue(LocalCredentialService.allowedMailOrigin("http://localhost:5173",true,"::1"));
        assertFalse(LocalCredentialService.allowedMailOrigin("http://localhost:5173",false,"127.0.0.1"));
        assertFalse(LocalCredentialService.allowedMailOrigin("http://localhost:5173",true,"0.0.0.0"));
        assertFalse(LocalCredentialService.allowedMailOrigin("http://127.0.0.1:5173",true,"127.0.0.1"));
        assertFalse(LocalCredentialService.allowedMailOrigin("http://localhost:8080",true,"127.0.0.1"));
        assertFalse(LocalCredentialService.allowedMailOrigin("http://guitar-mentor:5173",true,"127.0.0.1"));
        assertFalse(LocalCredentialService.allowedMailOrigin("http://localhost:5173",true,"192.168.1.10"));
        assertFalse(LocalCredentialService.allowedMailOrigin("https://app.example.test/path",true,"127.0.0.1"));
    }
}
