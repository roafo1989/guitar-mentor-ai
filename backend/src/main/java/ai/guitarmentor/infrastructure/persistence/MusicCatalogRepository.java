package ai.guitarmentor.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Private indexed metadata. This repository NEVER reads file bytes or local file paths. */
@Repository
public class MusicCatalogRepository {
    private final JdbcClient jdbc;

    public MusicCatalogRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record Work(String id, String title, String artist, String identityStatus) {}
    public record Arrangement(String id, String name, String identityStatus,
            String parserStatus, String tempo, String timeSignatures, String instruments,
            String trackCount, String measureCount, String noteCount) {}
    public record SourceFile(String id, String name, String format, long sizeBytes,
            String rightsStatus, String parserStatus, String originalAvailability) {}

    private static final org.springframework.jdbc.core.RowMapper<Work> WORK = (rs,n) ->
        new Work(rs.getString("source_work_id"),rs.getString("title"),rs.getString("artist"),
            rs.getString("identity_status"));

    private static final org.springframework.jdbc.core.RowMapper<Arrangement> ARRANGEMENT = (rs,n) ->
        new Arrangement(rs.getString("source_arrangement_id"),rs.getString("name"),
            rs.getString("identity_status"),rs.getString("parser_status"),
            rs.getString("tempo_raw"),rs.getString("time_signatures_raw"),
            rs.getString("instruments_raw"),rs.getString("track_count_raw"),
            rs.getString("measure_count_raw"),rs.getString("note_count_raw"));

    /** Query, format, parser and sorting are bound parameters or fixed server-controlled SQL. */
    public List<Work> search(UUID owner, String query, String format, String parserStatus,
            String sort, int limit, long offset) {
        String order = switch(sort) {
            case "artist" -> "w.normalized_artist, w.normalized_title, w.source_work_id";
            case "title_desc" -> "w.normalized_title DESC, w.source_work_id DESC";
            default -> "w.normalized_title, w.normalized_artist, w.source_work_id";
        };
        String sql = """
            SELECT w.source_work_id,w.title,w.artist,w.identity_status
            FROM music_catalog_works w
            WHERE w.owner_account_id=:owner
              AND (:q='' OR position(lower(:q) in lower(w.title))>0
                   OR position(lower(:q) in lower(w.artist))>0
                   OR position(lower(:q) in lower(w.normalized_title))>0
                   OR position(lower(:q) in lower(w.normalized_artist))>0)
              AND EXISTS (
                  SELECT 1 FROM music_catalog_arrangements a
                  WHERE a.owner_account_id=w.owner_account_id
                    AND a.source_work_id=w.source_work_id
                    AND (:parser='' OR a.parser_status=:parser)
                    AND (:format='' OR EXISTS (
                        SELECT 1 FROM music_catalog_source_files f
                        WHERE f.owner_account_id=a.owner_account_id
                          AND f.source_arrangement_id=a.source_arrangement_id
                          AND lower(f.extension)=:format))
              )
            ORDER BY """ + order + " LIMIT :limit OFFSET :offset";
        return jdbc.sql(sql).param("owner",owner).param("q",query)
            .param("parser",parserStatus).param("format",format)
            .param("limit",limit).param("offset",offset).query(WORK).list();
    }

    public Optional<Work> work(UUID owner,String id) {
        return jdbc.sql("""
            SELECT source_work_id,title,artist,identity_status FROM music_catalog_works
            WHERE owner_account_id=:owner AND source_work_id=:id
            """).param("owner",owner).param("id",id).query(WORK).optional();
    }

    public List<Arrangement> arrangements(UUID owner,String workId) {
        return jdbc.sql("""
            SELECT source_arrangement_id,name,identity_status,parser_status,tempo_raw,
                   time_signatures_raw,instruments_raw,track_count_raw,measure_count_raw,note_count_raw
            FROM music_catalog_arrangements
            WHERE owner_account_id=:owner AND source_work_id=:id
            ORDER BY name,source_arrangement_id
            """).param("owner",owner).param("id",workId).query(ARRANGEMENT).list();
    }

    public Optional<Arrangement> arrangement(UUID owner,String id) {
        return jdbc.sql("""
            SELECT source_arrangement_id,name,identity_status,parser_status,tempo_raw,
                   time_signatures_raw,instruments_raw,track_count_raw,measure_count_raw,note_count_raw
            FROM music_catalog_arrangements
            WHERE owner_account_id=:owner AND source_arrangement_id=:id
            """).param("owner",owner).param("id",id).query(ARRANGEMENT).optional();
    }

    public List<SourceFile> files(UUID owner,String arrangementId) {
        return jdbc.sql("""
            SELECT f.source_file_id,f.original_filename,f.extension,f.size_bytes,
                   f.rights_status,a.parser_status
            FROM music_catalog_source_files f
            JOIN music_catalog_arrangements a ON a.owner_account_id=f.owner_account_id
                 AND a.source_arrangement_id=f.source_arrangement_id
            WHERE f.owner_account_id=:owner AND f.source_arrangement_id=:id
              AND f.visibility='PRIVATE'
            ORDER BY f.source_file_id
            """).param("owner",owner).param("id",arrangementId)
            .query((rs,n)->new SourceFile(rs.getString(1),rs.getString(2),
                rs.getString(3),rs.getLong(4),rs.getString(5),rs.getString(6),
                "UNAVAILABLE")).list();
    }
}
