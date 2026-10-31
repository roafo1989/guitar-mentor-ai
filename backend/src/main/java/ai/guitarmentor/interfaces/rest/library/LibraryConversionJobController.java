package ai.guitarmentor.interfaces.rest.library;

import ai.guitarmentor.application.library.LibraryConversionJobService;
import ai.guitarmentor.application.library.LibraryConversionJobService.ConversionJobException;
import ai.guitarmentor.application.library.LibraryConversionJobService.Created;
import ai.guitarmentor.application.library.LibraryConversionJobService.JobView;
import ai.guitarmentor.infrastructure.security.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** FT-013-02/#170-A: private owner-scoped conversion job API. */
@RestController
@RequestMapping("/api/v1/library/materials/{materialId}/conversion-jobs")
public final class LibraryConversionJobController {
    private final LibraryConversionJobService service;
    private final boolean libraryEnabled;
    private final boolean conversionEnabled;

    public LibraryConversionJobController(LibraryConversionJobService service,
            @Value("${guitar-mentor.library.enabled:false}") boolean libraryEnabled,
            @Value("${guitar-mentor.library.conversion-enabled:false}") boolean conversionEnabled) {
        this.service = service;
        this.libraryEnabled = libraryEnabled;
        this.conversionEnabled = conversionEnabled;
    }

    public record CreateRequest(UUID sourceAssetId) {}
    public record TrackSelectionRequest(Integer trackIndex) {}

    private UUID owner(HttpServletRequest request) {
        CurrentActor actor = (CurrentActor) request.getAttribute("guitarMentorActor");
        if (actor == null) throw new ConversionJobException(401, "UNAUTHENTICATED");
        if (!libraryEnabled) throw new ConversionJobException(503, "LIBRARY_DISABLED");
        if (!conversionEnabled) throw new ConversionJobException(503, "LIBRARY_CONVERSION_DISABLED");
        return actor.accountId();
    }

    @PostMapping
    public ResponseEntity<JobView> create(HttpServletRequest request,
            @PathVariable UUID materialId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody CreateRequest body) {
        UUID account = owner(request);
        UUID key = parseKey(rawKey);
        long revision = parseRevision(ifMatch);
        if (body == null || body.sourceAssetId() == null)
            throw new ConversionJobException(400, "CONVERSION_INVALID_REQUEST");
        Created created = service.create(account, materialId, body.sourceAssetId(), key, revision);
        return ResponseEntity.accepted()
            .header(HttpHeaders.LOCATION, "/api/v1/library/materials/" + materialId
                + "/conversion-jobs/" + created.job().id())
            .header(HttpHeaders.ETAG, etag(created.job().revision()))
            .header("Idempotency-Replayed", Boolean.toString(created.replayed()))
            .cacheControl(CacheControl.noStore())
            .body(created.job());
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<JobView> get(HttpServletRequest request,
            @PathVariable UUID materialId, @PathVariable UUID jobId) {
        JobView job = service.get(owner(request), materialId, jobId);
        return ResponseEntity.ok().eTag(etag(job.revision()))
            .cacheControl(CacheControl.noStore()).body(job);
    }

    @PostMapping("/{jobId}/track-selection")
    public ResponseEntity<JobView> selectTrack(HttpServletRequest request,
            @PathVariable UUID materialId, @PathVariable UUID jobId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody TrackSelectionRequest body) {
        if (body == null || body.trackIndex() == null || body.trackIndex() < 0)
            throw new ConversionJobException(400, "CONVERSION_INVALID_TRACK");
        JobView job = service.selectTrack(owner(request), materialId, jobId,
            parseRevision(ifMatch), body.trackIndex());
        return response(job);
    }

    @PostMapping("/{jobId}/cancel")
    public ResponseEntity<JobView> cancel(HttpServletRequest request,
            @PathVariable UUID materialId, @PathVariable UUID jobId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        JobView job = service.cancel(owner(request), materialId, jobId, parseRevision(ifMatch));
        return response(job);
    }

    @PostMapping("/{jobId}/retry")
    public ResponseEntity<JobView> retry(HttpServletRequest request,
            @PathVariable UUID materialId, @PathVariable UUID jobId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        JobView job = service.retry(owner(request), materialId, jobId, parseRevision(ifMatch));
        return response(job);
    }

    private static ResponseEntity<JobView> response(JobView job) {
        return ResponseEntity.ok().eTag(etag(job.revision()))
            .cacheControl(CacheControl.noStore()).body(job);
    }

    private static String etag(long revision) { return "\"" + revision + "\""; }

    private static UUID parseKey(String raw) {
        if (raw == null) throw new ConversionJobException(400, "CONVERSION_IDEMPOTENCY_KEY_REQUIRED");
        try { return UUID.fromString(raw); }
        catch (IllegalArgumentException invalid) {
            throw new ConversionJobException(400, "CONVERSION_INVALID_IDEMPOTENCY_KEY");
        }
    }

    private static long parseRevision(String raw) {
        if (raw == null) throw new ConversionJobException(428, "LIBRARY_PRECONDITION_REQUIRED");
        if (!raw.matches("\"[1-9][0-9]*\""))
            throw new ConversionJobException(400, "LIBRARY_INVALID_METADATA");
        try { return Long.parseLong(raw.substring(1, raw.length() - 1)); }
        catch (NumberFormatException invalid) {
            throw new ConversionJobException(400, "LIBRARY_INVALID_METADATA");
        }
    }

    @ExceptionHandler(ConversionJobException.class)
    public ResponseEntity<Map<String,String>> problem(ConversionJobException error) {
        return ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore())
            .body(Map.of("code", error.code()));
    }
}
