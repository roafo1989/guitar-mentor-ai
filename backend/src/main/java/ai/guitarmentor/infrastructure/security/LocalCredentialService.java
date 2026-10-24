package ai.guitarmentor.infrastructure.security;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Local credentials: always bound to an existing invitation and the shared account model. */
@Service
public class LocalCredentialService {
    private final JdbcClient jdbc;
    private final JavaMailSender mail;
    private final PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    private final SecureRandom random = new SecureRandom();
    private final String publicOrigin;
    private final String from;
    public LocalCredentialService(JdbcClient jdbc, JavaMailSender mail,
            @Value("${guitar-mentor.local-auth.public-origin:}") String publicOrigin,
            @Value("${guitar-mentor.local-auth.mail-from:}") String from) {
        this.jdbc = jdbc; this.mail = mail; this.publicOrigin = publicOrigin; this.from = from;
    }
    private static String loginName(String name) {
        String v = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        if (!v.matches("[a-z0-9][a-z0-9._-]{2,63}")) throw new IllegalArgumentException("INVALID_INPUT");
        return v;
    }
    private static void validPassword(String value) {
        if (value == null || value.length() < 12 || value.length() > 128)
            throw new IllegalArgumentException("INVALID_INPUT");
    }
    private String newToken() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
    private void send(String destination, String subject, String token, String action) {
        if (!publicOrigin.matches("https://[^/\\s?#]+(?::[0-9]+)?")
            || from.isBlank()) throw new IllegalStateException("MAIL_NOT_CONFIGURED");
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(destination); message.setFrom(from); message.setSubject(subject);
        // No link token in logs, DTOs, persistent plaintext or analytics.
        message.setText("Open " + publicOrigin + "/?authAction=" + action + "&token=" + token
                + "\\nIf you did not request this, ignore this message.");
        mail.send(message);
    }
    /** A local registration consumes only an invitation issued for the normalized address. */
    @Transactional
    public void register(String invitationToken, String address, String login, String password) {
        String email = InvitationService.email(address);
        String username = loginName(login);
        validPassword(password);
        if (invitationToken == null || !invitationToken.matches("[a-f0-9]{64}"))
            throw new SecurityException("INVITATION_INVALID");
        var inv = jdbc.sql("""
            SELECT id,email_normalized FROM invitations
            WHERE token_hash=:hash AND status='PENDING' AND expires_at>now() FOR UPDATE
            """).param("hash",InvitationService.digest(invitationToken))
            .query((rs,i)->new Invitation(rs.getObject("id",UUID.class),rs.getString("email_normalized"))).optional();
        if (inv.isEmpty() || !inv.get().email.equals(email)) throw new SecurityException("INVITATION_INVALID");
        long exists = jdbc.sql("""
            SELECT (SELECT count(*) FROM local_credentials WHERE email_normalized=:email OR login_normalized=:login)
             + (SELECT count(*) FROM pending_local_registrations WHERE (email_normalized=:email OR login_normalized=:login) AND expires_at>now())
            """).param("email",email).param("login",username).query(Long.class).single();
        if (exists>0) throw new SecurityException("REGISTRATION_REJECTED");
        String token = newToken();
        jdbc.sql("DELETE FROM pending_local_registrations WHERE invitation_id=:id OR expires_at<=now()")
            .param("id",inv.get().id).update();
        jdbc.sql("""
            INSERT INTO pending_local_registrations(id,invitation_id,email_normalized,login_normalized,password_hash,verification_hash,expires_at)
            VALUES (:id,:inv,:email,:login,:hash,:token,:expiry)
            """).param("id",UUID.randomUUID()).param("inv",inv.get().id).param("email",email)
            .param("login",username).param("hash",encoder.encode(password))
            .param("token",InvitationService.digest(token))
            .param("expiry",java.sql.Timestamp.from(Instant.now().plus(30,ChronoUnit.MINUTES))).update();
        send(email,"Verify Guitar Mentor account",token,"verify");
    }
    @Transactional
    public void verify(String token) {
        if (token==null || !token.matches("[a-f0-9]{64}")) throw new SecurityException("INVALID_TOKEN");
        var pending = jdbc.sql("""
            SELECT p.id,p.invitation_id,p.email_normalized,p.login_normalized,p.password_hash,
                   i.learner_id,i.role
            FROM pending_local_registrations p
            JOIN invitations i ON i.id=p.invitation_id
            WHERE p.verification_hash=:token AND p.expires_at>now()
              AND i.status='PENDING' AND i.expires_at>now()
            FOR UPDATE OF p,i
            """).param("token",InvitationService.digest(token)).query((rs,i)->new Pending(
                rs.getObject("id",UUID.class),rs.getObject("invitation_id",UUID.class),
                rs.getString("email_normalized"),rs.getString("login_normalized"),
                rs.getString("password_hash"),rs.getObject("learner_id",UUID.class),rs.getString("role"))).optional();
        if (pending.isEmpty()) throw new SecurityException("INVALID_TOKEN");
        var p=pending.get();
        if ("OWNER".equals(p.role) && jdbc.sql("SELECT count(*) FROM accounts WHERE role='OWNER' AND status='ACTIVE'").query(Long.class).single()>0)
            throw new SecurityException("INVALID_TOKEN");
        UUID learner=p.learner == null?UUID.randomUUID():p.learner;
        if (p.learner==null)
            jdbc.sql("INSERT INTO learners(id,display_name,created_at) VALUES (:id,:name,now())")
                .param("id",learner).param("name",p.login).update();
        UUID account=UUID.randomUUID();
        jdbc.sql("INSERT INTO accounts(id,learner_id,role,status) VALUES (:id,:learner,:role,'ACTIVE')")
            .param("id",account).param("learner",learner).param("role",p.role).update();
        jdbc.sql("""
            INSERT INTO local_credentials(account_id,email_normalized,login_normalized,password_hash,email_verified_at)
            VALUES (:id,:email,:login,:hash,now())
            """).param("id",account).param("email",p.email).param("login",p.login).param("hash",p.hash).update();
        jdbc.sql("UPDATE invitations SET status='ACCEPTED',accepted_at=now(),accepted_account_id=:account WHERE id=:id")
            .param("account",account).param("id",p.invitation).update();
        jdbc.sql("DELETE FROM pending_local_registrations WHERE id=:id").param("id",p.id).update();
    }
    public UUID authenticate(String identifier, String password) {
        if (identifier==null || password==null || identifier.length()>320 || password.length()>128)
            throw new SecurityException("INVALID_CREDENTIALS");
        String name=identifier.strip().toLowerCase(Locale.ROOT);
        var found=jdbc.sql("""
            SELECT c.account_id,c.password_hash FROM local_credentials c
            JOIN accounts a ON a.id=c.account_id
            WHERE (c.email_normalized=:name OR c.login_normalized=:name) AND a.status='ACTIVE'
            """).param("name",name).query((rs,i)->new Credential(
                rs.getObject("account_id",UUID.class),rs.getString("password_hash"))).optional();
        // Constant costly comparison on an unknown user mitigates simple timing enumeration.
        String fallback=encoder.encode("not-a-real-credential-value");
        if(found.isEmpty() || !encoder.matches(password,found.map(Credential::hash).orElse(fallback)))
            throw new SecurityException("INVALID_CREDENTIALS");
        return found.get().id;
    }
    @Transactional
    public void requestReset(String emailInput) {
        String email;
        try { email=InvitationService.email(emailInput); }
        catch(IllegalArgumentException ex) { return; }
        var id=jdbc.sql("SELECT account_id FROM local_credentials WHERE email_normalized=:email")
            .param("email",email).query(UUID.class).optional();
        if(id.isEmpty()) return;
        String token=newToken();
        jdbc.sql("UPDATE local_password_tokens SET consumed_at=now() WHERE account_id=:id AND consumed_at IS NULL")
            .param("id",id.get()).update();
        jdbc.sql("""
            INSERT INTO local_password_tokens(id,account_id,purpose,token_hash,expires_at)
            VALUES (:id,:account,'RESET',:hash,:expires)
            """).param("id",UUID.randomUUID()).param("account",id.get())
            .param("hash",InvitationService.digest(token))
            .param("expires",java.sql.Timestamp.from(Instant.now().plus(15,ChronoUnit.MINUTES))).update();
        send(email,"Reset Guitar Mentor password",token,"reset");
    }
    @Transactional
    public void reset(String token,String password) {
        validPassword(password);
        if(token==null || !token.matches("[a-f0-9]{64}")) throw new SecurityException("INVALID_TOKEN");
        var id=jdbc.sql("""
            SELECT t.account_id FROM local_password_tokens t JOIN accounts a ON a.id=t.account_id
            WHERE t.token_hash=:hash AND t.purpose='RESET' AND t.consumed_at IS NULL
              AND t.expires_at>now() AND a.status='ACTIVE' FOR UPDATE OF t
            """).param("hash",InvitationService.digest(token)).query(UUID.class).optional();
        if(id.isEmpty()) throw new SecurityException("INVALID_TOKEN");
        replacePassword(id.get(),password);
    }
    @Transactional
    public void change(UUID account,String oldPassword,String newPassword) {
        validPassword(newPassword);
        var hash=jdbc.sql("SELECT password_hash FROM local_credentials WHERE account_id=:id FOR UPDATE")
            .param("id",account).query(String.class).optional();
        if(hash.isEmpty() || !encoder.matches(oldPassword,hash.get()))
            throw new SecurityException("INVALID_CREDENTIALS");
        replacePassword(account,newPassword);
    }
    private void replacePassword(UUID account,String password) {
        jdbc.sql("UPDATE local_credentials SET password_hash=:hash,changed_at=now() WHERE account_id=:id")
            .param("hash",encoder.encode(password)).param("id",account).update();
        jdbc.sql("UPDATE accounts SET access_version=access_version+1 WHERE id=:id").param("id",account).update();
        jdbc.sql("UPDATE local_password_tokens SET consumed_at=now() WHERE account_id=:id AND consumed_at IS NULL")
            .param("id",account).update();
    }
    private record Invitation(UUID id,String email) {}
    private record Pending(UUID id,UUID invitation,String email,String login,String hash,UUID learner,String role) {}
    private record Credential(UUID id,String hash) {}
}
