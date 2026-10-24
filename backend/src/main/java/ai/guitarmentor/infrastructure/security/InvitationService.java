package ai.guitarmentor.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Invitation verification runs only against the validated OIDC principal. */
@Service
public class InvitationService {
    public static final String GOOGLE_ISSUER = "https://accounts.google.com";
    private final JdbcClient jdbc;
    private final SecureRandom random = new SecureRandom();

    public InvitationService(JdbcClient jdbc) { this.jdbc = jdbc; }

    static String email(String value) {
        if (value == null) throw new IllegalArgumentException("email missing");
        String v = value.strip().toLowerCase(Locale.ROOT);
        if (v.isBlank() || v.length() > 320 || !v.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
            throw new IllegalArgumentException("invalid email");
        return v;
    }

    public static String digest(String token) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    @Transactional
    public String issue(String address, UUID existingLearner) {
        String normalized = email(address);
        if (existingLearner != null) {
            long used = jdbc.sql("SELECT count(*) FROM accounts WHERE learner_id=:id")
                .param("id",existingLearner).query(Long.class).single();
            long exists = jdbc.sql("SELECT count(*) FROM learners WHERE id=:id")
                .param("id",existingLearner).query(Long.class).single();
            if (used != 0 || exists != 1) throw new IllegalArgumentException("learner unavailable");
        }
        byte[] randomBytes = new byte[32];
        random.nextBytes(randomBytes);
        String token = HexFormat.of().formatHex(randomBytes);
        jdbc.sql("""
            INSERT INTO invitations(id,token_hash,issuer,email_normalized,learner_id,status,expires_at)
            VALUES (:id,:hash,:issuer,:email,:learner,'PENDING',:expiry)
            """)
            .param("id",UUID.randomUUID()).param("hash",digest(token))
            .param("issuer",GOOGLE_ISSUER).param("email",normalized)
            .param("learner",existingLearner).param("expiry",Instant.now().plus(7,ChronoUnit.DAYS))
            .update();
        return token;
    }

    @Transactional
    public UUID activate(String secret, OidcUser identity) {
        if (secret == null || !secret.matches("[a-f0-9]{64}") || identity == null
                || !GOOGLE_ISSUER.equals(identity.getIssuer().toString())
                || !Boolean.TRUE.equals(identity.getEmailVerified())
                || identity.getSubject() == null || identity.getSubject().isBlank()) {
            throw new SecurityException("INVITATION_INVALID");
        }
        String verifiedEmail;
        try { verifiedEmail = email(identity.getEmail()); }
        catch (IllegalArgumentException ex) { throw new SecurityException("INVITATION_INVALID"); }
        var invitations = jdbc.sql("""
           SELECT id,email_normalized,issuer,learner_id,status,expires_at
           FROM invitations WHERE token_hash=:hash FOR UPDATE
           """).param("hash",digest(secret)).query((rs,i)->new InvitationRow(
               rs.getObject("id",UUID.class),rs.getString("email_normalized"),
               rs.getString("issuer"),rs.getObject("learner_id",UUID.class),
               rs.getString("status"),rs.getTimestamp("expires_at").toInstant())).list();
        if (invitations.size()!=1) throw new SecurityException("INVITATION_INVALID");
        var invite=invitations.getFirst();
        if (!"PENDING".equals(invite.status()) || !invite.expires().isAfter(Instant.now())
                || !invite.email().equals(verifiedEmail) || !invite.issuer().equals(identity.getIssuer().toString()))
            throw new SecurityException("INVITATION_INVALID");
        long already = jdbc.sql("SELECT count(*) FROM account_identities WHERE issuer=:iss AND subject=:sub")
            .param("iss",GOOGLE_ISSUER).param("sub",identity.getSubject())
            .query(Long.class).single();
        if(already!=0) throw new SecurityException("INVITATION_INVALID");
        UUID learner=invite.learner()==null ? UUID.randomUUID() : invite.learner();
        if(invite.learner()==null) {
            String name = identity.getFullName();
            if (name==null || name.isBlank()) name=verifiedEmail.substring(0,verifiedEmail.indexOf('@'));
            if (name.length()>100) name=name.substring(0,100);
            jdbc.sql("INSERT INTO learners(id,display_name,created_at) VALUES (:id,:name,:created)")
                .param("id",learner).param("name",name).param("created",Instant.now()).update();
        }
        UUID account=UUID.randomUUID();
        jdbc.sql("INSERT INTO accounts(id,learner_id,role,status) VALUES (:id,:learner,'LEARNER','ACTIVE')")
            .param("id",account).param("learner",learner).update();
        jdbc.sql("INSERT INTO account_identities(issuer,subject,account_id) VALUES (:iss,:sub,:id)")
            .param("iss",GOOGLE_ISSUER).param("sub",identity.getSubject()).param("id",account).update();
        jdbc.sql("""
           UPDATE invitations SET status='ACCEPTED',accepted_at=:now,accepted_account_id=:account
           WHERE id=:id
           """).param("now",Instant.now()).param("account",account).param("id",invite.id()).update();
        return account;
    }

    @Transactional
    public void disable(UUID account) {
        int count=jdbc.sql("""
           UPDATE accounts SET status='DISABLED',access_version=access_version+1
           WHERE id=:id AND role='LEARNER'
           """).param("id",account).update();
        if(count!=1) throw new IllegalArgumentException("account unavailable or OWNER protected");
    }

    @Transactional
    public void revoke(UUID invite) {
        int count=jdbc.sql("UPDATE invitations SET status='REVOKED' WHERE id=:id AND status='PENDING'")
            .param("id",invite).update();
        if(count!=1) throw new IllegalArgumentException("pending invitation not found");
    }
    private record InvitationRow(UUID id,String email,String issuer,UUID learner,String status,Instant expires){}
}
