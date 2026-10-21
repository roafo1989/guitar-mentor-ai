package ai.guitarmentor.application.learning;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LearningAssessmentAuthorizerTest {
    private static final String TOKEN="unit-test-only-secret-proof-18";

    private LearningAssessmentAuthorizer approved() {
        return new LearningAssessmentAuthorizer(
            "HUMAN_A",TOKEN,"MEASURE_A",TOKEN,
            "BEAT:v1,TONE:v2",
            "BEAT:v1|RHYTHM|PULSE_STEADY;"
              +"TONE:v2|PITCH|FREQUENCY_ACCURATE");
    }

    @Test void measurementNeedsExactMethodVersionSkillCriterionAndPolicy() {
        var policy=approved();
        assertTrue(policy.authorized("VALIDATED_MEASUREMENT","BEAT","v1","MEASURE_A",TOKEN));
        assertTrue(policy.canAssess("VALIDATED_MEASUREMENT","BEAT","v1",
            "RHYTHM","PULSE_STEADY","VALIDATED_MEASUREMENT"));
        assertFalse(policy.canAssess("VALIDATED_MEASUREMENT","BEAT","v1",
            "PITCH","FREQUENCY_ACCURATE","VALIDATED_MEASUREMENT"));
        assertFalse(policy.canAssess("VALIDATED_MEASUREMENT","BEAT","v1",
            "RHYTHM","TEMPO_UNDER_CONTROL","VALIDATED_MEASUREMENT"));
        assertFalse(policy.canAssess("VALIDATED_MEASUREMENT","BEAT","v2",
            "RHYTHM","PULSE_STEADY","VALIDATED_MEASUREMENT"));
        assertFalse(policy.canAssess("VALIDATED_MEASUREMENT","BEAT","v1",
            "RHYTHM","PULSE_STEADY","MANUAL_OR_NOT_ASSESSED"));
        assertFalse(policy.canAssess("VALIDATED_MEASUREMENT","UNAPPROVED","v1",
            "RHYTHM","PULSE_STEADY","VALIDATED_MEASUREMENT"));
    }

    @Test void humanReviewCannotSatisfyMeasurementOnlyCriterion() {
        var policy=approved();
        assertTrue(policy.authorized("HUMAN_REVIEW","MANUAL","v1","HUMAN_A",TOKEN));
        assertTrue(policy.canAssess("HUMAN_REVIEW","MANUAL","v1",
            "RHYTHM","PULSE_STEADY","MANUAL_OR_NOT_ASSESSED"));
        assertFalse(policy.canAssess("HUMAN_REVIEW","MANUAL","v1",
            "RHYTHM","PULSE_STEADY","VALIDATED_MEASUREMENT"));
    }

    @Test void methodApprovalWithoutCriterionScopeDoesNotGrantAnything() {
        var noScopes=new LearningAssessmentAuthorizer("HUMAN_A",TOKEN,
            "MEASURE_A",TOKEN,"BEAT:v1","");
        assertTrue(noScopes.authorized("VALIDATED_MEASUREMENT","BEAT","v1","MEASURE_A",TOKEN));
        assertFalse(noScopes.canAssess("VALIDATED_MEASUREMENT","BEAT","v1",
            "RHYTHM","PULSE_STEADY","VALIDATED_MEASUREMENT"));
    }

    @Test void invalidOrUntrustedPrincipalsNeverPass() {
        var policy=approved();
        assertFalse(policy.authorized("HUMAN_REVIEW","MANUAL","v1",
            "LEGACY_UNVERIFIED",TOKEN));
        assertFalse(policy.authorized("HUMAN_REVIEW","MANUAL","v1",
            "HUMAN_A","fake-proof"));
        assertFalse(policy.authorized("VALIDATED_MEASUREMENT","BEAT","v1",
            "HUMAN_A",TOKEN));
        assertFalse(policy.authorized("VALIDATED_MEASUREMENT","BEAT","v9",
            "MEASURE_A",TOKEN));
    }

    @Test void proofIsNotPrintedInInputRecord() {
        var input=new LearningAssessmentWriter.Input(java.util.UUID.randomUUID(),
            "HUMAN_REVIEW","MANUAL","v1","HUMAN_A",TOKEN,java.util.List.of());
        String diagnostics=input.toString();
        assertTrue(diagnostics.contains("<REDACTED>"));
        assertFalse(diagnostics.contains(TOKEN));
        assertFalse(diagnostics.contains("unit-test-only"));
    }
}
