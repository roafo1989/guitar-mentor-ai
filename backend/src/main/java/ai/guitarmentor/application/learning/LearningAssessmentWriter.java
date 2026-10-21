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
import org.springframework.jdbc.core.JdbcTemplate;
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
            String methodVersion, boolean trustedInternalSource,
            List<CriterionInput> criteria) {}

    private final LearningRepository repo;
    private final LearningService learning;
    private final JdbcTemplate jdbc;

    public LearningAssessmentWriter(LearningRepository repo, LearningService learning,
            JdbcTemplate jdbc) {
        this.repo=repo;
        this.learning=learning;
        this.jdbc=jdbc;
    }

    @Transactional
    public AssessmentView record(UUID learnerId, UUID sessionId, UUID taskId,
            UUID attemptId, Input input) {
        learning.available();
        validateInput(input);
        learning.lockOwned(learnerId,sessionId,taskId);
        learning.state(taskId,true); // Same lock order as learner decisions.
        if (!repo.attemptBelongs(attemptId,taskId)) {
            throw new LearningProblem(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND");
        }
        var snapshot=repo.criteria(taskId);
        var known=new HashSet<String>();
        for (var criterion:snapshot) known.add(criterion.code());

        var results=new ArrayList<Result>();
        var seen=new HashSet<String>();
        for (var item:input.criteria()) {
            if (item==null || !known.contains(item.criterionCode())
                    || !seen.add(item.criterionCode())) invalid();
            if (!"ASSESSED".equals(item.status()) && !"UNCERTAIN".equals(item.status())
                    && !"NOT_ASSESSED".equals(item.status())) invalid();
            boolean assessed="ASSESSED".equals(item.status());
            if (assessed != ("MEETS".equals(item.outcome())
                    || "NEEDS_WORK".equals(item.outcome()))) invalid();
            if (!assessed && (item.reasonCode()==null || item.reasonCode().isBlank())) invalid();
            if (item.reasonCode()!=null && !item.reasonCode().matches("[A-Z_]{2,80}")) invalid();
            if (item.evidenceText()!=null && item.evidenceText().length()>2000) invalid();
            if ("VALIDATED_MEASUREMENT".equals(input.sourceKind()) && assessed
                    && snapshot.stream().filter(c->c.code().equals(item.criterionCode()))
                        .noneMatch(c->"VALIDATED_MEASUREMENT".equals(c.policy()))) invalid();
            results.add(new Result(item.criterionCode(),item.status(),item.outcome(),
                item.reasonCode(),item.evidenceText()));
        }
        results.sort(java.util.Comparator.comparing(Result::code));
        String recommendation=LearningRules.recommendation(snapshot,results);

        // Technical processing != assessment. A failed media file cannot earn PASS.
        String technicalStatus=jdbc.queryForObject(
            "SELECT status FROM submission_attempts WHERE id=?",String.class,attemptId);
        if ("FAILED".equals(technicalStatus) && "PASS".equals(recommendation)) {
            throw new LearningProblem(HttpStatus.BAD_REQUEST,"INVALID_ASSESSMENT");
        }
        String canonical=input.sourceOperationId()+"|"+input.sourceKind()+"|"
                +input.methodId()+"|"+input.methodVersion()+"|"+recommendation;
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
        UUID id=UUID.randomUUID();
        int revision=repo.nextRevision(attemptId);
        repo.insertAssessment(id,taskId,attemptId,revision,input.sourceOperationId(),
            hash,input.sourceKind(),input.methodId(),input.methodVersion(),
            recommendation,Instant.now());
        for (var result:results) repo.insertCriterionResult(id,taskId,result);
        // Neither terminal state nor learner_skill_signal_history is modified.
        return learning.assessmentView(taskId,repo.latestAssessment(attemptId).orElseThrow());
    }

    private static void validateInput(Input input) {
        if (input==null || input.sourceOperationId()==null || !input.trustedInternalSource()
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
