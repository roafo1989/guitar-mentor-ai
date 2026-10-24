package ai.guitarmentor.infrastructure.persistence;

import ai.guitarmentor.application.learning.LearningDtos.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Append-only pedagogical history. Transaction boundaries and lock order are
 * owned by LearningService/LearningAssessmentWriter, never by this repository.
 */
@Repository
public class LearningRepository {
    private final JdbcTemplate jdbc;
    public LearningRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record State(String state, long version) {}
    public record Attempt(UUID id, int number) {}
    public record Assessment(UUID id, UUID attemptId, int revision, UUID operationId,
            String fingerprint, String sourceKind, String sourcePrincipal, String methodId,
            String methodVersion, String recommendation, Instant createdAt) {}
    public record Criterion(String code, String skillCode, String label,
            String observable, String policy, int displayOrder) {}
    public record Result(String code, String status, String outcome, String reasonCode,
            String evidenceText) {}

    public boolean owns(UUID learnerId, UUID sessionId, UUID taskId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM diagnostic_tasks t
              JOIN diagnostic_sessions s ON s.id=t.diagnostic_session_id
              JOIN instrument_profiles p ON p.id=s.instrument_profile_id
              WHERE t.id=? AND s.id=? AND p.learner_id=?)
            """, Boolean.class, taskId, sessionId, learnerId));
    }

    public void initialize(UUID taskId) {
        jdbc.update("""
            INSERT INTO task_learning_state(task_id,state,version,updated_at)
            VALUES (?,'ACTIVE',0,CURRENT_TIMESTAMP) ON CONFLICT (task_id) DO NOTHING
            """, taskId);
        jdbc.update("""
            INSERT INTO task_criterion_snapshots
            (task_id,criterion_code,skill_code,label,observable_result,
              assessment_policy,display_order,captured_at)
            SELECT t.id,c.criterion_code,c.skill_code,c.label,c.observable_result,
                   c.assessment_policy,c.display_order,CURRENT_TIMESTAMP
              FROM diagnostic_tasks t JOIN exercise_criteria c ON c.exercise_id=t.exercise_id
             WHERE t.id=?
            ON CONFLICT (task_id,criterion_code) DO NOTHING
            """, taskId);
    }

    public Optional<State> state(UUID taskId, boolean locked) {
        String sql = "SELECT state,version FROM task_learning_state WHERE task_id=?"
                + (locked ? " FOR UPDATE" : "");
        return jdbc.query(sql, (rs,n) -> new State(rs.getString(1),rs.getLong(2)),taskId)
                .stream().findFirst();
    }

    public Optional<Attempt> latestAttempt(UUID taskId) {
        return jdbc.query("""
            SELECT id,attempt_number FROM submission_attempts
            WHERE diagnostic_task_id=? ORDER BY attempt_number DESC LIMIT 1
            """, (rs,n) -> new Attempt((UUID)rs.getObject(1),rs.getInt(2)),taskId)
                .stream().findFirst();
    }

    public boolean attemptBelongs(UUID attemptId, UUID taskId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM submission_attempts
              WHERE id=? AND diagnostic_task_id=?)
            """,Boolean.class,attemptId,taskId));
    }

    /** A PASS is valid only when the upload's technical validation is finalized. */
    public boolean technicallyProcessed(UUID attemptId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM submission_attempts
              WHERE id=? AND status='PROCESSED' AND processing_finalized_at IS NOT NULL)
            """,Boolean.class,attemptId));
    }

    // Bound parameters per statement even when the legacy GET reads all attempts.
    // The new paginated GET (<=50 items) still issues exactly one assessment query.
    private static final int ASSESSMENT_LOOKUP_BATCH_SIZE = 500;

    public Set<UUID> assessedAttemptIds(List<UUID> attemptIds) {
        if (attemptIds.isEmpty()) return Collections.emptySet();
        Set<UUID> assessed = new HashSet<>();
        for (int offset = 0; offset < attemptIds.size(); offset += ASSESSMENT_LOOKUP_BATCH_SIZE) {
            List<UUID> batch = attemptIds.subList(offset,
                    Math.min(offset + ASSESSMENT_LOOKUP_BATCH_SIZE, attemptIds.size()));
            String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
            assessed.addAll(jdbc.query(
                "SELECT DISTINCT attempt_id FROM attempt_assessments WHERE attempt_id IN (" + placeholders + ")",
                (rs, row) -> (UUID) rs.getObject(1), batch.toArray()));
        }
        return assessed;
    }

    public boolean hasAssessment(UUID attemptId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM attempt_assessments WHERE attempt_id=?)",
            Boolean.class,attemptId));
    }

    public Optional<Assessment> latestAssessment(UUID attemptId) {
        return jdbc.query("""
            SELECT * FROM attempt_assessments WHERE attempt_id=?
            ORDER BY revision DESC LIMIT 1
            """, (rs,n)-> assessment(rs), attemptId).stream().findFirst();
    }

    public Optional<Assessment> assessmentByOperation(UUID attemptId, UUID operationId) {
        return jdbc.query("""
            SELECT * FROM attempt_assessments WHERE attempt_id=? AND source_operation_id=?
            """,(rs,n)-> assessment(rs),attemptId,operationId).stream().findFirst();
    }

    public List<Assessment> assessments(UUID attemptId, Integer beforeRevision, int limit) {
        String sql = "SELECT * FROM attempt_assessments WHERE attempt_id=?"
            + (beforeRevision == null ? "" : " AND revision < ?")
            + " ORDER BY revision DESC LIMIT ?";
        Object[] params = beforeRevision == null
                ? new Object[]{attemptId,limit} : new Object[]{attemptId,beforeRevision,limit};
        return jdbc.query(sql,(rs,n)->assessment(rs),params);
    }

    private static Assessment assessment(ResultSet rs) throws SQLException {
        return new Assessment((UUID)rs.getObject("id"),(UUID)rs.getObject("attempt_id"),
            rs.getInt("revision"),(UUID)rs.getObject("source_operation_id"),
            rs.getString("input_fingerprint"),rs.getString("source_kind"),
            rs.getString("source_principal"),rs.getString("method_id"),rs.getString("method_version"),
            rs.getString("recommendation"),rs.getTimestamp("created_at").toInstant());
    }

    public List<Criterion> criteria(UUID taskId) {
        return jdbc.query("""
            SELECT criterion_code,skill_code,label,observable_result,assessment_policy,display_order
            FROM task_criterion_snapshots WHERE task_id=? ORDER BY display_order,criterion_code
            """,(rs,n)-> new Criterion(rs.getString(1),rs.getString(2),rs.getString(3),
                    rs.getString(4),rs.getString(5),rs.getInt(6)),taskId);
    }

    public List<Result> results(UUID assessmentId) {
        return jdbc.query("""
            SELECT criterion_code,status,outcome,reason_code,evidence_text
            FROM assessment_criterion_results WHERE assessment_id=?
            ORDER BY criterion_code
            """,(rs,n)-> new Result(rs.getString(1),rs.getString(2),rs.getString(3),
                    rs.getString(4),rs.getString(5)),assessmentId);
    }

    public int nextRevision(UUID attemptId) {
        return jdbc.queryForObject("""
            SELECT COALESCE(MAX(revision),0)+1 FROM attempt_assessments WHERE attempt_id=?
            """,Integer.class,attemptId);
    }

    public void insertAssessment(UUID id, UUID taskId, UUID attemptId, int revision,
            UUID operationId, String fingerprint, String source, String principal, String methodId,
            String methodVersion, String recommendation, Instant time) {
        jdbc.update("""
            INSERT INTO attempt_assessments
              (id,task_id,attempt_id,revision,source_operation_id,input_fingerprint,
               source_kind,source_principal,method_id,method_version,recommendation,created_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,taskId,attemptId,revision,operationId,fingerprint,source,principal,
                methodId,methodVersion,recommendation,Timestamp.from(time));
    }

    public void insertCriterionResult(UUID assessmentId, UUID taskId, Result result) {
        jdbc.update("""
            INSERT INTO assessment_criterion_results
              (assessment_id,task_id,criterion_code,status,outcome,reason_code,evidence_text)
            VALUES(?,?,?,?,?,?,?)
            """,assessmentId,taskId,result.code(),result.status(),result.outcome(),
                result.reasonCode(),result.evidenceText());
    }

    public Optional<DecisionView> decisionByRequest(UUID taskId, UUID requestId) {
        return jdbc.query("""
            SELECT * FROM learner_decisions WHERE task_id=? AND request_id=?
            """,(rs,n)-> decision(rs),taskId,requestId).stream().findFirst();
    }

    public Optional<String> decisionFingerprint(UUID taskId, UUID requestId) {
        return jdbc.query("""
            SELECT payload_fingerprint FROM learner_decisions WHERE task_id=? AND request_id=?
            """,(rs,n)-> rs.getString(1),taskId,requestId).stream().findFirst();
    }

    public Optional<DecisionView> latestDecision(UUID taskId) {
        return jdbc.query("""
            SELECT * FROM learner_decisions WHERE task_id=?
            ORDER BY created_at DESC,id DESC LIMIT 1
            """,(rs,n)-> decision(rs),taskId).stream().findFirst();
    }

    public List<DecisionView> decisions(UUID taskId, Instant beforeTime, UUID beforeId, int limit) {
        String sql = "SELECT * FROM learner_decisions WHERE task_id=?"
            + (beforeTime == null ? "" : " AND (created_at,id) < (?,?)")
            + " ORDER BY created_at DESC,id DESC LIMIT ?";
        Object[] args = beforeTime == null ? new Object[]{taskId,limit}
            : new Object[]{taskId,Timestamp.from(beforeTime),beforeId,limit};
        return jdbc.query(sql,(rs,n)->decision(rs),args);
    }

    private static DecisionView decision(ResultSet rs) throws SQLException {
        return new DecisionView((UUID)rs.getObject("id"),(UUID)rs.getObject("task_id"),
            (UUID)rs.getObject("attempt_id"),(UUID)rs.getObject("assessment_id"),
            rs.getString("action"),rs.getString("resulting_state"),
            rs.getLong("resulting_version"),rs.getString("reason_code"),
            rs.getString("reason_text"),rs.getTimestamp("created_at").toInstant());
    }

    public void insertDecision(UUID id, UUID taskId, UUID attemptId, UUID assessmentId,
            UUID requestId, String fingerprint, String action, String state,
            long version, String reasonCode, String reason, Instant time) {
        jdbc.update("""
            INSERT INTO learner_decisions
              (id,task_id,attempt_id,assessment_id,request_id,payload_fingerprint,
               action,resulting_state,resulting_version,reason_code,reason_text,created_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,taskId,attemptId,assessmentId,requestId,fingerprint,action,
                state,version,reasonCode,reason,Timestamp.from(time));
    }

    public void updateState(UUID taskId, String newState, long newVersion, Instant time) {
        jdbc.update("""
            UPDATE task_learning_state
            SET state=?,version=?,updated_at=?,completed_at=?
            WHERE task_id=?
            """,newState,newVersion,Timestamp.from(time),
                "ACTIVE".equals(newState) ? null : Timestamp.from(time),taskId);
    }

    /** Descending keyset cursor: timestamp, assessment UUID and criterion code are stable. */
    public List<SkillEvidenceView> evidence(UUID taskId, Instant beforeTime,
            UUID beforeAssessmentId, String beforeCriterion, int limit) {
        String sql="""
            SELECT assessment_id,attempt_id,criterion_code,skill_code,source_kind,
                   source_principal,method_id,method_version,created_at,status,outcome,
                   reason_code,evidence_text
            FROM skill_evidence WHERE task_id=?
            """ + (beforeTime == null ? "" : " AND (created_at,assessment_id,criterion_code) < (?,?,?)")
             + " ORDER BY created_at DESC,assessment_id DESC,criterion_code DESC LIMIT ?";
        Object[] params=beforeTime==null
            ? new Object[]{taskId,limit}
            : new Object[]{taskId,Timestamp.from(beforeTime),beforeAssessmentId,beforeCriterion,limit};
        return jdbc.query(sql,(rs,n)->new SkillEvidenceView(
            (UUID)rs.getObject(1),(UUID)rs.getObject(2),rs.getString(3),
            rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),
            rs.getString(8),rs.getTimestamp(9).toInstant(),rs.getString(10),
            rs.getString(11),rs.getString(12),rs.getString(13),null),params);
    }
}
