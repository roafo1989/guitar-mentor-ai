package ai.guitarmentor.infrastructure.security;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Fail the build when a new API resource is introduced outside learner ownership
 * without explicitly classifying the route and reviewing its authorization model.
 */
@SpringBootTest
class ApiRouteOwnershipInventoryTest {
    private static final Set<String> SHARED_API = Set.of(
        "/api/v1/me",
        "/api/v1/csrf",
        "/api/v1/invitations/activate",
        // Anonymous, CSRF-protected and throttled; admission requires invitation + delivered proof.
        "/api/v1/local-auth/register",
        "/api/v1/local-auth/verify",
        "/api/v1/local-auth/login",
        "/api/v1/local-auth/request-reset",
        "/api/v1/local-auth/reset",
        // Desktop-only OAuth handoff: Origin+CSRF, nonce, original session binding, one use.
        "/api/v1/oauth-bridge/start",
        "/api/v1/oauth-bridge/consume",
        // Authenticated CurrentActor + current password, never client-selected account.
        "/api/v1/local-auth/change",
        "/api/v1/local-auth/methods",
        // GET: own actor only; POST: same actor + current password + CSRF + throttle (#232).
        "/api/v1/local-auth/username",
        // Each link route requires a current actor, fresh method proof and CSRF.
        "/api/v1/local-auth/link/google/start",
        "/api/v1/local-auth/link/password/start",
        "/api/v1/local-auth/link/password/verify",
        "/api/v1/system/ping",
        // CurrentActor-only feature capabilities; no caller-selected object id.
        "/api/v1/library/capabilities"
    );
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void allNonLearnerApiRoutesAreExplicitlyClassified() {
        Set<String> unclassified = new TreeSet<>();
        Set<String> seen = new TreeSet<>();
        mappings.getHandlerMethods().forEach((info, method) -> {
            for (String path : info.getPatternValues()) {
                if (!path.startsWith("/api/")) continue;
                seen.add(path);
                if (path.startsWith("/api/v1/learners/") || path.equals("/api/v1/learners"))
                    continue; // ActorFilter enforces learnerId ownership; root POST is disabled.
                if (path.startsWith("/api/v1/exercises/") || path.equals("/api/v1/exercises"))
                    continue; // Curated shared exercise catalog, never user-upload media.
                if (path.equals("/api/v1/library/materials") || path.startsWith("/api/v1/library/materials/"))
                    continue; // Account-owned: every read and edit derives owner from trusted CurrentActor.
                if (path.equals("/api/v1/music-catalog/works")
                    || path.startsWith("/api/v1/music-catalog/works/")
                    || path.startsWith("/api/v1/music-catalog/arrangements/"))
                    continue; // #194 private indexed metadata, CurrentActor account scoping.
                if (path.equals("/api/v1/community/publications"))
                    continue; // #166: admitted-only published search; never returns private sources.
                if (path.equals("/api/v1/community/publications/{publicationId}")
                    || path.equals("/api/v1/community/publications/{publicationId}/files/{assetId}/content")
                    || path.equals("/api/v1/community/publications/{publicationId}/files/{assetId}/preview")
                    || path.equals("/api/v1/community/publications/{publicationId}/hide")
                    || path.equals("/api/v1/admin/catalog/publications/{publicationId}/hide"))
                    continue; // #165: authenticated PUBLISHED+READY checks per object; hide requires OWNER and audit.
                if (Set.of("/api/v1/community/works", "/api/v1/community/works/{id}",
                        "/api/v1/community/work-candidates", "/api/v1/community/work-candidates/{id}",
                        "/api/v1/admin/community/works/{id}", "/api/v1/admin/community/works/{id}/verify",
                        "/api/v1/admin/community/works/{id}/retire", "/api/v1/admin/community/works/{id}/split",
                        "/api/v1/admin/community/works/{id}/split/dry-run").contains(path))
                    continue; // #204: admitted published-only read; creator/OWNER candidate review;
                              // OWNER identity commands + If-Match, reason, persisted split plan and audit.
                if (SHARED_API.contains(path)) continue;
                unclassified.add(method.getBeanType().getSimpleName() + " " + path);
            }
        });
        assertTrue(seen.contains("/api/v1/invitations/activate"),
            "Invitation activation mapping must be discovered");
        assertTrue(seen.contains("/api/v1/learners/{learnerId}"),
            "Learner ownership mapping must be discovered");
        assertTrue(seen.stream().anyMatch(p -> p.startsWith("/api/v1/exercises/")),
            "Shared catalog resource mappings must be discovered");
        assertTrue(unclassified.isEmpty(),
            "New API endpoint needs object-level auth review: " + unclassified);
    }
}
