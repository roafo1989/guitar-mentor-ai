package ai.guitarmentor.interfaces.rest.library;

import ai.guitarmentor.infrastructure.persistence.MusicCatalogRepository;
import ai.guitarmentor.infrastructure.persistence.MusicCatalogRepository.Work;
import ai.guitarmentor.infrastructure.persistence.MusicCatalogRepository.Arrangement;
import ai.guitarmentor.infrastructure.persistence.MusicCatalogRepository.SourceFile;
import ai.guitarmentor.infrastructure.security.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Private metadata-only archive, isolated from /api/v1/library/materials and community publication.
 * No file path, storage URL, download endpoint or publishing capability.
 */
@RestController
@RequestMapping("/api/v1/music-catalog")
public class MusicCatalogController {
    private final MusicCatalogRepository repository;
    public MusicCatalogController(MusicCatalogRepository repository) { this.repository=repository; }

    private UUID owner(HttpServletRequest request) {
        if (!(request.getAttribute("guitarMentorActor") instanceof CurrentActor actor))
            throw new CatalogError(401,"UNAUTHENTICATED");
        return actor.accountId();
    }

    public record Page(List<Work> items,int limit,long offset,Long nextOffset) {}
    public record ArrangementView(Arrangement arrangement,List<SourceFile> files) {}
    public record Detail(Work work,List<ArrangementView> arrangements) {}

    @GetMapping("/works")
    public ResponseEntity<Page> search(HttpServletRequest request,
            @RequestParam(defaultValue="") String q,
            @RequestParam(defaultValue="") String format,
            @RequestParam(defaultValue="") String parserStatus,
            @RequestParam(defaultValue="title") String sort,
            @RequestParam(defaultValue="20") int limit,
            @RequestParam(defaultValue="0") long offset) {
        UUID account=owner(request);
        if (q.length()>150 || q.chars().anyMatch(Character::isISOControl)
                || limit<1 || limit>50 || offset<0 || offset>200000
                || !Set.of("title","artist","title_desc").contains(sort)
                || !Set.of("","gp","gp3","gp4","gp5","gpx","pdf").contains(format.toLowerCase(java.util.Locale.ROOT))
                || parserStatus.length()>40 || !parserStatus.matches("[A-Za-z0-9_]*"))
            throw new CatalogError(400,"MUSIC_CATALOG_INVALID_QUERY");
        String normalizedFormat=format.toLowerCase(java.util.Locale.ROOT);
        List<Work> rows=repository.search(account,q.strip(),normalizedFormat,
                parserStatus,sort,limit+1,offset);
        boolean more=rows.size()>limit;
        List<Work> page=rows.subList(0,Math.min(limit,rows.size()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new Page(page,limit,offset,more?offset+limit:null));
    }

    @GetMapping("/works/{id}")
    public ResponseEntity<Detail> detail(HttpServletRequest request,@PathVariable String id) {
        UUID account=owner(request);
        if(id.isBlank() || id.length()>64)
            throw new CatalogError(404,"MUSIC_CATALOG_NOT_FOUND");
        Work work=repository.work(account,id)
            .orElseThrow(()->new CatalogError(404,"MUSIC_CATALOG_NOT_FOUND"));
        List<ArrangementView> versions=repository.arrangements(account,id).stream()
            .map(a->new ArrangementView(a,repository.files(account,a.id()))).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new Detail(work,versions));
    }

    @GetMapping("/arrangements/{id}")
    public ResponseEntity<ArrangementView> arrangement(HttpServletRequest request,
            @PathVariable String id) {
        UUID account=owner(request);
        if(id.isBlank() || id.length()>64)
            throw new CatalogError(404,"MUSIC_CATALOG_NOT_FOUND");
        var arrangement=repository.arrangement(account,id)
            .orElseThrow(()->new CatalogError(404,"MUSIC_CATALOG_NOT_FOUND"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ArrangementView(arrangement,repository.files(account,id)));
    }

    static class CatalogError extends RuntimeException {
        final int status;
        CatalogError(int status,String code) {super(code);this.status=status;}
    }
    @ExceptionHandler(CatalogError.class)
    ResponseEntity<Map<String,String>> error(CatalogError e) {
        return ResponseEntity.status(e.status).cacheControl(CacheControl.noStore())
            .body(Map.of("code",e.getMessage()));
    }
}
