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
        "/api/v1/system/ping"
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
