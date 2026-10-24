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
    @Autowired GoogleLinkService googleLinks;
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


    @Test
    void linkingPasswordToExistingGoogleAccountKeepsSameAccountAndLearner() {
        // A real Google-only account is provisioned by the invitation admission flow.
        var learner=UUID.randomUUID();
        var account=UUID.randomUUID();
        var jdbc=org.springframework.test.util.ReflectionTestUtils.getField(actors,"jdbc");
        assertTrue(jdbc instanceof org.springframework.jdbc.core.simple.JdbcClient);
        var db=(org.springframework.jdbc.core.simple.JdbcClient)jdbc;
        db.sql("INSERT INTO learners(id,display_name,created_at) VALUES (:id,'Google player',now())")
            .param("id",learner).update();
        db.sql("INSERT INTO accounts(id,learner_id,role,status) VALUES (:id,:learner,'LEARNER','ACTIVE')")
            .param("id",account).param("learner",learner).update();
        db.sql("INSERT INTO account_identities(issuer,subject,account_id) VALUES (:iss,:sub,:account)")
            .param("iss",InvitationService.GOOGLE_ISSUER)
            .param("sub",UUID.randomUUID().toString()).param("account",account).update();
        String email=UUID.randomUUID()+"@example.test";
        String login="linked"+UUID.randomUUID().toString().substring(0,8);
        local.beginLink(account,email,login,"linked-password-999");
        assertFalse(actors.hasLocalCredential(account));
        String code=latestMailToken();
        local.verifyLink(account,code);
        assertThrows(SecurityException.class,()->local.verifyLink(account,code));
        assertEquals(account,local.authenticate(login,"linked-password-999"));
        assertEquals(learner,actors.findActiveLocal(account,0L).orElseThrow().learnerId());
    }

    @Test
    void explicitGoogleLinkRejectsAlreadyClaimedIdentity() throws Exception {
        String email=UUID.randomUUID()+"@example.test";
        String login="first"+UUID.randomUUID().toString().substring(0,8);
        local.register(invitations.issue(email,null,false),email,login,"first-password-999");
        local.verify(latestMailToken());
        UUID first=local.authenticate(login,"first-password-999");
        long version=actors.localVersion(first).orElseThrow();
        var oidc=mock(org.springframework.security.oauth2.core.oidc.user.OidcUser.class);
        when(oidc.getIssuer()).thenReturn(java.net.URI.create(InvitationService.GOOGLE_ISSUER).toURL());
        when(oidc.getEmailVerified()).thenReturn(true);
        when(oidc.getSubject()).thenReturn("synthetic-"+UUID.randomUUID());
        googleLinks.attachVerifiedGoogle(first,version,oidc);
        assertTrue(actors.hasGoogleIdentity(first));
        assertEquals(first,actors.findActive(InvitationService.GOOGLE_ISSUER,oidc.getSubject())
            .orElseThrow().accountId());
        UUID another=UUID.randomUUID(), anotherLearner=UUID.randomUUID();
        var db=(org.springframework.jdbc.core.simple.JdbcClient)
            org.springframework.test.util.ReflectionTestUtils.getField(actors,"jdbc");
        db.sql("INSERT INTO learners(id,display_name,created_at) VALUES (:id,'Other learner',now())")
            .param("id",anotherLearner).update();
        db.sql("INSERT INTO accounts(id,learner_id,role,status) VALUES (:id,:learner,'LEARNER','ACTIVE')")
            .param("id",another).param("learner",anotherLearner).update();
        assertThrows(SecurityException.class,()->googleLinks.attachVerifiedGoogle(another,0L,oidc));
    }

    private String latestMailToken() {
        var captor=ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender,atLeastOnce()).send(captor.capture());
        var text=captor.getAllValues().getLast().getText();
        assertNotNull(text);
        assertTrue(text.contains("\nIf you did not request this, ignore this message."),
            "Verification and reset emails must contain a real line break");
        var match=TOKEN.matcher(text);
        assertTrue(match.find());
        return match.group(1);
    }
}
