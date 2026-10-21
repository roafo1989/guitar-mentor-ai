package ai.guitarmentor.application.learning;

import ai.guitarmentor.application.learning.LearningDtos.AssessmentView;
import ai.guitarmentor.infrastructure.persistence.LearningRepository;
import ai.guitarmentor.infrastructure.persistence.LearningRepository.Result;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal-only entry point: there is intentionally NO REST route allowing a
 * learner/browser to submit scores, change recommendations or claim a skill.
 * #22 may integrate a validated provider here, after its validation is approved.
 */
@Service
public class LearningAssessmentWriter {
    public record CriterionInput(String criterionCode, String status, String outcome,
            String reasonCode, String evidenceText) {}
    public record Input(UUID sourceOperationId, String sourceKind, String methodId,
            String methodVersion, String sourcePrincipal, String sourceProof,
            List<CriterionInput> criteria) {
        // Never disclose the proof through record-generated diagnostics or logs.
        @Override public String toString() {
            return "Input[sourceOperationId="+sourceOperationId
                +",sourceKind="+sourceKind+",methodId="+methodId
                +",methodVersion="+methodVersion+",sourcePrincipal="+sourcePrincipal
                +",sourceProof=<REDACTED>,criteriaCount="
                +(criteria==null ? "null" : criteria.size())+"]";
        }
    }

    private final LearningRepository repo;
    private final LearningService learning;
    private final LearningAssessmentAuthorizer authority;

    public LearningAssessmentWriter(LearningRepository repo, LearningService learning,
            LearningAssessmentAuthorizer authority) {
        this.repo=repo;
        this.learning=learning;
        this.authority=authority;
    }

    @Transactional
    public AssessmentView record(UUID learnerId, UUID sessionId, UUID taskId,
            UUID attemptId, Input input) {
        learning.available();
        validateInput(input);
        // Fail closed: both the principal and its private server-provisioned proof
        // are required. Never persist, log or return the proof.
        if (!authority.authorized(input.sourceKind(),input.methodId(),
                input.methodVersion(),input.sourcePrincipal(),input.sourceProof())) invalid();
        learning.lockOwned(learnerId,sessionId,taskId);
        learning.state(taskId,true); // Same lock order as learner decisions.
        if (!repo.attemptBelongs(attemptId,taskId)) {
            throw new LearningProblem(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND");
        }
        var snapshot=repo.criteria(taskId);
        var known=new java.util.HashMap<String,LearningRepository.Criterion>();
        for (var criterion:snapshot) known.put(criterion.code(),criterion);

        var results=new ArrayList<Result>();
        var seen=new HashSet<String>();
        for (var item:input.criteria()) {
            if (item==null || !known.containsKey(item.criterionCode())
                    || !seen.add(item.criterionCode())) invalid();
            if (!"ASSESSED".equals(item.status()) && !"UNCERTAIN".equals(item.status())
                    && !"NOT_ASSESSED".equals(item.status())) invalid();
            boolean assessed="ASSESSED".equals(item.status());
            if (assessed != ("MEETS".equals(item.outcome())
                    || "NEEDS_WORK".equals(item.outcome()))) invalid();
            if (!assessed && (item.reasonCode()==null || item.reasonCode().isBlank())) invalid();
            if (item.reasonCode()!=null && !item.reasonCode().matches("[A-Z_]{2,80}")) invalid();
            if (item.evidenceText()!=null && item.evidenceText().length()>2000) invalid();
            // A method grant cannot be reused for an unrelated criterion.
            // HUMAN_REVIEW cannot claim automated-only criteria and vice versa.
            if (assessed) {
                var criterion=known.get(item.criterionCode());
                if (!authority.canAssess(input.sourceKind(),input.methodId(),
                        input.methodVersion(),criterion.skillCode(),criterion.code(),
                        criterion.policy())) invalid();
            }
            results.add(new Result(item.criterionCode(),item.status(),item.outcome(),
                item.reasonCode(),item.evidenceText()));
        }
        results.sort(java.util.Comparator.comparing(Result::code));
        String recommendation=LearningRules.recommendation(snapshot,results);

        String canonical=input.sourceOperationId()+"|"+input.sourceKind()+"|"
                +input.sourcePrincipal()+"|"+input.methodId()+"|"
                +input.methodVersion()+"|"+recommendation;
        for (var result:results) {
            canonical+="|"+result.code()+"|"+result.status()+"|"
                +Objects.toString(result.outcome(),"")+"|"
                +Objects.toString(result.reasonCode(),"")+"|"
                +java.util.Base64.getEncoder().encodeToString(
                    Objects.toString(result.evidenceText(),"").getBytes(
                        java.nio.charset.StandardCharsets.UTF_8));
        }
        String hash=LearningService.fingerprint(canonical);
        var existing=repo.assessmentByOperation(attemptId,input.sourceOperationId());
        if (existing.isPresent()) {
            if (!existing.get().fingerprint().equals(hash)) {
                throw new LearningProblem(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT");
            }
            return learning.assessmentView(taskId,existing.get());
        }
        // PASS is meaningful only AFTER the upload has successfully completed
        // its technical validation. PROCESSED is monotonic under the worker API.
        if ("PASS".equals(recommendation) && !repo.technicallyProcessed(attemptId)) {
            throw new LearningProblem(HttpStatus.CONFLICT,"ASSESSMENT_NOT_READY");
        }
        UUID id=UUID.randomUUID();
        int revision=repo.nextRevision(attemptId);
        repo.insertAssessment(id,taskId,attemptId,revision,input.sourceOperationId(),
            hash,input.sourceKind(),input.sourcePrincipal(),input.methodId(),
            input.methodVersion(),recommendation,Instant.now());
        for (var result:results) repo.insertCriterionResult(id,taskId,result);
        // Neither terminal state nor learner_skill_signal_history is modified.
        return learning.assessmentView(taskId,repo.latestAssessment(attemptId).orElseThrow());
    }

    private static void validateInput(Input input) {
        if (input==null || input.sourceOperationId()==null
                || (!"HUMAN_REVIEW".equals(input.sourceKind())
                    && !"VALIDATED_MEASUREMENT".equals(input.sourceKind()))
                || input.methodId()==null || !input.methodId().matches("[A-Za-z0-9_.:-]{1,100}")
                || input.methodVersion()==null
                || !input.methodVersion().matches("[A-Za-z0-9_.:-]{1,60}")
                || input.criteria()==null) invalid();
    }

    private static void invalid() {
        throw new LearningProblem(HttpStatus.BAD_REQUEST,"INVALID_ASSESSMENT");
    }
}
