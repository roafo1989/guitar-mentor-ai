package ai.guitarmentor.application.learning;

import ai.guitarmentor.infrastructure.persistence.LearningRepository.Criterion;
import ai.guitarmentor.infrastructure.persistence.LearningRepository.Result;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** No LLM text, technical status, or student action may award PASS. */
public final class LearningRules {
    private LearningRules() {}

    public static String recommendation(List<Criterion> snapshot, List<Result> results) {
        if (snapshot.isEmpty()) return "UNCERTAIN";
        Map<String,Result> byCode=results.stream()
                .collect(Collectors.toMap(Result::code,r->r));
        boolean revise=false;
        boolean uncertain=false;
        for (var criterion : snapshot) {
            var result=byCode.get(criterion.code());
            if (result == null || !"ASSESSED".equals(result.status())) {
                uncertain=true;
            } else if ("NEEDS_WORK".equals(result.outcome())) {
                revise=true;
            } else if (!"MEETS".equals(result.outcome())) {
                uncertain=true;
            }
        }
        if (revise) return "REVISE";
        return uncertain ? "UNCERTAIN" : "PASS";
    }

    public static String resultingState(String action, String recommendation) {
        if ("CONTINUE_PRACTICING".equals(action)) return "ACTIVE";
        return "PASS".equals(recommendation)
                ? "COMPLETED_PASSED" : "COMPLETED_BY_LEARNER";
    }
}
