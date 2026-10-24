package ai.guitarmentor.infrastructure.persistence;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Conservative crash recovery for upload orphans. Never delete a referenced asset:
 * only random-key objects from our isolated root, older than the staging grace.
 * A transient DB error aborts the sweep without deleting unknown ownership.
 */
@Configuration
@EnableScheduling
public class LibraryOrphanRecovery {
    private final LibraryObjectStore store;
    private final JdbcClient jdbc;
    private final boolean enabled;
    public LibraryOrphanRecovery(LibraryObjectStore store, JdbcClient jdbc,
            @Value("${guitar-mentor.library.enabled:false}") boolean enabled) {
        this.store=store;this.jdbc=jdbc;this.enabled=enabled;
    }
    @Scheduled(fixedDelayString="${guitar-mentor.library.reconcile-delay-ms:600000}")
    public void reconcile() {
        if(!enabled) return;
        try {
            for(Path candidate:store.expiredCandidates(Duration.ofMinutes(60),100)) {
                String name=candidate.getFileName().toString();
                if(name.startsWith(".staging-")) {
                    // Never promote staging; after grace any still-unfinished staging is orphaned.
                    store.deleteCandidate(candidate);
                    continue;
                }
                Boolean referenced=jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM library_assets WHERE object_key=:key)
                    """).param("key",name).query(Boolean.class).single();
                if(Boolean.FALSE.equals(referenced)) store.deleteCandidate(candidate);
            }
        } catch(Exception e) {
            // Fail closed on database or storage unavailability; retry next scheduled cycle.
            org.slf4j.LoggerFactory.getLogger(getClass()).warn(
                "Library orphan sweep deferred (storage or DB unavailable): {}",e.getClass().getSimpleName());
        }
    }
}
