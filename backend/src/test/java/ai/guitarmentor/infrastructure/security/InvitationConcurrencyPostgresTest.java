package ai.guitarmentor.infrastructure.security;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

/**
 * Two actual PostgreSQL transactions race on the same invitation.
 * No test-level @Transactional: worker threads must have independent connections.
 * The test owns and removes only its randomly named rows.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named="GUITAR_MENTOR_TEST_POSTGRES", matches="true")
class InvitationConcurrencyPostgresTest {
    @Autowired InvitationService invitations;
    @Autowired JdbcClient jdbc;

    private static DefaultOidcUser identity(String subject, String email) {
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")),
            new OidcIdToken("synthetic", Instant.now().minusSeconds(10),
                Instant.now().plusSeconds(300),
                Map.of("iss", InvitationService.GOOGLE_ISSUER,
                    "sub", subject, "email", email, "email_verified", true, "name", "QA race")));
    }

    @Test
    void onlyOneConcurrentRedemptionCommitsAccountAndConsumesInvite() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String email = "race-" + suffix + "@example.com";
        String token = invitations.issue(email, null, false);
        String hash = InvitationService.digest(token);
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Boolean> redeem = () -> {
                if (!start.await(10, TimeUnit.SECONDS))
                    throw new IllegalStateException("Concurrent test start timed out");
                try {
                    invitations.activate(token,
                        identity("google-" + UUID.randomUUID(), email));
                    return true;
                } catch (SecurityException rejected) {
                    return false;
                }
            };
            var a = executor.submit(redeem);
            var b = executor.submit(redeem);
            start.countDown();
            int wins = (a.get(20, TimeUnit.SECONDS) ? 1 : 0)
                + (b.get(20, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, wins, "One-and-only-one redeemer may commit");
            assertEquals("ACCEPTED", jdbc.sql("""
                SELECT status FROM invitations WHERE token_hash=:hash
                """).param("hash", hash).query(String.class).single());
            assertEquals(1L, jdbc.sql("""
                SELECT count(*) FROM accounts a
                JOIN invitations i ON i.accepted_account_id=a.id
                WHERE i.token_hash=:hash
                """).param("hash", hash).query(Long.class).single());
            assertEquals(1L, jdbc.sql("""
                SELECT count(*) FROM account_identities ai
                JOIN invitations i ON i.accepted_account_id=ai.account_id
                WHERE i.token_hash=:hash
                """).param("hash", hash).query(Long.class).single());
        } finally {
            executor.shutdownNow();
            // Do not delete any other fixture or operator account.
            var bindings = jdbc.sql("""
                SELECT accepted_account_id FROM invitations WHERE token_hash=:hash
                """).param("hash", hash).query((rs,n) ->
                    rs.getObject("accepted_account_id", UUID.class)).list();
            for (UUID account : bindings) {
                if (account == null) continue;
                UUID learner = jdbc.sql("SELECT learner_id FROM accounts WHERE id=:id")
                    .param("id", account).query(UUID.class).single();
                jdbc.sql("DELETE FROM invitations WHERE token_hash=:hash")
                    .param("hash", hash).update();
                jdbc.sql("DELETE FROM account_identities WHERE account_id=:id")
                    .param("id", account).update();
                jdbc.sql("DELETE FROM accounts WHERE id=:id").param("id", account).update();
                jdbc.sql("DELETE FROM learners WHERE id=:id").param("id", learner).update();
            }
            jdbc.sql("DELETE FROM invitations WHERE token_hash=:hash")
                .param("hash", hash).update();
        }
    }
}
