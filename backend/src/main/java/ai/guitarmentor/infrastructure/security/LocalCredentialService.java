package ai.guitarmentor.infrastructure.security;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
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
    private final org.springframework.beans.factory.ObjectProvider<JavaMailSender> mail;
    private final PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    // Precomputed once: do not allocate a new Argon2 hash for every login attempt.
    private final String dummyPasswordHash = encoder.encode("not-a-real-credential-value");
    private final SecureRandom random = new SecureRandom();
    @Value("${guitar-mentor.security.local-http:false}")
    private boolean localHttp;
    @Value("${server.address:127.0.0.1}")
    private String serverBind = "127.0.0.1";
    private static boolean loopback(String bind) {
        return "127.0.0.1".equals(bind) || "::1".equals(bind) || "localhost".equals(bind);
    }
    static boolean allowedMailOrigin(String origin, boolean localHttp, String bind) {
        return origin != null && (origin.matches("https://[^/\\s?#]+(?::[0-9]+)?")
                || (localHttp && loopback(bind) && "http://localhost:5173".equals(origin)));
    }
    private final String publicOrigin;
    private final String from;
    public LocalCredentialService(JdbcClient jdbc, org.springframework.beans.factory.ObjectProvider<JavaMailSender> mail,
            @Value("${guitar-mentor.local-auth.public-origin:}") String publicOrigin,
            @Value("${guitar-mentor.local-auth.mail-from:}") String from) {
        this.jdbc = jdbc; this.mail = mail; this.publicOrigin = publicOrigin; this.from = from;
    }
    private static String loginName(String name) { return CredentialPolicy.login(name); }
    private static void validPassword(String value) { CredentialPolicy.password(value); }
    // Serialize username claims across active credentials and both reservation tables.
    // Every path that creates/transfers a username takes this transaction-scoped DB lock first.
    private void lockLoginNamespace() {
        jdbc.sql("SELECT pg_advisory_xact_lock(232, 1)").query((rs, row) -> 1).single();
        jdbc.sql("DELETE FROM pending_local_registrations WHERE expires_at<=now()").update();
        jdbc.sql("DELETE FROM pending_local_links WHERE expires_at<=now()").update();
    }
    private String newToken() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
    private void send(String destination, String subject, String token, String action) {
        if (!allowedMailOrigin(publicOrigin, localHttp, serverBind)
            || from.isBlank()) throw new IllegalStateException("MAIL_NOT_CONFIGURED");
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(destination); message.setFrom(from); message.setSubject(subject);
        // No link token in logs, DTOs, persistent plaintext or analytics.
        message.setText("Open " + publicOrigin + (action.equals("link") ? "/profile" : "/") + "#authAction=" + action + "&token=" + token
                + "\nIf you did not request this, ignore this message.");
        JavaMailSender transport = mail.getIfAvailable();
        if (transport == null) throw new IllegalStateException("MAIL_NOT_CONFIGURED");
        transport.send(message);
    }
    /** A local registration consumes only an invitation issued for the normalized address. */
    @Transactional
    public void register(String invitationToken, String address, String login, String password) {
        String email = InvitationService.email(address);
        String username = loginName(login);
        validPassword(password);
        lockLoginNamespace();
        if (invitationToken == null || !invitationToken.matches("[a-f0-9]{64}"))
            throw new SecurityException("INVITATION_INVALID");
        var inv = jdbc.sql("""
            SELECT id,email_normalized FROM invitations
            WHERE token_hash=:hash AND issuer=:issuer AND status='PENDING' AND expires_at>now() FOR UPDATE
            """).param("hash",InvitationService.digest(invitationToken))
            .param("issuer",InvitationService.ADMISSION_ISSUER)
            .query((rs,i)->new Invitation(rs.getObject("id",UUID.class),rs.getString("email_normalized"))).optional();
        if (inv.isEmpty() || !inv.get().email.equals(email)) throw new SecurityException("INVITATION_INVALID");
        long exists = jdbc.sql("""
            SELECT (SELECT count(*) FROM local_credentials WHERE email_normalized=:email OR login_normalized=:login)
             + (SELECT count(*) FROM pending_local_registrations WHERE (email_normalized=:email OR login_normalized=:login) AND expires_at>now())
             + (SELECT count(*) FROM pending_local_links WHERE (email_normalized=:email OR login_normalized=:login) AND expires_at>now())
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
    /** Self-service registration: no invitation, no active account until verified email. */
    @Transactional
    public void registerPublic(String address, String login, String password) {
        String email = InvitationService.email(address);
        String username = loginName(login);
        validPassword(password);
        lockLoginNamespace();
        // Expired reservations cannot permanently hold a username or email.
        jdbc.sql("DELETE FROM pending_local_registrations WHERE expires_at<=now()").update();
        // Owner decision in #237 UAT: explain which submitted registration field conflicts.
        // Same transaction/advisory lock as every other username reservation/write path.
        var conflicts = jdbc.sql("""
            SELECT (SELECT count(*) FROM local_credentials WHERE email_normalized=:email),
                   (SELECT count(*) FROM pending_local_registrations WHERE email_normalized=:email),
                   (SELECT count(*) FROM pending_local_links WHERE email_normalized=:email),
                   (SELECT count(*) FROM local_credentials WHERE login_normalized=:login)
                     + (SELECT count(*) FROM pending_local_registrations WHERE login_normalized=:login)
                     + (SELECT count(*) FROM pending_local_links WHERE login_normalized=:login)
            """).param("email",email).param("login",username)
            .query((rs,row) -> new RegistrationConflicts(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4)))
            .single();
        if (conflicts.activeEmail() > 0 || conflicts.linkEmail() > 0)
            throw new RegistrationConflict("EMAIL_IN_USE");
        if (conflicts.pendingEmail() > 0)
            throw new RegistrationConflict("EMAIL_VERIFICATION_PENDING");
        if (conflicts.login() > 0) throw new RegistrationConflict("LOGIN_UNAVAILABLE");
        String token = newToken();
        jdbc.sql("""
            INSERT INTO pending_local_registrations
                (id,invitation_id,email_normalized,login_normalized,password_hash,verification_hash,expires_at)
            VALUES (:id,NULL,:email,:login,:hash,:token,:expiry)
            """).param("id",UUID.randomUUID())
            .param("email",email).param("login",username)
            .param("hash",encoder.encode(password))
            .param("token",InvitationService.digest(token))
            .param("expiry",java.sql.Timestamp.from(Instant.now().plus(30,ChronoUnit.MINUTES)))
            .update();
        // Transaction rolls back the pending entry if delivery fails.
        send(email,"Verify Guitar Mentor account",token,"verify");
    }

    @Transactional
    public void verify(String token) {
        if (token==null || !token.matches("[a-f0-9]{64}")) throw new SecurityException("INVALID_TOKEN");
        lockLoginNamespace();
        var pending = jdbc.sql("""
            SELECT p.id,p.invitation_id,p.email_normalized,p.login_normalized,p.password_hash,
                   i.learner_id,i.role
            FROM pending_local_registrations p
            LEFT JOIN invitations i ON i.id=p.invitation_id
            WHERE p.verification_hash=:token AND p.expires_at>now()
              AND (p.invitation_id IS NULL OR
                  (i.status='PENDING' AND i.expires_at>now()))
            FOR UPDATE OF p
            """).param("token",InvitationService.digest(token)).query((rs,i)->new Pending(
                rs.getObject("id",UUID.class),rs.getObject("invitation_id",UUID.class),
                rs.getString("email_normalized"),rs.getString("login_normalized"),
                rs.getString("password_hash"),rs.getObject("learner_id",UUID.class),
                rs.getString("role")==null?"LEARNER":rs.getString("role"))).optional();
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
        if (p.invitation != null) {
            // Preserve atomic legacy invite revocation semantics under concurrent verify/revoke.
            int accepted = jdbc.sql("""
                UPDATE invitations SET status='ACCEPTED',accepted_at=now(),accepted_account_id=:account
                WHERE id=:id AND status='PENDING' AND expires_at>now()
                """).param("account",account).param("id",p.invitation).update();
            if (accepted != 1) throw new SecurityException("INVALID_TOKEN");
        }
        jdbc.sql("DELETE FROM pending_local_registrations WHERE id=:id").param("id",p.id).update();
    }
    public UUID authenticate(String identifier, String password) {
        if (identifier==null || password==null || identifier.length()>320 || password.codePointCount(0,password.length())>128)
            throw new SecurityException("INVALID_CREDENTIALS");
        String name=CredentialPolicy.normalizeIdentifier(identifier);
        var found=jdbc.sql("""
            SELECT c.account_id,c.password_hash FROM local_credentials c
            JOIN accounts a ON a.id=c.account_id
            WHERE (c.email_normalized=:name OR c.login_normalized=:name) AND a.status='ACTIVE'
            """).param("name",name).query((rs,i)->new Credential(
                rs.getObject("account_id",UUID.class),rs.getString("password_hash"))).optional();
        // Constant costly comparison on an unknown user mitigates simple timing enumeration.
        boolean passwordMatches = encoder.matches(password, found.map(Credential::hash).orElse(dummyPasswordHash));
        if(found.isEmpty() || !passwordMatches)
            throw new SecurityException("INVALID_CREDENTIALS");
        var credential=found.get();
        if(encoder.upgradeEncoding(credential.hash)) {
            jdbc.sql("""
                UPDATE local_credentials SET password_hash=:newHash,changed_at=now()
                WHERE account_id=:id AND password_hash=:oldHash
                """).param("newHash",encoder.encode(password))
                .param("id",credential.id).param("oldHash",credential.hash).update();
        }
        return credential.id;
    }
    @Transactional
    public void requestReset(String emailInput) {
        String email;
        try { email=InvitationService.email(emailInput); }
        catch(IllegalArgumentException ex) { return; }
        var id=jdbc.sql("SELECT c.account_id FROM local_credentials c JOIN accounts a ON a.id=c.account_id WHERE c.email_normalized=:email AND a.status='ACTIVE'")
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

    /** A verified Google session may request a second method, subject to fresh reauthentication. */
    @Transactional
    public void beginLink(UUID accountId,String address,String login,String password) {
        String email=InvitationService.email(address);
        String username=loginName(login);
        validPassword(password);
        lockLoginNamespace();
        long admissible=jdbc.sql("""
            SELECT count(*) FROM accounts a
            WHERE a.id=:id AND a.status='ACTIVE'
              AND NOT EXISTS (SELECT 1 FROM local_credentials c WHERE c.account_id=a.id)
            """).param("id",accountId).query(Long.class).single();
        if(admissible!=1) throw new SecurityException("LINK_REJECTED");
        jdbc.sql("DELETE FROM pending_local_links WHERE account_id=:id OR expires_at<=now()")
            .param("id",accountId).update();
        long duplicate=jdbc.sql("""
            SELECT (SELECT count(*) FROM local_credentials WHERE email_normalized=:email OR login_normalized=:login)
             + (SELECT count(*) FROM pending_local_registrations WHERE email_normalized=:email OR login_normalized=:login)
             + (SELECT count(*) FROM pending_local_links WHERE email_normalized=:email OR login_normalized=:login)
            """).param("email",email).param("login",username).query(Long.class).single();
        if(duplicate!=0) throw new SecurityException("LINK_REJECTED");
        String token=newToken();
        jdbc.sql("""
            INSERT INTO pending_local_links(id,account_id,email_normalized,login_normalized,password_hash,verification_hash,expires_at)
            VALUES (:id,:account,:email,:login,:password,:token,:expiry)
            """).param("id",UUID.randomUUID()).param("account",accountId)
            .param("email",email).param("login",username)
            .param("password",encoder.encode(password))
            .param("token",InvitationService.digest(token))
            .param("expiry",java.sql.Timestamp.from(Instant.now().plus(30,ChronoUnit.MINUTES))).update();
        send(email,"Confirm Guitar Mentor password link",token,"link");
    }
    /** Confirmation must occur while signed in to the SAME Account that initiated linking. */
    @Transactional
    public void verifyLink(UUID accountId,String token) {
        if(token==null || !token.matches("[a-f0-9]{64}")) throw new SecurityException("LINK_REJECTED");
        lockLoginNamespace();
        var pending=jdbc.sql("""
            SELECT email_normalized,login_normalized,password_hash
            FROM pending_local_links WHERE account_id=:id AND verification_hash=:hash
             AND expires_at>now() FOR UPDATE
            """).param("id",accountId).param("hash",InvitationService.digest(token))
            .query((rs,i)->new PendingLink(rs.getString("email_normalized"),
                rs.getString("login_normalized"),rs.getString("password_hash"))).optional();
        if(pending.isEmpty()) throw new SecurityException("LINK_REJECTED");
        var row=pending.get();
        jdbc.sql("""
            INSERT INTO local_credentials(account_id,email_normalized,login_normalized,password_hash,email_verified_at)
            VALUES (:id,:email,:login,:password,now())
            """).param("id",accountId).param("email",row.email)
            .param("login",row.login).param("password",row.password).update();
        jdbc.sql("DELETE FROM pending_local_links WHERE account_id=:id").param("id",accountId).update();
    }
    public String username(UUID account) {
        return jdbc.sql("""
            SELECT c.login_normalized FROM local_credentials c JOIN accounts a ON a.id=c.account_id
            WHERE c.account_id=:id AND a.status='ACTIVE'
            """).param("id", account).query(String.class).optional().orElse(null);
    }

    /** Change the login identifier, never the account/learner identity or email. */
    @Transactional
    public String changeUsername(UUID account, String proposed, String currentPassword) {
        String name = loginName(proposed);
        if (currentPassword == null || currentPassword.codePointCount(0,currentPassword.length()) > 128)
            throw new SecurityException("INVALID_CREDENTIALS");
        lockLoginNamespace();
        var current = jdbc.sql("""
            SELECT c.login_normalized,c.password_hash FROM local_credentials c
            JOIN accounts a ON a.id=c.account_id
            WHERE c.account_id=:id AND a.status='ACTIVE' FOR UPDATE OF c
            """).param("id",account).query((rs,row)->new UsernameCredential(rs.getString(1),rs.getString(2))).optional();
        boolean matches = encoder.matches(currentPassword, current.map(UsernameCredential::hash).orElse(dummyPasswordHash));
        if (current.isEmpty() || !matches) throw new SecurityException("INVALID_CREDENTIALS");
        if (name.equals(current.get().login())) return name;
        long occupied = jdbc.sql("""
            SELECT (SELECT count(*) FROM local_credentials WHERE login_normalized=:name AND account_id<>:id)
                 + (SELECT count(*) FROM pending_local_registrations WHERE login_normalized=:name AND expires_at>now())
                 + (SELECT count(*) FROM pending_local_links WHERE login_normalized=:name AND expires_at>now())
            """).param("name",name).param("id",account).query(Long.class).single();
        if (occupied != 0) throw new UsernameUnavailable();
        jdbc.sql("UPDATE local_credentials SET login_normalized=:name,changed_at=now() WHERE account_id=:id")
            .param("name",name).param("id",account).update();
        return name;
    }
    static class RegistrationConflict extends SecurityException {
        private final String code;
        RegistrationConflict(String code) { super(code); this.code = code; }
        String code() { return code; }
    }
    private record RegistrationConflicts(long activeEmail, long pendingEmail, long linkEmail, long login) {}
    static class UsernameUnavailable extends RuntimeException {}
    private record UsernameCredential(String login,String hash) {}
    private record PendingLink(String email,String login,String password) {}
    private record Invitation(UUID id,String email) {}
    private record Pending(UUID id,UUID invitation,String email,String login,String hash,UUID learner,String role) {}
    private record Credential(UUID id,String hash) {}
}
