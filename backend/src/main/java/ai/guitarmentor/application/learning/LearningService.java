package ai.guitarmentor.application.learning;

import ai.guitarmentor.application.learning.LearningDtos.*;
import ai.guitarmentor.infrastructure.persistence.DiagnosticSessionRepository;
import ai.guitarmentor.infrastructure.persistence.LearningRepository;
import ai.guitarmentor.infrastructure.persistence.LearningRepository.Assessment;
import ai.guitarmentor.infrastructure.persistence.LearningRepository.State;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LearningService {
    private final LearningRepository repo;
    private final DiagnosticSessionRepository sessions;
    private final boolean enabled;

    public LearningService(LearningRepository repo, DiagnosticSessionRepository sessions,
            @Value("${guitarmentor.learning.enabled:false}") boolean enabled) {
        this.repo=repo;
        this.sessions=sessions;
        this.enabled=enabled;
    }

    void available() {
        if (!enabled) throw new LearningProblem(HttpStatus.SERVICE_UNAVAILABLE,"LEARNING_DISABLED");
    }

    void owned(UUID learnerId, UUID sessionId, UUID taskId) {
        if (!repo.owns(learnerId,sessionId,taskId)) {
            throw new LearningProblem(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND");
        }
    }

    /**
     * Writer, decision and upload all use session-before-learning-state locking.
     * Never lock task state before the session row.
     */
    void lockOwned(UUID learnerId, UUID sessionId, UUID taskId) {
        sessions.findLockedOwnedById(learnerId,sessionId)
            .orElseThrow(()->new LearningProblem(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND"));
        owned(learnerId,sessionId,taskId);
    }

    State state(UUID taskId, boolean lock) {
        return repo.state(taskId,lock).orElseThrow(
            ()->new LearningProblem(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND"));
    }

    @Transactional(readOnly=true)
    public LearningView learning(UUID learnerId, UUID sessionId, UUID taskId) {
        available();
        owned(learnerId,sessionId,taskId);
        var state=state(taskId,false);
        var attempt=repo.latestAttempt(taskId);
        var latestAssessment=attempt.flatMap(a->repo.latestAssessment(a.id()));
        return new LearningView(taskId,state.state(),state.version(),
            "ACTIVE".equals(state.state()) && attempt.isPresent()
                ? List.of("CONTINUE_PRACTICING","COMPLETE") : List.of(),
            attempt.map(LearningRepository.Attempt::id).orElse(null),
            attempt.isEmpty() ? "NO_ATTEMPT"
                : latestAssessment.isEmpty() ? "NO_ASSESSMENT" : "AVAILABLE",
            latestAssessment.map(Assessment::id).orElse(null),
            repo.latestDecision(taskId).orElse(null),nextAvailability(taskId,state.state()),
            repo.successorLink(taskId).orElse(null));
    }

    @Transactional(readOnly=true)
    public Page<AssessmentView> assessments(UUID learnerId, UUID sessionId,
            UUID taskId, UUID attemptId, Integer limit, String cursor) {
        available();
        owned(learnerId,sessionId,taskId);
        if (!repo.attemptBelongs(attemptId,taskId)) {
            throw new LearningProblem(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND");
        }
        int size=pageLimit(limit);
        Integer before=null;
        if (cursor!=null) {
            String[] tokens=parseCursor(cursor,3);
            if (!"A".equals(tokens[0]) || !attemptId.toString().equals(tokens[1])) {
                throw invalidPagination();
            }
            try {
                before=Integer.parseInt(tokens[2]);
                if (before<=0) throw invalidPagination();
            } catch (NumberFormatException invalid) { throw invalidPagination(); }
        }
        var rows=repo.assessments(attemptId,before,size+1);
        var items=rows.stream().limit(size).map(a->assessmentView(taskId,a)).toList();
        String next=rows.size()>size
            ? cursor("A|"+attemptId+"|"+rows.get(size-1).revision()) : null;
        return new Page<>(items,next);
    }

    AssessmentView assessmentView(UUID taskId, Assessment assessment) {
        var resultByCode=new HashMap<String,LearningRepository.Result>();
        for (var result:repo.results(assessment.id())) resultByCode.put(result.code(),result);
        var criteria=new ArrayList<CriterionResultView>();
        for (var criterion:repo.criteria(taskId)) {
            var result=resultByCode.get(criterion.code());
            criteria.add(new CriterionResultView(criterion.code(),criterion.skillCode(),
                criterion.label(),criterion.observable(),
                result==null ? "NOT_ASSESSED" : result.status(),
                result==null ? null : result.outcome(),
                result==null ? "MISSING_RESULT" : result.reasonCode(),
                result==null ? null : result.evidenceText(),null));
        }
        return new AssessmentView(assessment.id(),assessment.attemptId(),
            assessment.revision(),assessment.sourceKind(),assessment.sourcePrincipal(),assessment.methodId(),
            assessment.methodVersion(),assessment.recommendation(),assessment.createdAt(),
            criteria,"LEGACY_UNVERIFIED".equals(assessment.sourcePrincipal())
                ? List.of("Legacy assessment has unverified provenance and cannot grant PASS",
                    "Criterion evidence is not a confirmed skill level")
                : List.of("Criterion evidence is not a confirmed skill level"));
    }

    @Transactional(readOnly=true)
    public Page<DecisionView> decisions(UUID learnerId, UUID sessionId,
            UUID taskId, Integer limit, String cursor) {
        available();
        owned(learnerId,sessionId,taskId);
        int size=pageLimit(limit);
        Instant beforeTime=null;
        UUID beforeId=null;
        if (cursor!=null) {
            String[] parts=parseCursor(cursor,4);
            if (!"D".equals(parts[0]) || !taskId.toString().equals(parts[1])) {
                throw invalidPagination();
            }
            try {
                beforeTime=Instant.parse(parts[2]);
                beforeId=UUID.fromString(parts[3]);
            } catch (RuntimeException invalid) { throw invalidPagination(); }
        }
        var rows=repo.decisions(taskId,beforeTime,beforeId,size+1);
        var items=rows.stream().limit(size).toList();
        String next=rows.size()>size
            ? cursor("D|"+taskId+"|"+items.getLast().recordedAt()
                +"|"+items.getLast().decisionId()) : null;
        return new Page<>(items,next);
    }

    @Transactional(readOnly=true)
    public Page<SkillEvidenceView> evidence(UUID learnerId, UUID sessionId,
            UUID taskId, Integer limit, String cursor) {
        available();
        owned(learnerId,sessionId,taskId);
        int size=pageLimit(limit);
        Instant before=null;
        UUID assessmentId=null;
        String criterionCode=null;
        if (cursor!=null) {
            String[] parts=parseCursor(cursor,5);
            if (!"E".equals(parts[0]) || !taskId.toString().equals(parts[1]))
                throw invalidPagination();
            try {
                before=Instant.parse(parts[2]);
                assessmentId=UUID.fromString(parts[3]);
                criterionCode=parts[4];
                if (!criterionCode.matches("[A-Za-z0-9_-]{1,60}")) throw invalidPagination();
            } catch (RuntimeException invalid) { throw invalidPagination(); }
        }
        var rows=repo.evidence(taskId,before,assessmentId,criterionCode,size+1);
        var items=rows.stream().limit(size).toList();
        String next=rows.size()>size ? cursor("E|"+taskId+"|"
                +items.getLast().createdAt()+"|"+items.getLast().assessmentId()
                +"|"+items.getLast().criterionCode()) : null;
        return new Page<>(items,next);
    }

    @Transactional
    public CreatedDecision decide(UUID learnerId,UUID sessionId,UUID taskId,
            DecisionRequest request) {
        available();
        if (request==null || request.requestId()==null || request.attemptId()==null
                || request.expectedVersion()==null || request.expectedVersion()<0
                || (!"COMPLETE".equals(request.action())
                    && !"CONTINUE_PRACTICING".equals(request.action()))) {
            throw new LearningProblem(HttpStatus.BAD_REQUEST,"INVALID_DECISION");
        }
        String reason=request.reason()==null ? null : request.reason().trim();
        if (reason!=null && reason.length()>2000) {
            throw new LearningProblem(HttpStatus.BAD_REQUEST,"REASON_TOO_LONG");
        }
        if ("".equals(reason)) reason=null;
        String fingerprint=fingerprint(request.requestId()+"|"+request.attemptId()+"|"
            +request.action()+"|"+request.expectedVersion()+"|"
            +Objects.toString(request.baseAssessmentId(),"")+"|"
            +Base64.getEncoder().encodeToString(
                Objects.toString(reason,"").getBytes(StandardCharsets.UTF_8)));

        lockOwned(learnerId,sessionId,taskId);
        State current=state(taskId,true);
        // Replay MUST precede version/terminal checks; exact prior results survive.
        var existing=repo.decisionByRequest(taskId,request.requestId());
        if (existing.isPresent()) {
            if (!fingerprint.equals(repo.decisionFingerprint(taskId,request.requestId())
                    .orElseThrow())) {
                throw new LearningProblem(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT");
            }
            return new CreatedDecision(result(existing.get()),true);
        }
        if (repo.latestAttempt(taskId).isEmpty()) {
            throw new LearningProblem(HttpStatus.CONFLICT,"NO_ATTEMPT");
        }
        if (!repo.attemptBelongs(request.attemptId(),taskId)) {
            throw new LearningProblem(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND");
        }
        if (!"ACTIVE".equals(current.state())) {
            throw new LearningProblem(HttpStatus.CONFLICT,"TASK_COMPLETED");
        }
        if (current.version()!=request.expectedVersion()) {
            throw new LearningProblem(HttpStatus.CONFLICT,"VERSION_CONFLICT");
        }
        var assessment=repo.latestAssessment(request.attemptId());
        UUID actualAssessmentId=assessment.map(Assessment::id).orElse(null);
        if (!Objects.equals(actualAssessmentId,request.baseAssessmentId())) {
            throw new LearningProblem(HttpStatus.CONFLICT,"ASSESSMENT_CHANGED");
        }
        String recommendation=assessment.map(Assessment::recommendation).orElse("UNCERTAIN");
        // Protect against assessments written by pre-fix code or direct DB changes.
        // The upload worker cannot normally turn a finalized PROCESSED into FAILED.
        if ("PASS".equals(recommendation)
                && (!repo.technicallyProcessed(request.attemptId())
                    || assessment.isEmpty()
                    || assessment.get().sourcePrincipal()==null
                    || "LEGACY_UNVERIFIED".equals(assessment.get().sourcePrincipal()))) {
            // Historical V15 rows had only a caller-supplied trust boolean and
            // were backfilled as LEGACY_UNVERIFIED in V16. They remain visible
            // but can never confer a verified PASS.
            recommendation="UNCERTAIN";
        }
        String resulting=LearningRules.resultingState(request.action(),recommendation);
        String reasonCode="CONTINUE_PRACTICING".equals(request.action())
            ? "CONTINUE_PRACTICING"
            : "COMPLETED_PASSED".equals(resulting) ? "ASSESSMENT_PASSED" : "LEARNER_CHOICE";
        long nextVersion=current.version()+1;
        UUID id=UUID.randomUUID();
        Instant now=Instant.now();
        repo.insertDecision(id,taskId,request.attemptId(),actualAssessmentId,
            request.requestId(),fingerprint,request.action(),resulting,nextVersion,
            reasonCode,reason,now);
        repo.updateState(taskId,resulting,nextVersion,now);
        return new CreatedDecision(result(repo.decisionByRequest(taskId,request.requestId())
                .orElseThrow()),false);
    }

    public boolean isTerminal(UUID taskId) {
        return repo.state(taskId,false).map(s->!"ACTIVE".equals(s.state())).orElse(false);
    }

    private String nextAvailability(UUID taskId, String state) {
        if (!enabled || "ACTIVE".equals(state)) return "NOT_IMPLEMENTED";
        return repo.successor(taskId).isPresent() ? "SUCCESSOR_EXISTS" : "MANUAL_SELECTION";
    }

    private DecisionResult result(DecisionView decision) {
        return new DecisionResult(decision.decisionId(),decision.taskId(),
            decision.resultingState(),decision.resultingVersion(),decision.assessmentId(),
            decision.recordedAt(),nextAvailability(decision.taskId(),decision.resultingState()),
            repo.successorLink(decision.taskId()).orElse(null));
    }

    private static int pageLimit(Integer limit) {
        if (limit==null) return 20;
        if (limit<1 || limit>100) throw invalidPagination();
        return limit;
    }

    private static LearningProblem invalidPagination() {
        return new LearningProblem(HttpStatus.BAD_REQUEST,"INVALID_PAGINATION");
    }

    private static String cursor(String raw) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String[] parseCursor(String encoded,int length) {
        if (encoded.isBlank() || encoded.length()>256) throw invalidPagination();
        try {
            String raw=new String(Base64.getUrlDecoder().decode(encoded),StandardCharsets.UTF_8);
            String[] parts=raw.split("\\|",-1);
            if (parts.length!=length) throw invalidPagination();
            return parts;
        } catch (IllegalArgumentException invalid) { throw invalidPagination(); }
    }

    static String fingerprint(String data) {
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256")
                .digest(data.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable",impossible);
        }
    }
}
