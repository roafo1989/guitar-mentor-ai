package ai.guitarmentor.infrastructure.security;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Operator CLI must be able to start without servlet web/security infrastructure. */
@EnabledIfEnvironmentVariable(named="GUITAR_MENTOR_TEST_POSTGRES",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,
    properties={"spring.main.web-application-type=none"})
class InvitationHeadlessContextTest {
    @Autowired InvitationService invitations;

    @Test
    void adminServiceStartsWithoutWebServer() {
        assertNotNull(invitations);
    }
}
