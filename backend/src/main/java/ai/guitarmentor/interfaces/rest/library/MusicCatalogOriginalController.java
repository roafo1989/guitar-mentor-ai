package ai.guitarmentor.interfaces.rest.library;

import ai.guitarmentor.infrastructure.persistence.MusicCatalogRepository;
import ai.guitarmentor.infrastructure.security.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Explicit private association with an already validated and quota-charged original. */
@RestController
@RequestMapping("/api/v1/music-catalog/files")
public class MusicCatalogOriginalController {
    private final MusicCatalogRepository catalog;
    private final boolean enabled;
    public MusicCatalogOriginalController(MusicCatalogRepository catalog,
            @Value("${guitar-mentor.library.enabled:false}") boolean enabled) {
        this.catalog=catalog; this.enabled=enabled;
    }

    @PutMapping("/{id}/original")
    public ResponseEntity<Void> bind(HttpServletRequest request, @PathVariable String id,
            @RequestBody Map<String,Object> body) {
        if(!(request.getAttribute("guitarMentorActor") instanceof CurrentActor actor))
            throw new Failure(401,"UNAUTHENTICATED");
        if(!enabled) throw new Failure(503,"LIBRARY_DISABLED");
        if(id.isBlank() || id.length()>64 || id.equals(".") || id.equals("..")
                || id.chars().anyMatch(c->Character.isISOControl(c) || c=='/' || c=='\\'))
            throw new Failure(400,"MUSIC_CATALOG_INVALID_ORIGINAL");
        UUID material,asset;
        try {
            if(body==null || !body.keySet().equals(Set.of("materialId","assetId")))
                throw new IllegalArgumentException();
            material=UUID.fromString((String)body.get("materialId"));
            asset=UUID.fromString((String)body.get("assetId"));
            if(!material.toString().equals(body.get("materialId"))
                    || !asset.toString().equals(body.get("assetId"))) throw new IllegalArgumentException();
        } catch(RuntimeException e) {throw new Failure(400,"MUSIC_CATALOG_INVALID_ORIGINAL");}
        if(!catalog.bindOriginal(actor.accountId(),id,material,asset))
            throw new Failure(404,"MUSIC_CATALOG_ORIGINAL_NOT_FOUND");
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static class Failure extends RuntimeException {
        final int status;
        Failure(int status,String code) {super(code);this.status=status;}
    }
    @ExceptionHandler(Failure.class)
    ResponseEntity<Map<String,String>> error(Failure e) {
        return ResponseEntity.status(e.status).cacheControl(CacheControl.noStore())
            .body(Map.of("code",e.getMessage()));
    }
}
