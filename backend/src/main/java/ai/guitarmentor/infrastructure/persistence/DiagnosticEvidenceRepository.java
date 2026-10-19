package ai.guitarmentor.infrastructure.persistence;

import ai.guitarmentor.application.evidence.EvidenceDtos.EvidenceView;
import ai.guitarmentor.application.evidence.EvidenceDtos.MediaView;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL persistence for pre-task evidence, separate from task submission_attempts. */
@Repository
public class DiagnosticEvidenceRepository {
    private final JdbcTemplate db;
    public DiagnosticEvidenceRepository(JdbcTemplate db) { this.db = db; }

    public record Evidence(UUID id, UUID sessionId, UUID requestId, String title,
            String composer, String comment, Instant submittedAt) {}
    public record Media(UUID id, UUID evidenceId, String kind, String key,
            String format, long bytes, String status, BigDecimal duration,
            String errorCode, String mediaState) {}
    public record AudioJob(UUID id, String key, long bytes) {}
    public record Expired(UUID id, String kind, String key) {}

    private static UUID uuid(ResultSet rs, String key) throws SQLException {
        return rs.getObject(key, UUID.class);
    }
    private static Evidence mapEvidence(ResultSet rs, int i) throws SQLException {
        return new Evidence(uuid(rs, "id"), uuid(rs, "diagnostic_session_id"),
                uuid(rs, "request_id"), rs.getString("title"), rs.getString("composer"),
                rs.getString("student_comment"), rs.getTimestamp("submitted_at").toInstant());
    }
    private static Media mapMedia(ResultSet rs, int i) throws SQLException {
        return new Media(uuid(rs, "id"), uuid(rs, "evidence_id"), rs.getString("kind"),
                rs.getString("storage_key"), rs.getString("media_format"),
                rs.getLong("size_bytes"), rs.getString("status"),
                rs.getBigDecimal("audio_duration_seconds"), rs.getString("processing_error_code"),
                rs.getString("media_state"));
    }

    public List<Evidence> list(UUID sessionId) {
        return db.query("SELECT * FROM diagnostic_evidence WHERE diagnostic_session_id=? " +
                "ORDER BY submitted_at, id", DiagnosticEvidenceRepository::mapEvidence, sessionId);
    }
    public Optional<Evidence> byRequest(UUID sessionId, UUID requestId) {
        return db.query("SELECT * FROM diagnostic_evidence WHERE diagnostic_session_id=? " +
                "AND request_id=?", DiagnosticEvidenceRepository::mapEvidence, sessionId, requestId)
                .stream().findFirst();
    }
    public long count(UUID sessionId) {
        Long count = db.queryForObject("SELECT count(*) FROM diagnostic_evidence " +
                "WHERE diagnostic_session_id=?", Long.class, sessionId);
        return count == null ? 0 : count;
    }
    public void addEvidence(UUID id, UUID sessionId, UUID requestId,
            String title, String composer, String comment) {
        db.update("INSERT INTO diagnostic_evidence " +
                "(id,diagnostic_session_id,request_id,title,composer,student_comment)" +
                " VALUES (?,?,?,?,?,?)", id, sessionId, requestId, title, composer, comment);
    }
    public void addMedia(UUID evidenceId, String kind, String key, String format,
            long bytes, String status) {
        db.update("INSERT INTO diagnostic_evidence_media " +
                "(id,evidence_id,kind,storage_key,media_format,size_bytes,status) " +
                "VALUES (?,?,?,?,?,?,?)", UUID.randomUUID(), evidenceId, kind, key, format,
                bytes, status);
    }
    public List<Media> media(UUID evidenceId) {
        return db.query("SELECT * FROM diagnostic_evidence_media WHERE evidence_id=?",
                DiagnosticEvidenceRepository::mapMedia, evidenceId);
    }
    public EvidenceView view(Evidence evidence) {
        MediaView audio = null, score = null;
        for (Media item : media(evidence.id())) {
            var data = new MediaView(item.format(), item.bytes(), item.status(),
                    item.duration(), item.errorCode(), item.mediaState());
            if (item.kind().equals("AUDIO")) audio = data;
            else score = data;
        }
        return new EvidenceView(evidence.id(), evidence.title(), evidence.composer(),
                evidence.comment(), evidence.submittedAt(), audio, score,
                "STUDENT_UPLOADED", "NOT_ASSESSED");
    }

