package ai.guitarmentor.infrastructure.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ActorRepository {
    private final JdbcClient jdbc;

    public ActorRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Always recheck status against the database; disabling access affects the next request. */
    public Optional<CurrentActor> findActive(String issuer, String subject) {
        return jdbc.sql("""
                SELECT a.id AS account_id, a.learner_id, a.role
                FROM account_identities i
                JOIN accounts a ON a.id = i.account_id
                WHERE i.issuer = :issuer AND i.subject = :subject AND a.status = 'ACTIVE'
                """)
                .param("issuer", issuer).param("subject", subject)
                .query((rs, n) -> new CurrentActor(
                        rs.getObject("account_id", UUID.class),
                        rs.getObject("learner_id", UUID.class),
                        rs.getString("role")))
                .optional();
    }
}
