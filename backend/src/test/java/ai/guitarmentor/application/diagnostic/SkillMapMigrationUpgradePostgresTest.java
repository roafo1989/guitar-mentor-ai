package ai.guitarmentor.application.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Simulates an existing nonempty database at V11 (including accepted #21),
 * migrates it to V12, validates Flyway and verifies historical references.
 * Test schema is isolated and dropped afterwards.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "GUITAR_MENTOR_TEST_POSTGRES", matches = "true")
class SkillMapMigrationUpgradePostgresTest {
    @Value("${spring.datasource.url}") String url;
    @Value("${spring.datasource.username}") String username;
    @Value("${spring.datasource.password}") String password;

    @Test
    void migrationFromPopulatedV11ToV12PreservesAssignedTaskAndBackfillsOnlyClaim()
            throws Exception {
        String schema = "r16_" + UUID.randomUUID().toString().replace("-", "");
        String testUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        var original = Flyway.configure().dataSource(testUrl, username, password)
                .defaultSchema(schema).schemas(schema).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("11")).load();
        UUID learnerId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        try {
            original.migrate();
            try (var conn = DriverManager.getConnection(testUrl, username, password);
                 var sql = conn.createStatement()) {
                sql.execute("INSERT INTO learners (id,display_name,created_at) VALUES ('"
                        + learnerId + "','historical learner',NOW())");
                sql.execute("INSERT INTO instrument_profiles"
                        + "(id,learner_id,instrument_code,technique_code) VALUES ('"
                        + profileId + "','" + learnerId + "','ACOUSTIC_GUITAR','FINGERSTYLE')");
                sql.execute("INSERT INTO learning_backgrounds"
                        + "(id,instrument_profile_id,experience_level,years_playing,"
                        + "practice_minutes,sessions_per_week,notes) VALUES ('"
                        + UUID.randomUUID() + "','" + profileId + "','INTERMEDIATE',3,40,3,'')");
                sql.execute("INSERT INTO background_skills(background_id,skill_code)"
                        + " SELECT id,'FINGERSTYLE_MELODY' FROM learning_backgrounds"
                        + " WHERE instrument_profile_id='" + profileId + "'");
                sql.execute("INSERT INTO diagnostic_sessions"
                        + "(id,instrument_profile_id,status,created_at,updated_at,version)"
                        + " VALUES ('" + sessionId + "','" + profileId
                        + "','TASK_ASSIGNED',NOW(),NOW(),0)");
                sql.execute("INSERT INTO diagnostic_tasks"
                        + "(id,diagnostic_session_id,exercise_id,assigned_at) VALUES ('"
                        + taskId + "','" + sessionId
                        + "','58430000-0000-4000-8000-000000000801',NOW())");
            }
            var updated = Flyway.configure().dataSource(testUrl, username, password)
                    .defaultSchema(schema).schemas(schema)
                    .locations("classpath:db/migration").load();
            updated.migrate();
            updated.validate();
            try (var conn = DriverManager.getConnection(testUrl, username, password);
                 var stmt = conn.createStatement()) {
                try (var rs = stmt.executeQuery("SELECT count(*) FROM diagnostic_tasks"
                        + " WHERE id='" + taskId + "' AND exercise_id="
                        + "'58430000-0000-4000-8000-000000000801'")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1));
                }
                try (var rs = stmt.executeQuery("SELECT count(*)"
                        + " FROM learner_skill_signal_history WHERE instrument_profile_id='"
                        + profileId + "' AND skill_code='MELODY'"
                        + " AND source_type='SELF_REPORTED' AND level IS NULL")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1));
                }
            }
        } finally {
            try (var conn = DriverManager.getConnection(url, username, password);
                 var stmt = conn.createStatement()) {
                stmt.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }
}
