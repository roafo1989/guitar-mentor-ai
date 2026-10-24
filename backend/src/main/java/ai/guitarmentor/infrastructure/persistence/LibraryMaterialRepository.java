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
    public record Material(UUID id, String title, String materialType, String description,
            String status, long revision, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
    private static final org.springframework.jdbc.core.RowMapper<Material> MAPPER =
            (rs, n) -> new Material(rs.getObject("id",UUID.class),rs.getString("title"),
                    rs.getString("material_type"),rs.getString("description"),rs.getString("status"),
                    rs.getLong("revision"),rs.getObject("created_at",OffsetDateTime.class),
                    rs.getObject("updated_at",OffsetDateTime.class));

    public Optional<Material> find(UUID owner, UUID id) {
        return jdbc.sql(""" 
            SELECT id,title,material_type,description,status,revision,created_at,updated_at
            FROM library_materials WHERE owner_account_id=:owner AND id=:id AND status='READY'
            """).param("owner",owner).param("id",id).query(MAPPER).optional();
    }

    public List<Material> page(UUID owner, OffsetDateTime beforeTime, UUID beforeId, int limit) {
        if (beforeTime == null) {
            return jdbc.sql("""
                SELECT id,title,material_type,description,status,revision,created_at,updated_at
                FROM library_materials WHERE owner_account_id=:owner AND status='READY'
                ORDER BY created_at DESC,id DESC LIMIT :limit
                """).param("owner",owner).param("limit",limit).query(MAPPER).list();
        }
        return jdbc.sql("""
            SELECT id,title,material_type,description,status,revision,created_at,updated_at
            FROM library_materials WHERE owner_account_id=:owner AND status='READY'
              AND (created_at,id)<(:beforeTime,:beforeId)
            ORDER BY created_at DESC,id DESC LIMIT :limit
            """).param("owner",owner).param("beforeTime",beforeTime)
                .param("beforeId",beforeId).param("limit",limit).query(MAPPER).list();
    }

    public int update(UUID owner, UUID id, long revision, String title, String type, String description) {
        return jdbc.sql("""
            UPDATE library_materials SET title=:title, material_type=:type,
                description=:description, revision=revision+1,updated_at=now()
            WHERE owner_account_id=:owner AND id=:id AND revision=:revision AND status='READY'
            """).param("owner",owner).param("id",id).param("revision",revision)
                .param("title",title).param("type",type).param("description",description).update();
    }
}
