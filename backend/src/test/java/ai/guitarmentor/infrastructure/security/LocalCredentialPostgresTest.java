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
    @Autowired org.springframework.jdbc.core.simple.JdbcClient db;
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
    void publicRegistrationIsInactiveUntilEmailVerificationAndCannotReplay() {
        String email=UUID.randomUUID()+"@example.test";
        String login="public"+UUID.randomUUID().toString().substring(0,9);
        local.registerPublic(email,login,"secure-public-password-999");
        assertThrows(SecurityException.class,
            () -> local.authenticate(login,"secure-public-password-999"));
        String token=latestMailToken();
        local.verify(token);
        assertThrows(SecurityException.class,()->local.verify(token));
        UUID account=local.authenticate(login,"secure-public-password-999");
        assertEquals("LEARNER",actors.findActiveLocal(account,0L).orElseThrow().role());
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

    @Test
    void publicRegistrationRejectsDuplicatePendingAndActiveCredentials() {
        String email=UUID.randomUUID()+"@example.test";
        String login="free"+UUID.randomUUID().toString().substring(0,9);
        local.registerPublic(email,login,"public-password-999");
        assertThrows(SecurityException.class,
            () -> local.registerPublic(email,"another"+UUID.randomUUID().toString().substring(0,8),"public-password-999"));
        assertThrows(SecurityException.class,
            () -> local.registerPublic(UUID.randomUUID()+"@example.test",login,"public-password-999"));
        String token=latestMailToken();
        local.verify(token);
        assertThrows(SecurityException.class,
            () -> local.registerPublic(email,login,"public-password-999"));
    }

    @Test
    void publicGoogleEnrollmentCreatesDistinctAccountWithoutImplicitEmailMerge() throws Exception {
        String shared=UUID.randomUUID()+"@example.test";
        String login="linked"+UUID.randomUUID().toString().substring(0,8);
        local.registerPublic(shared,login,"public-password-999");
        local.verify(latestMailToken());
        UUID localAccount=local.authenticate(login,"public-password-999");
        var google=mock(org.springframework.security.oauth2.core.oidc.user.OidcUser.class);
        when(google.getIssuer()).thenReturn(java.net.URI.create(InvitationService.GOOGLE_ISSUER).toURL());
        when(google.getEmailVerified()).thenReturn(true);
        when(google.getSubject()).thenReturn("new-google-"+UUID.randomUUID());
        when(google.getEmail()).thenReturn(shared);
        when(google.getFullName()).thenReturn("QA Google");
        UUID googleAccount=invitations.admitPublicGoogle(google);
        assertNotEquals(localAccount,googleAccount);
        assertEquals(googleAccount,invitations.admitPublicGoogle(google));
        assertEquals(googleAccount,actors.findActive(InvitationService.GOOGLE_ISSUER,google.getSubject())
            .orElseThrow().accountId());
        assertEquals("LEARNER",actors.findActive(InvitationService.GOOGLE_ISSUER,google.getSubject())
            .orElseThrow().role());
    }

    @Test
    void unicodeUsernameChangeKeepsAccountLearnerAndEmailAndSixCharacterPasswordWorks() {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        String login="Роман"+suffix, email=suffix+"@example.test";
        local.registerPublic(email,login,"123456"); local.verify(latestMailToken());
        UUID account=local.authenticate(login.toUpperCase(java.util.Locale.ROOT),"123456");
        UUID learner=db.sql("SELECT learner_id FROM accounts WHERE id=:id").param("id",account).query(UUID.class).single();
        String display=db.sql("SELECT display_name FROM learners WHERE id=:id").param("id",learner).query(String.class).single();
        String renamed=local.changeUsername(account,"И\u0306ГРОК_"+suffix,"123456");
        assertEquals("йгрок_"+suffix,renamed);
        assertEquals(renamed,local.username(account));
        assertThrows(SecurityException.class,()->local.authenticate(login,"123456"));
        assertEquals(account,local.authenticate("ЙГРОК_"+suffix,"123456"));
        assertEquals(account,local.authenticate(email,"123456"));
        assertEquals(learner,actors.findActiveLocal(account,0L).orElseThrow().learnerId());
        assertEquals(display,db.sql("SELECT display_name FROM learners WHERE id=:id").param("id",learner).query(String.class).single());
        assertThrows(SecurityException.class,()->local.changeUsername(account,"another"+suffix,"wrong-password"));
        assertEquals(renamed,local.username(account));
        assertEquals(renamed,local.changeUsername(account,renamed.toUpperCase(java.util.Locale.ROOT),"123456"));
        local.change(account,"123456","abcdef");
        assertEquals(account,local.authenticate(renamed,"abcdef"));
        local.requestReset(email);local.reset(latestMailToken(),"fedcba");
        assertEquals(account,local.authenticate(email,"fedcba"));
    }
    @Test
    void usernameChangesRespectActiveAndPendingReservations() {
        String suffix=UUID.randomUUID().toString().substring(0,8), first="first"+suffix, second="second"+suffix;
        local.registerPublic(first+"@example.test",first,"123456"); local.verify(latestMailToken());
        local.registerPublic(second+"@example.test",second,"123456"); local.verify(latestMailToken());
        UUID account=local.authenticate(second,"123456");
        assertThrows(LocalCredentialService.UsernameUnavailable.class,()->local.changeUsername(account,first.toUpperCase(),"123456"));
        String pending="ожидает"+suffix;
        local.registerPublic(pending+"@example.test",pending,"123456");
        assertThrows(LocalCredentialService.UsernameUnavailable.class,()->local.changeUsername(account,pending,"123456"));
        UUID google=UUID.randomUUID(),learner=UUID.randomUUID();
        db.sql("INSERT INTO learners(id,display_name,created_at) VALUES (:id,'QA232',now())").param("id",learner).update();
        db.sql("INSERT INTO accounts(id,learner_id,role,status) VALUES (:id,:learner,'LEARNER','ACTIVE')").param("id",google).param("learner",learner).update();
        String linked="связь"+suffix;
        local.beginLink(google,"linked"+suffix+"@example.test",linked,"123456");
        assertThrows(LocalCredentialService.UsernameUnavailable.class,()->local.changeUsername(account,linked,"123456"));
        local.verifyLink(google,latestMailToken());
        assertEquals(google,local.authenticate(linked,"123456"));
        assertEquals(second,local.username(account));
    }
    @Test
    void expiredReservationIsReleasedAndShortPasswordsAreRejected() {
        String suffix=UUID.randomUUID().toString().substring(0,8), name="active"+suffix;
        assertThrows(IllegalArgumentException.class,()->local.registerPublic(suffix+"@example.test",name,"12345"));
        assertThrows(IllegalArgumentException.class,()->local.registerPublic(suffix+"@example.test",name,"x".repeat(129)));
        local.registerPublic(suffix+"@example.test",name,"123456");local.verify(latestMailToken());
        UUID account=local.authenticate(name,"123456");
        assertThrows(IllegalArgumentException.class,()->local.change(account,"123456","12345"));
        String expired="истекло"+suffix;
        local.registerPublic("pending"+suffix+"@example.test",expired,"123456");
        db.sql("UPDATE pending_local_registrations SET expires_at=now()-interval '1 minute' WHERE login_normalized=:name").param("name",expired).update();
        assertEquals(expired,local.changeUsername(account,expired,"123456"));
        assertEquals(account,local.authenticate(expired,"123456"));
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
