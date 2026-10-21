package ai.guitarmentor.application.learning;

import ai.guitarmentor.infrastructure.persistence.LearningRepository.Criterion;
import ai.guitarmentor.infrastructure.persistence.LearningRepository.Result;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LearningRulesTest {
    private static final List<Criterion> CRITERIA=List.of(
        new Criterion("PULSE","PULSE","Pulse","Steady pulse","MANUAL_OR_NOT_ASSESSED",10),
        new Criterion("BASS","BASS_PULSE","Bass","Steady bass","MANUAL_OR_NOT_ASSESSED",20)
    );
    private static Result result(String code,String status,String outcome) {
        return new Result(code,status,outcome,"ASSESSED".equals(status)?null:"NOT_MEASURED",null);
    }

    @Test void noEvidenceIsUncertain() {
        assertEquals("UNCERTAIN",LearningRules.recommendation(CRITERIA,List.of()));
        assertEquals("UNCERTAIN",LearningRules.recommendation(List.of(),
                List.of(result("PULSE","ASSESSED","MEETS"))));
    }

    @Test void passNeedsEveryCriterion() {
        assertEquals("UNCERTAIN",LearningRules.recommendation(CRITERIA,
            List.of(result("PULSE","ASSESSED","MEETS"))));
        assertEquals("PASS",LearningRules.recommendation(CRITERIA,
            List.of(result("PULSE","ASSESSED","MEETS"),result("BASS","ASSESSED","MEETS"))));
        assertEquals("REVISE",LearningRules.recommendation(CRITERIA,
            List.of(result("PULSE","ASSESSED","MEETS"),result("BASS","ASSESSED","NEEDS_WORK"))));
        assertEquals("REVISE",LearningRules.recommendation(CRITERIA,
            List.of(result("PULSE","NOT_ASSESSED",null),result("BASS","ASSESSED","NEEDS_WORK"))));
    }

    @Test void learnerCanCompleteAfterRevisingWithoutPassing() {
        assertEquals("COMPLETED_BY_LEARNER",LearningRules.resultingState("COMPLETE","REVISE"));
        assertEquals("COMPLETED_BY_LEARNER",LearningRules.resultingState("COMPLETE","UNCERTAIN"));
        assertEquals("COMPLETED_PASSED",LearningRules.resultingState("COMPLETE","PASS"));
        assertEquals("ACTIVE",LearningRules.resultingState("CONTINUE_PRACTICING","PASS"));
    }
}
