package ai.guitarmentor.infrastructure.security;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicit linking only, never merge by matching email or login. */
@Service
public class GoogleLinkService {
    private final JdbcClient jdbc;
    private final ActorRepository actors;
    public GoogleLinkService(JdbcClient jdbc, ActorRepository actors) {
        this.jdbc=jdbc; this.actors=actors;
    }
    @Transactional
    public long attachVerifiedGoogle(UUID account, long pinnedVersion, OidcUser google) {
        if(google==null || !InvitationService.GOOGLE_ISSUER.equals(google.getIssuer().toString())
            || !Boolean.TRUE.equals(google.getEmailVerified())
            || google.getSubject()==null || google.getSubject().isBlank())
            throw new SecurityException("GOOGLE_LINK_REJECTED");
        long version=actors.localVersion(account).orElseThrow(()->new SecurityException("GOOGLE_LINK_REJECTED"));
        if(version!=pinnedVersion) throw new SecurityException("GOOGLE_LINK_REJECTED");
        var existing=jdbc.sql("SELECT account_id FROM account_identities WHERE issuer=:iss AND subject=:sub FOR UPDATE")
            .param("iss",InvitationService.GOOGLE_ISSUER).param("sub",google.getSubject())
            .query(UUID.class).optional();
        if(existing.isPresent()) {
            if(!existing.get().equals(account)) throw new SecurityException("GOOGLE_LINK_REJECTED");
            return version;
        }
        jdbc.sql("INSERT INTO account_identities(issuer,subject,account_id) VALUES (:iss,:sub,:account)")
            .param("iss",InvitationService.GOOGLE_ISSUER).param("sub",google.getSubject())
            .param("account",account).update();
        return version;
    }
}
