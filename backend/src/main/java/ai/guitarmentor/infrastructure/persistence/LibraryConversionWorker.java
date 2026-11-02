package ai.guitarmentor.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Durable queue consumer. Database leases, not the local executor, fence output commits. */
@Component
public final class LibraryConversionWorker {
    private final LibraryConversionJobLeaseService leases;
    private final LibraryConversionProcessRunner runner;
    private final LibraryConversionOutputService outputs;
    private final LibraryObjectStore store;
    private final JdbcClient jdbc;
    private final boolean enabled;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "library-conversion");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger dispatched = new AtomicInteger();
    private volatile boolean stopping;

    public LibraryConversionWorker(LibraryConversionJobLeaseService leases,
            LibraryConversionProcessRunner runner, LibraryConversionOutputService outputs,
            LibraryObjectStore store, JdbcClient jdbc,
            @Value("${guitar-mentor.library.enabled:false}") boolean libraryEnabled,
            @Value("${guitar-mentor.library.conversion-enabled:false}") boolean conversionEnabled,
            @Value("${guitar-mentor.library.conversion-worker-enabled:true}") boolean workerEnabled) {
        this.leases = leases; this.runner = runner; this.outputs = outputs;
        this.store = store; this.jdbc = jdbc;
        this.enabled = libraryEnabled && conversionEnabled && workerEnabled;
    }

    @Scheduled(fixedDelayString="${guitar-mentor.library.conversion-poll-ms:1000}", initialDelay=5000)
    public void poll() {
        if (!enabled || stopping) return;
        for (int i = 0; i < 2 && dispatched.get() < 2; i++) {
            dispatched.incrementAndGet();
            try {
                executor.submit(() -> {
                    try { runNext(); }
                    catch (RuntimeException error) { warn("queue", error); }
                    finally { dispatched.decrementAndGet(); }
                });
            } catch (java.util.concurrent.RejectedExecutionException error) {
                dispatched.decrementAndGet();
                return;
            }
        }
    }

    /** Also used by the isolated acceptance harness; never starts unless both feature flags are on. */
    public boolean runNext() {
        if (!enabled || stopping) return false;
        var next = leases.claimNext(UUID.randomUUID()); // a fresh fence for every delivery
        if (next.isEmpty()) return false;
        var lease = next.get();
        LibraryConversionProcessRunner.WorkerResult result = null;
        try {
            var sources = jdbc.sql("""
                SELECT a.object_key,j.selected_track_index FROM library_assets a
                JOIN library_materials m ON m.id=a.material_id
                JOIN library_conversion_jobs j ON j.source_asset_id=a.id AND j.material_id=m.id
                WHERE j.id=:job AND m.id=:material AND m.owner_account_id=:owner
                  AND m.state='READY' AND a.processing_state='READY' AND a.role='SCORE_ORIGINAL'
                  AND a.sha256=:sha AND a.format=:format AND a.byte_size=:bytes
                """).param("job", lease.jobId()).param("material", lease.materialId())
                .param("owner", lease.ownerAccountId()).param("sha", lease.sourceSha256())
                .param("format", lease.sourceFormat()).param("bytes", lease.sourceByteSize())
                .query().listOfRows();
            if (sources.size() != 1) { finish(lease, "FAILED", "SOURCE_UNAVAILABLE", false); return true; }
            var source = sources.getFirst();
            Integer selected = source.get("selected_track_index") instanceof Number n ? n.intValue() : null;
            BooleanSupplier cancelled = new BooleanSupplier() {
                private long nextHeartbeat;
                @Override public boolean getAsBoolean() {
                    if (stopping || Thread.currentThread().isInterrupted()) return true;
                    long now = System.nanoTime();
                    if (now >= nextHeartbeat) {
                        if (!leases.heartbeat(lease.jobId(), lease.leaseOwner())) return true;
                        nextHeartbeat = now + java.time.Duration.ofSeconds(5).toNanos();
                    }
                    return leases.isCancelRequested(lease.jobId(), lease.leaseOwner());
                }
            };
            if (cancelled.getAsBoolean()) { finish(lease, "CANCELLED", "CANCELLED_BY_USER", false); return true; }
            leases.updateProgress(lease.jobId(), lease.leaseOwner(), "IMPORTING_SCORE", 10);
            result = runner.execute(store.file((String) source.get("object_key")),
                lease.sourceFormat(), lease.sourceSha256(), selected, cancelled);
            if (stopping || Thread.currentThread().isInterrupted()) return true;
            if (cancelled.getAsBoolean() || result.cancelled()) {
                finish(lease, "CANCELLED", "CANCELLED_BY_USER", false);
            } else if (result.trackSelectionRequired()) {
                if (!leases.awaitTrackSelection(lease.jobId(), lease.leaseOwner(),
                        new ObjectMapper().writeValueAsString(result.tracks())))
                    finish(lease, "FAILED", "WORKER_FAILED", true);
            } else if (result.rendered()) {
                if (!leases.recordSelectedTrack(lease.jobId(), lease.leaseOwner(), result.selectedTrackIndex()))
                    throw new IllegalStateException("Conversion selection or lease changed");
                leases.updateProgress(lease.jobId(), lease.leaseOwner(), "VERIFYING_OUTPUTS", 90);
                outputs.persist(lease, result);
            } else {
                String code = result.errorCode();
                if (code == null || !code.matches("[A-Z0-9_]{1,64}")) code = "WORKER_FAILED";
                boolean unsupported = code.startsWith("UNSUPPORTED_") || "NO_GUITAR_TRACK".equals(code);
                boolean timeout = "TIMED_OUT".equals(code);
                boolean retryable = timeout || java.util.Set.of("WORKER_FAILED", "WORKER_UNAVAILABLE",
                    "CONVERSION_INTERNAL_ERROR", "PDF_RENDER_FAILED").contains(code);
                finish(lease, unsupported ? "UNSUPPORTED" : timeout ? "TIMED_OUT" : "FAILED", code, retryable);
            }
        } catch (LibraryConversionOutputService.QuotaExceeded error) {
            finish(lease, "FAILED", "OUTPUT_QUOTA_EXCEEDED", true);
        } catch (LibraryConversionOutputService.MaterialCapacityExceeded error) {
            finish(lease, "FAILED", "OUTPUT_MATERIAL_CAPACITY", true);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); // shutdown: expire/reclaim rather than invent a user cancellation
        } catch (IOException | RuntimeException error) {
            finish(lease, "FAILED", "WORKER_FAILED", true);
            warn("execution", error);
        } finally {
            if (result != null && result.workspace() != null) {
                try { runner.deleteWorkspace(result.workspace()); }
                catch (IOException error) { warn("workspace cleanup", error); }
            }
        }
        return true;
    }

    private void finish(LibraryConversionJobLeaseService.Lease lease, String state, String code, boolean retryable) {
        leases.finish(lease.jobId(), lease.leaseOwner(), state, code, retryable && lease.attemptCount() < 2);
    }

    @Scheduled(fixedDelay=60000, initialDelay=60000)
    public void cleanupAbandonedWorkspaces() {
        if (!enabled || stopping) return;
        try { runner.cleanupAbandonedWorkspaces(); }
        catch (IOException error) { warn("workspace recovery", error); }
    }

    @PreDestroy
    public void close() {
        stopping = true;
        executor.shutdownNow();
    }

    private void warn(String operation, Exception error) {
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Conversion {} deferred: {}",
            operation, error.getClass().getSimpleName());
    }
}
