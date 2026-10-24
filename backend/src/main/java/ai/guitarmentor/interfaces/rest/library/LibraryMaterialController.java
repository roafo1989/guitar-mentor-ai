package ai.guitarmentor.interfaces.rest.library;

import ai.guitarmentor.infrastructure.persistence.LibraryMaterialRepository;
import ai.guitarmentor.infrastructure.persistence.LibraryMaterialRepository.Material;
import ai.guitarmentor.infrastructure.security.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/library/materials")
public class LibraryMaterialController {
    private final LibraryMaterialRepository repository;
    public LibraryMaterialController(LibraryMaterialRepository repository) { this.repository=repository; }

    private UUID owner(HttpServletRequest request) {
        CurrentActor actor=(CurrentActor)request.getAttribute("guitarMentorActor");
        if(actor==null) throw new ApiError(401,"UNAUTHENTICATED");
        return actor.accountId();
    }
    public record Edit(@NotBlank @Size(max=200) String title,
                       @NotBlank String materialType, @Size(max=4000) String description) {}
    public record Page(List<Material> items,String nextCursor) {}

    @GetMapping
    public ResponseEntity<Page> list(HttpServletRequest request,
            @RequestParam(defaultValue="20") int limit, @RequestParam(required=false) String cursor) {
        if(limit<1||limit>100) throw new ApiError(400,"INVALID_LIMIT");
        OffsetDateTime time=null; UUID id=null;
        if(cursor!=null) {
            try {
                String decoded=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8);
                String[] parts=decoded.split("\\|",-1);
                if(parts.length!=2) throw new IllegalArgumentException();
                time=OffsetDateTime.parse(parts[0]); id=UUID.fromString(parts[1]);
            } catch(RuntimeException ex) {throw new ApiError(400,"INVALID_CURSOR");}
        }
        List<Material> rows=repository.page(owner(request),time,id,limit+1);
        boolean more=rows.size()>limit;
        List<Material> items=new ArrayList<>(rows.subList(0,Math.min(limit,rows.size())));
        String next=null;
        if(more) {
            Material last=items.get(items.size()-1);
            next=Base64.getUrlEncoder().withoutPadding().encodeToString(
                (last.createdAt().toString()+"|"+last.id()).getBytes(StandardCharsets.UTF_8));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Page(items,next));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Material> get(HttpServletRequest request,@PathVariable UUID id) {
        Material result=repository.find(owner(request),id).orElseThrow(()->new ApiError(404,"NOT_FOUND"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .eTag("\"" + result.revision() + "\"").body(result);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Material> edit(HttpServletRequest request,@PathVariable UUID id,
            @RequestHeader(value="If-Match",required=false) String ifMatch,@Valid @RequestBody Edit edit) {
        UUID account=owner(request);
        if(!List.of("EXERCISE","ETUDE","COMPOSITION","OTHER").contains(edit.materialType()))
            throw new ApiError(400,"INVALID_TYPE");
        if(edit.title()==null||edit.title().isBlank()||edit.title().length()>200
                ||edit.description()!=null&&edit.description().length()>4000)
            throw new ApiError(400,"INVALID_METADATA");
        long revision=parseRevision(ifMatch);
        if(repository.update(account,id,revision,edit.title().strip(),edit.materialType(),
                    edit.description()==null?"":edit.description())==0) {
            if(repository.find(account,id).isEmpty()) throw new ApiError(404,"NOT_FOUND");
            throw new ApiError(412,"REVISION_CONFLICT");
        }
        return get(request,id);
    }


    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable UUID id,
            @RequestHeader(value="If-Match",required=false) String ifMatch) {
        UUID account=owner(request);
        long revision=parseRevision(ifMatch);
        if(repository.delete(account,id,revision)==0) {
            if(repository.find(account,id).isEmpty()) throw new ApiError(404,"NOT_FOUND");
            throw new ApiError(412,"REVISION_CONFLICT");
        }
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private long parseRevision(String ifMatch) {
        if(ifMatch==null || !ifMatch.matches("\\\"[0-9]+\\\""))
            throw new ApiError(428,"REVISION_REQUIRED");
        try { return Long.parseLong(ifMatch.substring(1,ifMatch.length()-1)); }
        catch(NumberFormatException e) {throw new ApiError(400,"INVALID_REVISION");}
    }

    static final class ApiError extends RuntimeException {
        final int status; final String code;
        ApiError(int status,String code){super(code);this.status=status;this.code=code;}
    }
    @ExceptionHandler(ApiError.class)
    ResponseEntity<Map<String,String>> error(ApiError e) {
        return ResponseEntity.status(e.status).cacheControl(CacheControl.noStore())
            .body(Map.of("code",e.code));
    }
}
