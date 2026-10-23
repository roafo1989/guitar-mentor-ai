package ai.guitarmentor.interfaces.rest.exercise;

import ai.guitarmentor.application.exercise.ExerciseCatalogService;
import ai.guitarmentor.application.exercise.ExerciseMediaService;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/exercises")
public class ExerciseController {
    private final ExerciseCatalogService service;
    private final ExerciseMediaService media;
    private final ai.guitarmentor.application.exercise.ExerciseVersionResourceService versionResources;

    public ExerciseController(ExerciseCatalogService service, ExerciseMediaService media,
            ai.guitarmentor.application.exercise.ExerciseVersionResourceService versionResources) {
        this.service = service;
        this.media = media;
        this.versionResources = versionResources;
    }

    @GetMapping
    public List<ExerciseView> list(
            @RequestParam @NotBlank String instrumentCode,
            @RequestParam @NotBlank String techniqueCode) {
        return service.available(instrumentCode, techniqueCode);
    }

    @GetMapping("/{code}")
    public ExerciseView one(@PathVariable String code) {
        return service.find(code);
    }

    @GetMapping(value = "/{code}/preview/audio", produces = "audio/mpeg")
    public ResponseEntity<Resource> audio(@PathVariable String code) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/mpeg"))
                .cacheControl(CacheControl.noCache())
                .body(media.get(code, true));
    }

    @GetMapping(value = "/{code}/preview/pdf", produces = "application/pdf")
    public ResponseEntity<Resource> pdf(@PathVariable String code) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .cacheControl(CacheControl.noCache())
                .body(media.get(code, false));
    }

    @GetMapping(value = "/{code}/score", produces = "application/vnd.recordare.musicxml+xml")
    public ResponseEntity<byte[]> score(@PathVariable String code) throws IOException {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.recordare.musicxml+xml"))
                .body(service.resource(code, true));
    }

    @GetMapping(value = "/{code}/events", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> events(@PathVariable String code) throws IOException {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(service.resource(code, false));
    }
    @GetMapping("/{code}/versions/{version}")
    public ResponseEntity<ExerciseView> exact(@PathVariable String code,
            @PathVariable String version) {
        ai.guitarmentor.application.exercise.ExerciseVersionResourceService.validate(code, version);
        return ResponseEntity.ok().cacheControl(CacheControl.noCache())
                .body(service.findVersion(code, version));
    }

    @org.springframework.web.bind.annotation.RequestMapping(
            value = "/{code}/versions/{version}/score",
            method = {org.springframework.web.bind.annotation.RequestMethod.GET,
                      org.springframework.web.bind.annotation.RequestMethod.HEAD})
    public ResponseEntity<byte[]> exactScore(@PathVariable String code, @PathVariable String version,
            jakarta.servlet.http.HttpServletRequest request) {
        return exactResource(code, version,
                ai.guitarmentor.application.exercise.ExerciseVersionResourceService.Kind.SCORE,
                MediaType.parseMediaType("application/vnd.recordare.musicxml+xml"), request);
    }

    @org.springframework.web.bind.annotation.RequestMapping(
            value = "/{code}/versions/{version}/events",
            method = {org.springframework.web.bind.annotation.RequestMethod.GET,
                      org.springframework.web.bind.annotation.RequestMethod.HEAD})
    public ResponseEntity<byte[]> exactEvents(@PathVariable String code, @PathVariable String version,
            jakarta.servlet.http.HttpServletRequest request) {
        return exactResource(code, version,
                ai.guitarmentor.application.exercise.ExerciseVersionResourceService.Kind.EVENTS,
                MediaType.APPLICATION_JSON, request);
    }

    @org.springframework.web.bind.annotation.RequestMapping(
            value = "/{code}/versions/{version}/preview/audio",
            method = {org.springframework.web.bind.annotation.RequestMethod.GET,
                      org.springframework.web.bind.annotation.RequestMethod.HEAD})
    public ResponseEntity<byte[]> exactAudio(@PathVariable String code, @PathVariable String version,
            jakarta.servlet.http.HttpServletRequest request) {
        return exactResource(code, version,
                ai.guitarmentor.application.exercise.ExerciseVersionResourceService.Kind.AUDIO,
                MediaType.parseMediaType("audio/mpeg"), request);
    }

    @org.springframework.web.bind.annotation.RequestMapping(
            value = "/{code}/versions/{version}/preview/pdf",
            method = {org.springframework.web.bind.annotation.RequestMethod.GET,
                      org.springframework.web.bind.annotation.RequestMethod.HEAD})
    public ResponseEntity<byte[]> exactPdf(@PathVariable String code, @PathVariable String version,
            jakarta.servlet.http.HttpServletRequest request) {
        return exactResource(code, version,
                ai.guitarmentor.application.exercise.ExerciseVersionResourceService.Kind.PDF,
                MediaType.APPLICATION_PDF, request);
    }

    private ResponseEntity<byte[]> exactResource(String code, String version,
            ai.guitarmentor.application.exercise.ExerciseVersionResourceService.Kind kind,
            MediaType mime, jakarta.servlet.http.HttpServletRequest request) {
        boolean head = "HEAD".equalsIgnoreCase(request.getMethod());
        if (head) {
            versionResources.locate(code, version, kind);
        }
        byte[] body = head ? null : versionResources.read(code, version, kind);
        return ResponseEntity.ok().cacheControl(CacheControl.noCache())
                .contentType(mime).body(body);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(
            ai.guitarmentor.application.exercise.ExerciseReferenceException.class)
    public ResponseEntity<java.util.Map<String, String>> exactError(
            ai.guitarmentor.application.exercise.ExerciseReferenceException ex) {
        return ResponseEntity.status(ex.status()).cacheControl(CacheControl.noCache())
                .body(java.util.Map.of("code", ex.code(), "error", ex.getMessage()));
    }

}
