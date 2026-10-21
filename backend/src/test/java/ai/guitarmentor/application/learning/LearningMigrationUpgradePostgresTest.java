package ai.guitarmentor.application.learning;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A genuinely pre-V15 database: migrate V1..V14, create task + submitted attempt,
 * then apply V15/V16 and assert stable identifiers and full criterion backfill.
 * Each run uses an isolated PostgreSQL schema, never the application's public schema.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named="GUITAR_MENTOR_TEST_POSTGRES",matches="true")
class LearningMigrationUpgradePostgresTest {
    @Autowired DataSource datasource;
    private static final UUID EXERCISE=
        UUID.fromString("58430000-0000-4000-8000-000000000801");

    @Test void olderTaskAndAttemptSurviveAdditiveMigrationWithActiveStateAndSnapshot()
            throws Exception {
        String schema="r18_upgrade_"+UUID.randomUUID().toString().replace("-","");
        UUID learner=UUID.randomUUID(), profile=UUID.randomUUID(),session=UUID.randomUUID(),
            task=UUID.randomUUID(),attempt=UUID.randomUUID(),request=UUID.randomUUID();
        try(Connection admin=datasource.getConnection();
            var statement=admin.createStatement()) {
            statement.execute("CREATE SCHEMA "+schema);
        }
        try {
            var old=Flyway.configure().dataSource(datasource).schemas(schema)
                .defaultSchema(schema).target("14").load();
            old.migrate();
            try(Connection connection=datasource.getConnection()) {
                String previousSchema=connection.getSchema();
                try {
                    connection.setSchema(schema);
                var now=Timestamp.from(Instant.now());
                insert(connection,"INSERT INTO learners(id,display_name,created_at) VALUES(?,?,?)",
                    learner,"Legacy learner",now);
                insert(connection,"INSERT INTO instrument_profiles"
                    +"(id,learner_id,instrument_code,technique_code)"
                    +" VALUES(?,?,'ACOUSTIC_GUITAR','FINGERSTYLE')",profile,learner);
                insert(connection,"INSERT INTO diagnostic_sessions"
                    +"(id,instrument_profile_id,status,created_at,updated_at,version)"
                    +" VALUES(?,?,'TASK_ASSIGNED',?,?,0)",session,profile,now,now);
                insert(connection,"INSERT INTO diagnostic_tasks"
                    +"(id,diagnostic_session_id,exercise_id,assigned_at)"
                    +" VALUES(?,?,?,?)",task,session,EXERCISE,now);
                insert(connection,"INSERT INTO submission_attempts"
                    +"(id,diagnostic_task_id,request_id,attempt_number,submitted_at,"
                    +"status,student_comment,media_key,media_format,size_bytes)"
                    +" VALUES(?,?,?,?,?,'UPLOADED',?,?,?,?)",
                    attempt,task,request,1,now,"Legacy preserved",
                    UUID.randomUUID()+".wav","wav",32L);
                } finally {
                    connection.setSchema(previousSchema==null ? "public" : previousSchema);
                }
            }
            Flyway.configure().dataSource(datasource).schemas(schema)
                .defaultSchema(schema).load().migrate();
            try(Connection connection=datasource.getConnection()) {
                String previousSchema=connection.getSchema();
                try {
                    connection.setSchema(schema);
                assertEquals(1L,count(connection,"SELECT count(*) FROM diagnostic_tasks WHERE id=?",task));
                assertEquals(1L,count(connection,"SELECT count(*) FROM submission_attempts"
                    +" WHERE id=? AND request_id=?",attempt,request));
                assertEquals(1L,count(connection,"SELECT count(*) FROM task_learning_state"
                    +" WHERE task_id=? AND state='ACTIVE' AND version=0",task));
                assertEquals(3L,count(connection,"SELECT count(*) FROM task_criterion_snapshots"
                    +" WHERE task_id=?",task));
                assertEquals(0L,count(connection,"SELECT count(*) FROM attempt_assessments"
                    +" WHERE task_id=?",task));
                assertEquals(0L,count(connection,"SELECT count(*) FROM learner_skill_signal_history"
                    +" WHERE instrument_profile_id=? AND source_type <> 'SELF_REPORTED'",profile));
                assertEquals(16L,count(connection,
                    "SELECT max(installed_rank) FROM flyway_schema_history WHERE success=true"));
                } finally {
                    connection.setSchema(previousSchema==null ? "public" : previousSchema);
                }
            }
        } finally {
            try(Connection admin=datasource.getConnection();
                var statement=admin.createStatement()) {
                statement.execute("DROP SCHEMA "+schema+" CASCADE");
            }
        }
    }

    private static void insert(Connection connection,String sql,Object...values)
            throws Exception {
        try(PreparedStatement statement=connection.prepareStatement(sql)) {
            for(int index=0;index<values.length;index++) statement.setObject(index+1,values[index]);
            assertEquals(1,statement.executeUpdate());
        }
    }
    private static long count(Connection connection,String sql,Object...values)
            throws Exception {
        try(PreparedStatement statement=connection.prepareStatement(sql)) {
            for(int index=0;index<values.length;index++) statement.setObject(index+1,values[index]);
            try(ResultSet rows=statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }
}
