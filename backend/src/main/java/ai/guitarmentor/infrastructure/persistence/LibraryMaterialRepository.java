package ai.guitarmentor.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LibraryMaterialRepository {
    private final JdbcClient jdbc;
    public LibraryMaterialRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record Material(UUID id, String title, String type, String author,
            String state, long revision, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
    public record Asset(UUID id, String originalName, String format, long byteSize,
            String sha256,String role,String mediaType,Long durationMillis,Integer channels,
            Integer sampleRate,Integer width,Integer height,String processingState,boolean previewAvailable) {}

    private static final org.springframework.jdbc.core.RowMapper<Material> MATERIAL =
        (rs,n) -> new Material(rs.getObject("id",UUID.class), rs.getString("title"),
            rs.getString("material_type"), rs.getString("author"),rs.getString("state"),
            rs.getLong("revision"), rs.getObject("created_at",OffsetDateTime.class),
            rs.getObject("updated_at",OffsetDateTime.class));

    public Optional<Material> find(UUID owner, UUID id) {
        return jdbc.sql("""
            SELECT id,title,material_type,author,state,revision,created_at,updated_at
            FROM library_materials
            WHERE owner_account_id=:owner AND id=:id AND state='READY'
            """).param("owner",owner).param("id",id).query(MATERIAL).optional();
    }

    /** Used only for idempotent DELETE; never exposed as a readable card. */
    public Optional<Material> findIncludingDeleting(UUID owner, UUID id) {
        return jdbc.sql("""
            SELECT id,title,material_type,author,state,revision,created_at,updated_at
            FROM library_materials WHERE owner_account_id=:owner AND id=:id
            """).param("owner",owner).param("id",id).query(MATERIAL).optional();
    }

    public List<Material> page(UUID owner, OffsetDateTime before, UUID beforeId, int limit) {
        if (before == null) {
            return jdbc.sql("""
                SELECT id,title,material_type,author,state,revision,created_at,updated_at
                FROM library_materials WHERE owner_account_id=:owner AND state='READY'
                ORDER BY created_at DESC,id DESC LIMIT :limit
                """).param("owner",owner).param("limit",limit).query(MATERIAL).list();
        }
        return jdbc.sql("""
            SELECT id,title,material_type,author,state,revision,created_at,updated_at
            FROM library_materials WHERE owner_account_id=:owner AND state='READY'
              AND (created_at,id)<(:before,:beforeId)
            ORDER BY created_at DESC,id DESC LIMIT :limit
            """).param("owner",owner).param("before",before)
                .param("beforeId",beforeId).param("limit",limit).query(MATERIAL).list();
    }

    public List<Asset> assets(UUID owner, UUID materialId) {
        return jdbc.sql("""
            SELECT a.id,a.original_name,a.format,a.byte_size,a.sha256,a.role,a.media_type,
                a.duration_millis,a.channels,a.sample_rate,a.width,a.height,
                a.processing_state,(a.preview_object_key IS NOT NULL AND a.processing_state='READY') AS preview_available
            FROM library_assets a
            JOIN library_materials m ON m.id=a.material_id
            WHERE m.owner_account_id=:owner AND m.id=:id AND m.state='READY' AND a.processing_state IN ('READY','REJECTED')
            ORDER BY a.created_at,a.id
            """).param("owner",owner).param("id",materialId)
            .query((rs,n)->new Asset(rs.getObject("id",UUID.class),
                rs.getString("original_name"),rs.getString("format"),rs.getLong("byte_size"),
                rs.getString("sha256"),rs.getString("role"),rs.getString("media_type"),
                rs.getObject("duration_millis",Long.class),rs.getObject("channels",Integer.class),
                rs.getObject("sample_rate",Integer.class),rs.getObject("width",Integer.class),
                rs.getObject("height",Integer.class),rs.getString("processing_state"),rs.getBoolean("preview_available"))).list();
    }

    public int update(UUID owner, UUID id, long revision, String title, String type, String author) {
        return jdbc.sql("""
            UPDATE library_materials SET title=:title,material_type=:type,
                author=:author,revision=revision+1,updated_at=now()
            WHERE owner_account_id=:owner AND id=:id AND revision=:revision AND state='READY'
            """).param("owner",owner).param("id",id).param("revision",revision)
                .param("title",title).param("type",type).param("author",author).update();
    }

    /** Only changes visibility; bytes are removed by #161's durable deletion worker. */
    public int markDeleting(UUID owner, UUID id, long revision) {
        return jdbc.sql("""
            UPDATE library_materials SET state='DELETING',revision=revision+1,updated_at=now()
            WHERE owner_account_id=:owner AND id=:id AND revision=:revision AND state='READY'
            """).param("owner",owner).param("id",id).param("revision",revision).update();
    }

    public void queueDeletion(UUID materialId) {
        jdbc.sql("""
            INSERT INTO library_deletion_jobs(material_id,state)
            VALUES(:id,'PENDING') ON CONFLICT (material_id) DO NOTHING
            """).param("id",materialId).update();
    }
}
