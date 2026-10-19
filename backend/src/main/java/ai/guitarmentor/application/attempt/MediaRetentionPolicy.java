package ai.guitarmentor.application.attempt;

import ai.guitarmentor.infrastructure.persistence.SystemSettingRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class MediaRetentionPolicy {
    private final SystemSettingRepository settings;
    public MediaRetentionPolicy(SystemSettingRepository settings) { this.settings=settings; }
    public record Policy(Duration afterFinal, Duration maxUnprocessed, Duration interval) {}
    public Policy read() {
        Map<String,String> values=settings.snapshot().stream().collect(Collectors.toMap(
            SystemSettingRepository.Setting::key,SystemSettingRepository.Setting::value));
        long a=positive(values.get("media.retention_after_processing_hours"));
        long b=positive(values.get("media.max_unprocessed_age_hours"));
        long c=positive(values.get("media.cleanup_interval_minutes"));
        try {
            var p=new Policy(Duration.ofHours(a),Duration.ofHours(b),Duration.ofMinutes(c));
            // Reject values which cannot safely calculate cutoff/due instants in common use.
            Instant.now().minus(p.afterFinal());
            Instant.now().minus(p.maxUnprocessed());
            Instant.now().minus(p.interval());
            return p;
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("MEDIA_CLEANUP_CONFIG_INVALID"); }
    }
    private static long positive(String value) {
        try {
            long n=Long.parseLong(value);
            if(n<=0) throw new IllegalArgumentException();
            return n;
        } catch(RuntimeException invalid) { throw new IllegalArgumentException("MEDIA_CLEANUP_CONFIG_INVALID"); }
    }
}
