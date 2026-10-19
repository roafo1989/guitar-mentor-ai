package ai.guitarmentor.infrastructure.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read model for the nine stable skill codes; writing verified evidence belongs to #18. */
@Repository
public class SkillMapRepository {
    private final JdbcTemplate jdbc;

    public SkillMapRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record SkillRow(String code, String title, String description, String category, int displayOrder) {}
    public record SignalRow(String skillCode, String sourceType, String claimState, Integer level,
                            Instant recordedAt, String noteCode) {}
    public record CriterionRow(String code, String skillCode, String label, String observableResult,
                               String assessmentPolicy) {}

    public List<SkillRow> skills() {
        return jdbc.query("SELECT code,title,description,category,display_order FROM skill_codes"
                        + " WHERE active=true ORDER BY display_order,code",
                (rs, n) -> new SkillRow(rs.getString("code"), rs.getString("title"),
                        rs.getString("description"), rs.getString("category"),
                        rs.getInt("display_order")));
    }

    public Map<String, List<String>> prerequisites() {
        var result = new HashMap<String, List<String>>();
        jdbc.query("SELECT skill_code,prerequisite_code FROM skill_prerequisites"
                    + " ORDER BY skill_code,prerequisite_code", rs -> {
            result.computeIfAbsent(rs.getString(1), ignored -> new ArrayList<>())
                    .add(rs.getString(2));
        });
        return result;
    }

    public Map<String, String> exerciseRoles(UUID exerciseId) {
        var result = new HashMap<String, String>();
        jdbc.query("SELECT skill_code,target_role FROM exercise_skills WHERE exercise_id=?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    result.put(rs.getString(1), rs.getString(2));
                }, exerciseId);
        return result;
    }

    public List<CriterionRow> exerciseCriteria(UUID exerciseId) {
        return jdbc.query("SELECT criterion_code,skill_code,label,observable_result,assessment_policy"
                        + " FROM exercise_criteria WHERE exercise_id=? ORDER BY display_order,criterion_code",
                (rs, n) -> new CriterionRow(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getString(5)), exerciseId);
    }

    public List<SignalRow> signals(UUID profileId) {
        return jdbc.query("SELECT skill_code,source_type,claim_state,level,recorded_at,note_code"
                       + " FROM learner_skill_signal_history WHERE instrument_profile_id=?"
                       + " ORDER BY recorded_at DESC,id DESC",
                (rs, n) -> new SignalRow(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getObject(4) == null ? null : ((Number) rs.getObject(4)).intValue(),
                        rs.getTimestamp(5).toInstant(), rs.getString(6)), profileId);
    }

    public List<SignalRow> history(UUID profileId, String code, int limit) {
        return jdbc.query("SELECT skill_code,source_type,claim_state,level,recorded_at,note_code"
                        + " FROM learner_skill_signal_history"
                        + " WHERE instrument_profile_id=? AND skill_code=?"
                        + " ORDER BY recorded_at DESC,id DESC LIMIT ?",
                (rs, n) -> new SignalRow(rs.getString(1), rs.getString(2),
                        rs.getString(3), (Integer) rs.getObject(4),
                        rs.getTimestamp(5).toInstant(), rs.getString(6)), profileId, code, limit);
    }

    public void appendOnboardingClaim(UUID profileId, String skill, boolean claimed) {
        jdbc.update("INSERT INTO learner_skill_signal_history"
                        + " (id,instrument_profile_id,skill_code,source_type,claim_state,"
                        + " source_reference,recorded_at,note_code) VALUES (?,?,?,'SELF_REPORTED',?,"
                        + " 'ONBOARDING',CURRENT_TIMESTAMP,'FAMILIARITY_ONLY')",
                UUID.randomUUID(), profileId, skill, claimed ? "CLAIMED" : "RETRACTED");
    }
}
