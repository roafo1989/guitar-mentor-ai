package ai.guitarmentor.infrastructure.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** Real Postgres identity resolution including immediate account disable. Synthetic records roll back. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "GUITAR_MENTOR_TEST_POSTGRES", matches = "true")
@Transactional
class ActorRepositoryPostgresTest {
    @Autowired ActorRepository actors;
    @Autowired JdbcClient jdbc;

    @Test
    void twoDistinctIdentityPairsOnlyResolveTheirOwnActiveAccount() {
        UUID learnerA = UUID.randomUUID();
        UUID learnerB = UUID.randomUUID();
        UUID accountA = UUID.randomUUID();
        UUID accountB = UUID.randomUUID();
        insertLearner(learnerA);
        insertLearner(learnerB);
        insertAccount(accountA, learnerA);
        insertAccount(accountB, learnerB);
        insertIdentity(accountA, "https://idp.test/realms/a", "subject-1");
        insertIdentity(accountB, "https://idp.test/realms/a", "subject-2");

        assertEquals(learnerA, actors.findActive("https://idp.test/realms/a", "subject-1")
                .orElseThrow().learnerId());
        assertEquals(learnerB, actors.findActive("https://idp.test/realms/a", "subject-2")
                .orElseThrow().learnerId());
        assertTrue(actors.findActive("https://evil-idp.test", "subject-1").isEmpty());
        assertTrue(actors.findActive("https://idp.test/realms/a", "not-invited").isEmpty());

        jdbc.sql("UPDATE accounts SET status='DISABLED',access_version=access_version+1 WHERE id=:id")
                .param("id", accountA).update();
        assertTrue(actors.findActive("https://idp.test/realms/a", "subject-1").isEmpty());
        assertEquals(learnerB, actors.findActive("https://idp.test/realms/a", "subject-2")
                .orElseThrow().learnerId());
    }

    private void insertLearner(UUID id) {
        jdbc.sql("INSERT INTO learners(id,display_name,created_at) VALUES (:id,'QA #38',:created)")
            .param("id", id).param("created", java.sql.Timestamp.from(Instant.now())).update();
    }
    private void insertAccount(UUID id, UUID learner) {
        jdbc.sql("INSERT INTO accounts(id,learner_id,role,status) VALUES (:id,:learner,'LEARNER','ACTIVE')")
            .param("id", id).param("learner", learner).update();
    }
    private void insertIdentity(UUID account, String issuer, String subject) {
        jdbc.sql("INSERT INTO account_identities(issuer,subject,account_id) VALUES (:issuer,:subject,:account)")
            .param("issuer", issuer).param("subject", subject).param("account", account).update();
    }
}
