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
}
