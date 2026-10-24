package ai.guitarmentor.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.time.Duration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.filter.OncePerRequestFilter;

/** BOLA guard: all learner-scoped routes are checked before reaching controllers or media. */
public class ActorFilter extends OncePerRequestFilter {
    private final ActorRepository repository;
    private final Duration absoluteLifetime;

    public ActorFilter(ActorRepository repository, Duration absoluteLifetime) {
        this.repository = repository;
        this.absoluteLifetime = absoluteLifetime;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!path.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        var session = request.getSession(false);
        if (session != null && System.currentTimeMillis() - session.getCreationTime()
                >= absoluteLifetime.toMillis()) {
            session.invalidate();
            SecurityContextHolder.clearContext();
            deny(response, 401, "SESSION_EXPIRED");
            return;
        }
        if (path.equals("/api/v1/csrf") && request.getMethod().equals("GET")) {
            chain.doFilter(request, response); return;
        }
        if (path.startsWith("/api/v1/local-auth/") && !path.equals("/api/v1/local-auth/change")) {
            chain.doFilter(request, response); return;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            deny(response, 401, "UNAUTHENTICATED");
            return;
        }
        // Validated Google OIDC users without admission may redeem an invite only.
        // All other private API routes still require an admitted active account.
        if ((path.equals("/api/v1/invitations/activate") && request.getMethod().equals("POST"))
            || (path.equals("/api/v1/csrf") && request.getMethod().equals("GET"))) {
            chain.doFilter(request, response);
            return;
        }
        var actor = auth.getPrincipal() instanceof OidcUser oidc
            ? repository.findActive(oidc.getIssuer().toString(), oidc.getSubject())
            : auth.getPrincipal() instanceof LocalPrincipal local
                ? repository.findActiveLocal(local.accountId(),local.accessVersion())
                : java.util.Optional.<CurrentActor>empty();
        if (actor.isEmpty()) {
            deny(response, 403, "ACCOUNT_NOT_ADMITTED");
            return;
        }
        // Existing OIDC sessions are version-pinned at successful login/activation.
        // Password change/reset or operator revocation bumps access_version and rejects old sessions.
        if (auth.getPrincipal() instanceof OidcUser) {
            Object pinned = session == null ? null : session.getAttribute("guitarMentorAccessVersion");
            var current = repository.localVersion(actor.get().accountId());
            if (session != null && (!(pinned instanceof Long) || current.isEmpty() || !pinned.equals(current.get()))) {
                if (session != null) session.invalidate();
                SecurityContextHolder.clearContext();
                deny(response, 401, "SESSION_EXPIRED");
                return;
            }
        }
        request.setAttribute("guitarMentorActor", actor.get());
        if (path.equals("/api/v1/learners") || path.equals("/api/v1/learners/")) {
            // Enrollment must be atomic and invitation-gated in #42, never via public create.
            deny(response, 403, "INVITATION_REQUIRED");
            return;
        }
        String prefix = "/api/v1/learners/";
        if (path.startsWith(prefix)) {
            String segment = path.substring(prefix.length()).split("/", 2)[0];
            UUID requested;
            try { requested = UUID.fromString(segment); }
            catch (IllegalArgumentException ex) {
                deny(response, 404, "RESOURCE_NOT_FOUND");
                return;
            }
            if (!requested.equals(actor.get().learnerId())) {
                deny(response, 404, "RESOURCE_NOT_FOUND");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static void deny(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\"}");
    }
}
