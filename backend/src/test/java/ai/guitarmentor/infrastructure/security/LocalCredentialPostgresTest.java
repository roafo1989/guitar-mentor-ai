package ai.guitarmentor.infrastructure.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/** Real PostgreSQL admission and password lifecycle; no external mail delivery. */
@SpringBootTest(properties={
    "guitar-mentor.local-auth.public-origin=https://app.example.test",
    "guitar-mentor.local-auth.mail-from=no-reply@app.example.test"
})
@EnabledIfEnvironmentVariable(named="GUITAR_MENTOR_TEST_POSTGRES",matches="true")
@Transactional
class LocalCredentialPostgresTest {
    @Autowired LocalCredentialService local;
    @Autowired InvitationService invitations;
    @Autowired ActorRepository actors;
    @MockitoBean JavaMailSender sender;
    private static final Pattern TOKEN=Pattern.compile("token=([a-f0-9]{64})");

    @Test
    void invitedUserNeedsVerifiedMailAndCannotReplayVerification() {
        String email=UUID.randomUUID()+"@example.test";
        String invite=invitations.issue(email,null,false);
        String login="player"+UUID.randomUUID().toString().substring(0,8);
        local.register(invite,email,login,"correct-horse-999");
        assertThrows(SecurityException.class,()->local.authenticate(login,"correct-horse-999"));
        assertThrows(SecurityException.class,()->local.register(invite,email,login,"correct-horse-999"));
        String code=latestMailToken();
        local.verify(code);
        assertThrows(SecurityException.class,()->local.verify(code));
        UUID actor=local.authenticate(login,"correct-horse-999");
        assertEquals(actor,local.authenticate(email.toUpperCase(),"correct-horse-999"));
        assertTrue(actors.localVersion(actor).isPresent());
        assertThrows(SecurityException.class,()->local.authenticate(login,"wrong password"));
    }

    @Test
    void resetIsSingleUseAndRevokesPriorAccessVersion() {
        String email=UUID.randomUUID()+"@example.test";
        String login="learner"+UUID.randomUUID().toString().substring(0,8);
        local.register(invitations.issue(email,null,false),email,login,"old-password-999");
        local.verify(latestMailToken());
        UUID id=local.authenticate(email,"old-password-999");
        long oldVersion=actors.localVersion(id).orElseThrow();
        clearInvocations(sender);
        local.requestReset(email);
        String token=latestMailToken();
        local.reset(token,"new-password-999");
        assertThrows(SecurityException.class,()->local.reset(token,"third-password-999"));
        assertThrows(SecurityException.class,()->local.authenticate(login,"old-password-999"));
        assertEquals(id,local.authenticate(login,"new-password-999"));
        assertTrue(actors.findActiveLocal(id,oldVersion).isEmpty());
    }

    @Test
    void noInvitationNeverRegistersOrSendsMail() {
        assertThrows(SecurityException.class,()->local.register("f".repeat(64),
            UUID.randomUUID()+"@example.test","uninvited","secure-password-999"));
        verifyNoInteractions(sender);
    }

    private String latestMailToken() {
        var captor=ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender,atLeastOnce()).send(captor.capture());
        var text=captor.getAllValues().getLast().getText();
        var match=TOKEN.matcher(text);
        assertTrue(match.find());
        return match.group(1);
    }
}
