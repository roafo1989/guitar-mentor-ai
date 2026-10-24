package ai.guitarmentor.infrastructure.security;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named="GUITAR_MENTOR_TEST_POSTGRES",matches="true")
class InvitationServicePostgresTest {
    @Autowired InvitationService service;
    @Autowired JdbcClient jdbc;

    private DefaultOidcUser user(String sub, String mail, boolean verified) {
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")),
            new OidcIdToken("testing",Instant.now().minusSeconds(20),Instant.now().plusSeconds(200),
                Map.of("iss",InvitationService.GOOGLE_ISSUER, "sub",sub,
                       "email",mail,"email_verified",verified,"name","Tester")));
    }

    @Test
    void invitationIsSingleUseAndBoundToVerifiedGoogleEmailAndSubject() {
        String token=service.issue("Example+tag@Gmail.com",null,false);
        assertFalse(jdbc.sql("SELECT token_hash FROM invitations WHERE email_normalized=:e")
            .param("e","example+tag@gmail.com").query(String.class).single().equals(token));
        assertThrows(SecurityException.class,()->service.activate(token,user("unverified","example+tag@gmail.com",false)));
        assertThrows(SecurityException.class,()->service.activate(token,user("mismatch","someone@gmail.com",true)));
        UUID account=service.activate(token,user("sub-valid","example+tag@gmail.com",true));
        assertNotNull(account);
        assertThrows(SecurityException.class,()->service.activate(token,user("sub-other","example+tag@gmail.com",true)));
        assertEquals(1L,jdbc.sql("SELECT count(*) FROM accounts WHERE id=:id")
            .param("id",account).query(Long.class).single());
        service.disable(account);
        assertTrue(jdbc.sql("SELECT status FROM accounts WHERE id=:id")
            .param("id",account).query(String.class).single().equals("DISABLED"));
    }

    @Test
    void revokedInvitationCannotActivateAndNoAccountIsCreated() {
        String token=service.issue("revoked@example.com",null,false);
        UUID invite=jdbc.sql("SELECT id FROM invitations WHERE token_hash=:hash")
            .param("hash",InvitationService.digest(token)).query(UUID.class).single();
        service.revoke(invite);
        assertThrows(SecurityException.class,()->service.activate(token,user("revoked-sub","revoked@example.com",true)));
        assertEquals(0L,jdbc.sql("SELECT count(*) FROM accounts WHERE role='LEARNER' AND learner_id IN (SELECT id FROM learners WHERE display_name='Tester')")
            .query(Long.class).single());
    }

    @Test
    void explicitlyNamedLegacyLearnerIsClaimedWithoutChangingProfileHistory() {
        UUID existing = UUID.randomUUID();
        jdbc.sql("INSERT INTO learners(id,display_name,created_at) VALUES (:id,'Original learner',:created)")
            .param("id",existing).param("created",java.sql.Timestamp.from(Instant.now())).update();
        String token = service.issue("legacy-owner@example.com",existing,true);
        UUID account = service.activate(token,user("legacy-owner","legacy-owner@example.com",true));
        UUID mapped = jdbc.sql("SELECT learner_id FROM accounts WHERE id=:id")
            .param("id",account).query(UUID.class).single();
        assertEquals(existing,mapped);
        assertEquals("Original learner",jdbc.sql("SELECT display_name FROM learners WHERE id=:id")
            .param("id",existing).query(String.class).single());
        assertThrows(IllegalArgumentException.class, () -> service.issue("second@example.com",existing,false));
    }

    @Test
    void expiredInvitationIsRejectedWithoutCreatingAccount() {
        String token=service.issue("expired@example.com",null,false);
        jdbc.sql("UPDATE invitations SET expires_at=:past WHERE token_hash=:hash")
            .param("past",java.sql.Timestamp.from(Instant.now().minusSeconds(60)))
            .param("hash",InvitationService.digest(token)).update();
        assertThrows(SecurityException.class,()->service.activate(token,
            user("expired-user","expired@example.com",true)));
        assertEquals(0L,jdbc.sql("""
            SELECT count(*) FROM account_identities
            WHERE issuer=:issuer AND subject='expired-user'
            """).param("issuer",InvitationService.GOOGLE_ISSUER).query(Long.class).single());
    }

    @Test
    void expiredPendingInvitationCanBeReissuedForSameEmail() {
        String oldToken = service.issue("renew@example.com", null, false);
        jdbc.sql("UPDATE invitations SET expires_at=:past WHERE token_hash=:hash")
            .param("past", java.sql.Timestamp.from(Instant.now().minusSeconds(60)))
            .param("hash", InvitationService.digest(oldToken)).update();
        String newToken = service.issue("renew@example.com", null, false);
        assertNotEquals(oldToken, newToken);
        assertEquals("REVOKED", jdbc.sql("SELECT status FROM invitations WHERE token_hash=:hash")
            .param("hash", InvitationService.digest(oldToken)).query(String.class).single());
        assertThrows(SecurityException.class, () -> service.activate(oldToken,
            user("old-sub", "renew@example.com", true)));
        assertNotNull(service.activate(newToken, user("new-sub", "renew@example.com", true)));
    }

    @Test
    void revokedAndAcceptedInvitationsCannotBeRevokedAgain() {
        String token=service.issue("revoked-again@example.com",null,false);
        UUID invite=jdbc.sql("SELECT id FROM invitations WHERE token_hash=:hash")
            .param("hash",InvitationService.digest(token)).query(UUID.class).single();
        service.revoke(invite);
        assertThrows(IllegalArgumentException.class,()->service.revoke(invite));
        assertThrows(SecurityException.class,()->service.activate(token,
            user("revoked-again","revoked-again@example.com",true)));
    }

    @Test
    void aGoogleIdentityAlreadyBoundCannotRedeemAnotherInvitation() {
        String original=service.issue("first@example.com",null,false);
        service.activate(original,user("same-google-sub","first@example.com",true));
        String duplicate=service.issue("second@example.com",null,false);
        assertThrows(SecurityException.class,()->service.activate(duplicate,
            user("same-google-sub","second@example.com",true)));
        assertEquals("PENDING",jdbc.sql("SELECT status FROM invitations WHERE token_hash=:hash")
            .param("hash",InvitationService.digest(duplicate)).query(String.class).single());
    }

    @Test
    void invitationWithoutValidTokenNeverCreatesAccount() {
        String token=service.issue("invalid@example.com",null,false);
        assertThrows(SecurityException.class,()->service.activate("wrong",
            user("invalid-google-sub","invalid@example.com",true)));
        assertThrows(SecurityException.class,()->service.activate(null,
            user("invalid-google-sub","invalid@example.com",true)));
        assertEquals("PENDING",jdbc.sql("SELECT status FROM invitations WHERE token_hash=:hash")
            .param("hash",InvitationService.digest(token)).query(String.class).single());
    }

    @Test
    void acceptedInvitationCreatesExactlyOneIdentityAndLearnerBinding() {
        String token=service.issue("unique@example.com",null,false);
        UUID account=service.activate(token,user("unique-sub","unique@example.com",true));
        assertEquals(1L,jdbc.sql("SELECT count(*) FROM account_identities WHERE account_id=:id")
            .param("id",account).query(Long.class).single());
        assertEquals("ACCEPTED",jdbc.sql("SELECT status FROM invitations WHERE token_hash=:hash")
            .param("hash",InvitationService.digest(token)).query(String.class).single());
        assertThrows(SecurityException.class,()->service.activate(token,
            user("another-sub","unique@example.com",true)));
    }

    @Test
    void ownerBootstrapIsOneTimeAndNoGenericDisableAllowed() {
        String token=service.issue("owner@example.com",null,true);
        UUID account=service.activate(token,user("owner-sub","owner@example.com",true));
        assertThrows(IllegalStateException.class,()->service.issue("other-owner@example.com",null,true));
        assertThrows(IllegalArgumentException.class,()->service.disable(account));
    }
}
