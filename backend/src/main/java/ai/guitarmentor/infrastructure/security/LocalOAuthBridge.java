package ai.guitarmentor.infrastructure.security;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

/**
 * One-process, localhost-only OAuth callback handoff to the original HTTPS
 * guitar-mentor session. Never reuse on a public/multi-node deployment.
 * Both challenges and tickets are single-use, short-lived and bound to the
 * pre-existing HTTPS session. No browser-side identity assertions are trusted.
 */
@Service
public class LocalOAuthBridge {
    private static final long START_MS = Duration.ofMinutes(3).toMillis();
    private static final long TICKET_MS = Duration.ofMinutes(1).toMillis();
    private static final int MAX_OUTSTANDING = 2048;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, Completed> completed = new ConcurrentHashMap<>();
    private record Pending(String originalSessionId, boolean linking, long expiresAt) {}
    private record Completed(String originalSessionId, OidcUser principal, long expiresAt) {}

    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private void prune() {
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(e -> e.getValue().expiresAt < now);
        completed.entrySet().removeIf(e -> e.getValue().expiresAt < now);
    }

    public String begin(String originalSessionId, boolean linking) {
        if (originalSessionId == null || originalSessionId.isBlank()) throw new SecurityException("NO_SESSION");
        prune();
        if (pending.size() + completed.size() >= MAX_OUTSTANDING)
            throw new SecurityException("BRIDGE_AT_CAPACITY");
        String nonce = token();
        pending.put(nonce, new Pending(originalSessionId, linking, System.currentTimeMillis() + START_MS));
        return nonce;
    }

    /** Check that the browser actually began this flow before redirecting to Google. */
    public boolean validStart(String nonce) {
        if (nonce == null || !nonce.matches("[a-f0-9]{64}")) return false;
        Pending value = pending.get(nonce);
        return value != null && value.expiresAt >= System.currentTimeMillis();
    }

    public boolean linking(String nonce) {
        Pending value = pending.get(nonce);
        if (value == null || value.expiresAt < System.currentTimeMillis())
            throw new SecurityException("OAUTH_START_EXPIRED");
        return value.linking;
    }

    /**
     * Only called by the OAuth success handler with a server-validated OIDC
     * principal. Removes the start nonce atomically to prevent replay.
     */
    public String complete(String nonce, Authentication authentication) {
        if (nonce == null || !(authentication.getPrincipal() instanceof OidcUser oidc))
            throw new SecurityException("INVALID_OAUTH_BRIDGE");
        Pending start = pending.remove(nonce);
        if (start == null || start.expiresAt < System.currentTimeMillis())
            throw new SecurityException("OAUTH_START_EXPIRED");
        prune();
        String ticket = token();
        completed.put(ticket, new Completed(start.originalSessionId, oidc,
                System.currentTimeMillis() + TICKET_MS));
        return ticket;
    }

    /** Burns even a mismatched ticket so a stolen ticket cannot be retried. */
    public OidcUser consume(String ticket, String originalSessionId) {
        if (ticket == null || !ticket.matches("[a-f0-9]{64}"))
            throw new SecurityException("INVALID_BRIDGE_TICKET");
        Completed finished = completed.remove(ticket);
        if (finished == null || finished.expiresAt < System.currentTimeMillis()
                || !finished.originalSessionId.equals(originalSessionId))
            throw new SecurityException("INVALID_BRIDGE_TICKET");
        return finished.principal;
    }
}
