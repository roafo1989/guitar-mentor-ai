package ai.guitarmentor.infrastructure.persistence;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SystemSettingRepository {
    private final JdbcTemplate jdbc;
    public SystemSettingRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** One SQL statement -> one PostgreSQL READ COMMITTED snapshot for all three settings. */
    public List<Setting> snapshot() {
        return jdbc.query("SELECT setting_key,setting_value FROM system_settings WHERE setting_key LIKE 'media.%'",
            (rs,n) -> new Setting(rs.getString(1),rs.getString(2)));
    }
    public record Setting(String key,String value) {}
}
