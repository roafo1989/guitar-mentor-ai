package ai.guitarmentor.application.learning;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Closed-by-default local internal writer authorization.
 * No public API routes issue reviewer credentials; #38 must replace this with
 * authenticated principals before internet deployment.
 * Only reviewer ID (never its proof) is persisted as assessment provenance.
 */
@Component
public class LearningAssessmentAuthorizer {
    private final String reviewPrincipal;
    private final String reviewToken;
    private final String measurementPrincipal;
    private final String measurementToken;
    private final Set<String> validatedMethods;
    // Exact grants: method:version|skillCode|criterionCode. No wildcard or implicit
    // access to all VALIDATED_MEASUREMENT criteria from a method's mere registration.
    private final Set<String> validatedMethodScopes;

    public LearningAssessmentAuthorizer(
            @Value("${guitarmentor.learning.review-principal:}") String reviewPrincipal,
            @Value("${guitarmentor.learning.review-token:}") String reviewToken,
            @Value("${guitarmentor.learning.measurement-principal:}") String measurementPrincipal,
            @Value("${guitarmentor.learning.measurement-token:}") String measurementToken,
            @Value("${guitarmentor.learning.validated-methods:}") String methods,
            @Value("${guitarmentor.learning.validated-method-scopes:}") String scopes) {
        this.reviewPrincipal=reviewPrincipal;
        this.reviewToken=reviewToken;
        this.measurementPrincipal=measurementPrincipal;
        this.measurementToken=measurementToken;
        this.validatedMethods=Arrays.stream(methods.split(","))
                .map(String::trim).filter(s->!s.isBlank()).collect(Collectors.toUnmodifiableSet());
        this.validatedMethodScopes=Arrays.stream(scopes.split(";"))
                .map(String::trim).filter(s->!s.isBlank())
                .filter(s->s.matches("[A-Za-z0-9_.:-]{1,161}\\|[A-Z0-9_]{1,50}\\|[A-Z0-9_]{1,60}"))
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean authorized(String kind, String method, String version,
            String principal, String proof) {
        if (principal==null || proof==null || "LEGACY_UNVERIFIED".equals(principal)
                || !principal.matches("[A-Za-z0-9_.:-]{1,120}"))
            return false;
        if ("HUMAN_REVIEW".equals(kind)) {
            return matches(principal,proof,reviewPrincipal,reviewToken);
        }
        if ("VALIDATED_MEASUREMENT".equals(kind)) {
            return validatedMethods.contains(method+":"+version)
                    && matches(principal,proof,measurementPrincipal,measurementToken);
        }
        return false;
    }

    /**
     * Criterion-level policy: REVIEW may grade manual criteria; automated methods
     * require BOTH global approval and an explicit method/skill/criterion grant.
     * Unassessed/uncertain observations never produce MEETS or a verified level.
     */
    public boolean canAssess(String kind,String method,String version,
            String skillCode,String criterionCode,String policy) {
        if ("HUMAN_REVIEW".equals(kind))
            return "MANUAL_OR_NOT_ASSESSED".equals(policy);
        if ("VALIDATED_MEASUREMENT".equals(kind))
            return "VALIDATED_MEASUREMENT".equals(policy)
                && validatedMethods.contains(method+":"+version)
                && validatedMethodScopes.contains(method+":"+version+"|"+skillCode+"|"+criterionCode);
        return false;
    }

    private static boolean matches(String principal,String proof,String allowed,String secret) {
        if (allowed==null || allowed.isBlank() || secret==null || secret.length()<16)
            return false;
        // Compare both principal and proof without revealing credentials in logs/errors.
        return MessageDigest.isEqual(principal.getBytes(StandardCharsets.UTF_8),
                                     allowed.getBytes(StandardCharsets.UTF_8))
            && MessageDigest.isEqual(proof.getBytes(StandardCharsets.UTF_8),
                                     secret.getBytes(StandardCharsets.UTF_8));
    }
}
