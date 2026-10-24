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
    private org.springframework.mock.env.MockEnvironment unifiedEnv() {
        return new org.springframework.mock.env.MockEnvironment()
            .withProperty("guitar-mentor.local-auth.unified-local","true")
            .withProperty("server.address","127.0.0.1")
            .withProperty("guitar-mentor.local-auth.public-origin","https://guitar-mentor")
            .withProperty("guitar-mentor.security.enabled","true")
            .withProperty("guitar-mentor.security.local-http","false")
            .withProperty("spring.security.oauth2.client.registration.guitar-mentor.client-id","example-client")
            .withProperty("spring.security.oauth2.client.registration.guitar-mentor.client-secret","example-secret")
            .withProperty("spring.security.oauth2.client.registration.guitar-mentor.redirect-uri",
                "http://localhost:5173/login/oauth2/code/{registrationId}")
            .withProperty("spring.mail.host","smtp.example.test")
            .withProperty("guitar-mentor.local-auth.mail-from","sender@example.test")
            .withProperty("spring.mail.properties.mail.smtp.auth","true")
            .withProperty("spring.mail.properties.mail.smtp.starttls.required","true");
    }

    @Test
    void unifiedLocalAuthRefusesUnsafeOrIncompleteConfiguration() throws Exception {
        assertDoesNotThrow(() -> configuration.guardUnifiedLocalAuth(unifiedEnv()).run(null));
        for (String[] invalid : new String[][] {
            {"server.address","0.0.0.0"},
            {"guitar-mentor.local-auth.public-origin","http://localhost:5173"},
            {"guitar-mentor.security.enabled","false"},
            {"guitar-mentor.security.local-http","true"},
            {"spring.security.oauth2.client.registration.guitar-mentor.client-secret",""},
            {"spring.security.oauth2.client.registration.guitar-mentor.redirect-uri",
                "http://127.0.0.1:5173/login/oauth2/code/{registrationId}"},
            {"spring.mail.host",""},
            {"guitar-mentor.local-auth.mail-from",""},
            {"spring.mail.properties.mail.smtp.auth","false"},
            {"spring.mail.properties.mail.smtp.starttls.required","false"}
        }) {
            var env = unifiedEnv().withProperty(invalid[0],invalid[1]);
            assertThrows(IllegalStateException.class,
                () -> configuration.guardUnifiedLocalAuth(env).run(null),invalid[0]);
        }
        assertDoesNotThrow(() -> configuration.guardUnifiedLocalAuth(
            new org.springframework.mock.env.MockEnvironment()).run(null));
    }

}
