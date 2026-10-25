package ai.guitarmentor.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.*;

/** Restricted to the local desktop profile: never expose this bridge publicly. */
@RestController
public class LocalOAuthBridgeController {
    private final LocalOAuthBridge bridge;
    private final ActorRepository actors;
    private final GoogleLinkService googleLinks;
    private final boolean enabled;
    private final String publicOrigin;

    LocalOAuthBridgeController(LocalOAuthBridge bridge, ActorRepository actors, GoogleLinkService googleLinks,
            @Value("${guitar-mentor.local-auth.unified-local:false}") boolean enabled,
            @Value("${guitar-mentor.local-auth.public-origin:}") String publicOrigin) {
        this.bridge = bridge;
        this.actors = actors;
        this.googleLinks = googleLinks;
        this.enabled = enabled;
        this.publicOrigin = publicOrigin;
    }

    private void checkEnabled() {
        if (!enabled) throw new SecurityException("BRIDGE_DISABLED");
    }

    @PostMapping("/api/v1/oauth-bridge/start")
    ResponseEntity<Map<String, String>> start(HttpServletRequest request) {
        checkEnabled();
        // Only our HTTPS app may start the OAuth flow. CSRF is additionally
        // enforced by Spring Security before this controller is invoked.
        if (!publicOrigin.equals(request.getHeader("Origin")))
            return ResponseEntity.status(403).build();
        var session = request.getSession(true);
        String nonce = bridge.begin(session.getId(),
                session.getAttribute("guitarMentorLinkGoogleAccount") != null);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("beginUrl", "http://localhost:5173/oauth-bridge/begin?nonce=" + nonce));
    }

    @GetMapping("/oauth-bridge/begin")
    ResponseEntity<Void> begin(@RequestParam String nonce, HttpServletRequest request) {
        checkEnabled();
        if (!bridge.validStart(nonce)) return ResponseEntity.status(403).build();
        request.getSession(true).setAttribute("guitarMentorBridgeStartNonce", nonce);
        return ResponseEntity.status(302).header(HttpHeaders.LOCATION, "/oauth2/authorization/guitar-mentor")
                .header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @PostMapping("/api/v1/oauth-bridge/consume")
    ResponseEntity<Map<String, String>> consume(@RequestBody Redeem body,
            HttpServletRequest request, HttpServletResponse response) {
        checkEnabled();
        if (!publicOrigin.equals(request.getHeader("Origin")))
            return ResponseEntity.status(403).build();
        var session = request.getSession(false);
        if (session == null) return ResponseEntity.status(403).build();
        try {
            var handoff = bridge.consume(body.ticket(), session.getId());
            var oidc = handoff.principal();
            CurrentActor actor;

            // Explicit account linking was authorized by local password re-entry
            // on this original HTTPS session; never link by email coincidence.
            Object linkAccount = session.getAttribute("guitarMentorLinkGoogleAccount");
            if (handoff.linking() != (linkAccount != null))
                throw new SecurityException("GOOGLE_LINK_INTENT_MISMATCH");
            if (linkAccount != null) {
                Object pinned = session.getAttribute("guitarMentorLinkGoogleVersion");
                Object started = session.getAttribute("guitarMentorLinkGoogleStarted");
                session.removeAttribute("guitarMentorLinkGoogleAccount");
                session.removeAttribute("guitarMentorLinkGoogleVersion");
                session.removeAttribute("guitarMentorLinkGoogleStarted");
                if (!(linkAccount instanceof UUID accountId) || !(pinned instanceof Long version)
                        || !(started instanceof Long millis) || System.currentTimeMillis() - millis > 300_000L)
                    throw new SecurityException("GOOGLE_LINK_REJECTED");
                googleLinks.attachVerifiedGoogle(accountId, version, oidc);
                actor = actors.findActive(oidc.getIssuer().toString(), oidc.getSubject()).orElseThrow();
                if (!actor.accountId().equals(accountId)) throw new SecurityException("GOOGLE_LINK_REJECTED");
            } else {
                actor = actors.findActive(oidc.getIssuer().toString(), oidc.getSubject()).orElseThrow();
            }

            var accessVersion = actors.localVersion(actor.accountId()).orElseThrow();
            request.changeSessionId();
            session.setAttribute("guitarMentorAccessVersion", accessVersion);
            session.setAttribute("guitarMentorAuthenticatedAt", System.currentTimeMillis());
            var auth = new UsernamePasswordAuthenticationToken(oidc, null,
                    List.of(new SimpleGrantedAuthority("ROLE_USER")));
            var ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            SecurityContextHolder.setContext(ctx);
            new HttpSessionSecurityContextRepository().saveContext(ctx, request, response);
            return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(Map.of("code", "GOOGLE_SIGNED_IN"));
        } catch (SecurityException | java.util.NoSuchElementException error) {
            return ResponseEntity.status(403).header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(Map.of("code", "OAUTH_BRIDGE_FAILED"));
        }
    }

    record Redeem(String ticket) {}
}
