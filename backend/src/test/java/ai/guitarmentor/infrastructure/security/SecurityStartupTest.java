package ai.guitarmentor.infrastructure.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SecurityStartupTest {
    private final SecurityConfiguration configuration = new SecurityConfiguration();

    @Test
    void localTestBypassRejectsExternalBindings() {
        for (String address : new String[] {"0.0.0.0", "::", "192.168.1.10"}) {
            assertThrows(IllegalStateException.class,
                    () -> configuration.rejectInsecureNonLoopback(false, address).run(null));
        }
    }

    @Test
    void localTestBypassPermittedOnlyForExplicitLoopback() {
        for (String address : new String[] {"127.0.0.1", "::1", "localhost"}) {
            assertDoesNotThrow(() ->
                    configuration.rejectInsecureNonLoopback(false, address).run(null));
        }
    }

    @Test
    void smtpCaptureQaAcceptsOnlyProtectedLoopbackSettings() {
        assertDoesNotThrow(() -> configuration.restrictLocalMailQa(true,
                "127.0.0.1","127.0.0.1",1025,"https://guitar-mentor",true,false).run(null));
        assertThrows(IllegalStateException.class, () ->
                configuration.restrictLocalMailQa(true,
                    "0.0.0.0","127.0.0.1",1025,"https://guitar-mentor",true,false).run(null));
        assertThrows(IllegalStateException.class, () ->
                configuration.restrictLocalMailQa(true,
                    "127.0.0.1","smtp.example.com",587,"https://guitar-mentor",true,false).run(null));
        assertThrows(IllegalStateException.class, () ->
                configuration.restrictLocalMailQa(true,
                    "127.0.0.1","127.0.0.1",1025,"http://guitar-mentor",true,false).run(null));
        assertThrows(IllegalStateException.class, () ->
                configuration.restrictLocalMailQa(true,
                    "127.0.0.1","127.0.0.1",1025,"https://guitar-mentor",false,false).run(null));
    }
}