    /** Native UPDATE RETURNING is atomic even with concurrent workers. */
    @Transactional
    public Optional<AudioJob> claimAudio() {
        return db.query("""
                UPDATE diagnostic_evidence_media SET status='PROCESSING',
                    processing_attempts=processing_attempts+1,
                    processing_started_at=CURRENT_TIMESTAMP
                WHERE id=(SELECT id FROM diagnostic_evidence_media
                    WHERE kind='AUDIO' AND status='UPLOADED' AND media_state='AVAILABLE'
                    ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED)
                RETURNING id,storage_key,size_bytes
                """, (rs, i) -> new AudioJob(uuid(rs,"id"), rs.getString("storage_key"),
                        rs.getLong("size_bytes"))).stream().findFirst();
    }
    @Transactional
    public void finishAudio(UUID id, double duration, String error) {
        String status = error == null ? "PROCESSED" : "FAILED";
        db.update("""
                UPDATE diagnostic_evidence_media SET status=?, finalized_at=CURRENT_TIMESTAMP,
                    audio_duration_seconds=?,processing_error_code=?
                WHERE id=? AND kind='AUDIO' AND status='PROCESSING' AND media_state='AVAILABLE'
                """, status, error == null ? BigDecimal.valueOf(duration).setScale(3,
                        java.math.RoundingMode.HALF_UP) : null, error, id);
        db.update("""
                UPDATE diagnostic_evidence_media SET finalized_at=CURRENT_TIMESTAMP
                WHERE kind='SCORE' AND finalized_at IS NULL AND
                    evidence_id=(SELECT evidence_id FROM diagnostic_evidence_media WHERE id=?)
                """, id);
    }
    @Transactional
    public void recover(Instant cutoff) {
        db.update("""
                UPDATE diagnostic_evidence_media SET status='UPLOADED',
                    processing_started_at=NULL
                WHERE kind='AUDIO' AND status='PROCESSING' AND
                    processing_started_at<? AND processing_attempts<3 AND media_state='AVAILABLE'
                """, cutoff);
        var exhausted = db.query("""
                UPDATE diagnostic_evidence_media SET status='FAILED',
                    processing_error_code='PROCESSING_INTERRUPTED', finalized_at=CURRENT_TIMESTAMP
                WHERE kind='AUDIO' AND status='PROCESSING' AND processing_started_at<?
                  AND processing_attempts>=3 AND media_state='AVAILABLE'
                RETURNING evidence_id
                """, (rs,i) -> uuid(rs,"evidence_id"), cutoff);
        for (UUID e : exhausted) db.update("""
                UPDATE diagnostic_evidence_media SET finalized_at=CURRENT_TIMESTAMP
                WHERE kind='SCORE' AND evidence_id=? AND finalized_at IS NULL
                """, e);
    }

    @Transactional
    public List<Expired> claimExpired(Instant finishedCutoff, Instant staleCutoff) {
        var candidates = db.query("""
                SELECT m.id,m.kind,m.storage_key FROM diagnostic_evidence_media m
                JOIN diagnostic_evidence e ON e.id=m.evidence_id
                WHERE m.media_state='DELETE_PENDING'
                   OR (m.media_state='AVAILABLE' AND
                       ((m.finalized_at IS NOT NULL AND m.finalized_at<=?)
                        OR (m.finalized_at IS NULL AND e.submitted_at<=?)))
                ORDER BY e.submitted_at,m.id LIMIT 50 FOR UPDATE OF m SKIP LOCKED
                """, (rs,i) -> new Expired(uuid(rs,"id"),rs.getString("kind"),
                        rs.getString("storage_key")), finishedCutoff, staleCutoff);
        for (Expired item : candidates) {
            db.update("""
                    UPDATE diagnostic_evidence_media SET media_state='DELETE_PENDING'
                    WHERE id=? AND media_state='AVAILABLE'
                    """, item.id());
        }
        return candidates;
    }
    public void markDeleted(UUID id) {
        db.update("""
                UPDATE diagnostic_evidence_media
                SET media_state='DELETED',deleted_at=CURRENT_TIMESTAMP,delete_error_code=NULL
                WHERE id=? AND media_state='DELETE_PENDING'
                """, id);
    }
    public void markFailed(UUID id) {
        db.update("""
                UPDATE diagnostic_evidence_media SET
                    delete_attempts=delete_attempts+1,delete_error_code='DELETE_IO_ERROR'
                WHERE id=? AND media_state='DELETE_PENDING'
                """, id);
    }
    public boolean existsByKey(String key) {
        Integer count = db.queryForObject(
                "SELECT count(*) FROM diagnostic_evidence_media WHERE storage_key=?",
                Integer.class, key);
        return count != null && count > 0;
    }
}
