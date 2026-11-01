package ai.guitarmentor.interfaces.rest.library;

import ai.guitarmentor.infrastructure.persistence.LibraryMaterialRepository;
import ai.guitarmentor.infrastructure.persistence.LibraryMaterialRepository.Material;
import ai.guitarmentor.infrastructure.security.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/library/materials")
public class LibraryMaterialController {
    private final LibraryMaterialRepository repository;
    private final JdbcClient jdbc;
    private final byte[] cursorSecret;
    private final boolean enabled;

    @org.springframework.beans.factory.annotation.Autowired
    public LibraryMaterialController(LibraryMaterialRepository repository,
            JdbcClient jdbc,
            @Value("${guitar-mentor.library.cursor-secret:}") String secret,
            @Value("${guitar-mentor.library.enabled:false}") boolean enabled) {
        this.repository=repository;
        this.jdbc=jdbc;
        this.cursorSecret=secret.getBytes(StandardCharsets.UTF_8);
        this.enabled=enabled;
        if(enabled && this.cursorSecret.length<32)
            throw new IllegalStateException("Library cursor secret must be at least 32 bytes");
    }

    /** Legacy direct-construction unit tests; live Spring injection always supplies JdbcClient. */
    LibraryMaterialController(LibraryMaterialRepository repository,String secret,boolean enabled) {
        this(repository,null,secret,enabled);
    }

    private UUID owner(HttpServletRequest request) {
        CurrentActor actor=(CurrentActor)request.getAttribute("guitarMentorActor");
        if(actor==null) throw new ApiError(401,"UNAUTHENTICATED");
        if(!enabled) throw new ApiError(503,"LIBRARY_DISABLED");
        return actor.accountId();
    }

    public record FileView(UUID id,String originalName,String format,long byteSize,
            String sha256,String validationStatus,String downloadUrl,String role,String mediaType,
            Long durationMillis,Integer channels,Integer sampleRate,Integer width,Integer height,
            String processingState,boolean previewAvailable,String previewReasonCode,String previewUrl) {}
    public record MaterialView(UUID id,String title,String type,String author,
            String state,String visibility,String reviewStatus,long revision,
            OffsetDateTime createdAt,OffsetDateTime updatedAt,List<FileView> files) {}
    public record Page(List<MaterialView> items,String nextCursor) {}
    public record Deleting(UUID id,String state) {}

    private MaterialView view(UUID owner,Material m) {
        List<FileView> files=repository.assets(owner,m.id()).stream().map(a -> new FileView(
            a.id(),a.originalName(),a.format(),a.byteSize(),a.sha256(),
            "REJECTED".equals(a.processingState())?"MEDIA_UNAVAILABLE":
                (a.format().equals("PDF")?"PDF_HEADER_CHECKED":"ORIGINAL_UNVERIFIED"),
            "/api/v1/library/materials/"+m.id()+"/files/"+a.id()+"/content",a.role(),a.mediaType(),
            a.durationMillis(),a.channels(),a.sampleRate(),a.width(),a.height(),a.processingState(),a.previewAvailable(),
            "PHOTO_ORIGINAL".equals(a.role())&&!a.previewAvailable()?"MEDIA_UNAVAILABLE":null,
            a.previewAvailable()?"/api/v1/library/materials/"+m.id()+"/files/"+a.id()+"/preview":null)).toList();
        return new MaterialView(m.id(),m.title(),m.type(),m.author(),m.state(),
                "PRIVATE","UNREVIEWED",m.revision(),m.createdAt(),m.updatedAt(),files);
    }

    @GetMapping
    public ResponseEntity<Page> list(HttpServletRequest request,
            @RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor) {
        UUID account=owner(request);
        if(limit<1||limit>50) throw new ApiError(400,"LIBRARY_INVALID_METADATA");
        OffsetDateTime before=null; UUID id=null;
        if(cursor!=null) {
            try {
                String decoded=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8);
                String[] segments=decoded.split("\\|",-1);
                if(segments.length!=4 || !segments[0].equals("v1")
                        || !segments[1].equals(account.toString())) throw new IllegalArgumentException();
                String message=String.join("|",segments[0],segments[1],segments[2]);
                if(!java.security.MessageDigest.isEqual(
                    sign(message).getBytes(StandardCharsets.US_ASCII),
                    segments[3].getBytes(StandardCharsets.US_ASCII))) throw new IllegalArgumentException();
                String[] key=segments[2].split(",",-1);
                if(key.length!=2) throw new IllegalArgumentException();
                before=OffsetDateTime.parse(key[0]); id=UUID.fromString(key[1]);
            } catch(RuntimeException ex) {throw new ApiError(400,"LIBRARY_INVALID_CURSOR");}
        }
        List<Material> rows=repository.page(account,before,id,limit+1);
        boolean more=rows.size()>limit;
        List<Material> slice=new ArrayList<>(rows.subList(0,Math.min(limit,rows.size())));
        String next=null;
        if(more) {
            Material last=slice.get(slice.size()-1);
            String message="v1|"+account+"|"+last.createdAt()+","+last.id();
            next=Base64.getUrlEncoder().withoutPadding().encodeToString(
                (message+"|"+sign(message)).getBytes(StandardCharsets.UTF_8));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Page(slice.stream().map(m->view(account,m)).toList(),next));
    }

    @GetMapping("/{id}")
    public ResponseEntity<MaterialView> get(HttpServletRequest request,@PathVariable UUID id) {
        UUID account=owner(request);
        Material material=repository.find(account,id)
                .orElseThrow(()->new ApiError(404,"LIBRARY_MATERIAL_NOT_FOUND"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .eTag("\"" + material.revision() + "\"").body(view(account,material));
    }

    @PatchMapping("/{id}")
    @Transactional
    public ResponseEntity<MaterialView> edit(HttpServletRequest request,@PathVariable UUID id,
            @RequestHeader(value="If-Match",required=false) String ifMatch,
            @RequestBody Map<String,Object> patch) {
        UUID account=owner(request);
        long revision=parseRevision(ifMatch);
        if(patch==null || patch.isEmpty() || patch.size()>3)
            throw new ApiError(400,"LIBRARY_INVALID_METADATA");
        if(!Set.of("title","type","author").containsAll(patch.keySet()))
            throw new ApiError(400,"LIBRARY_INVALID_METADATA");
        Material old=repository.find(account,id)
                .orElseThrow(()->new ApiError(404,"LIBRARY_MATERIAL_NOT_FOUND"));
        String title=patch.containsKey("title") ? text(patch.get("title"),false) : old.title();
        String type=patch.containsKey("type") ? text(patch.get("type"),false) : old.type();
        String author=patch.containsKey("author") ? text(patch.get("author"),true) : old.author();
        if(title==null || title.isBlank() || title.length()>150
                || !Set.of("EXERCISE","ETUDE","COMPOSITION","OTHER").contains(type)
                || author!=null&&author.length()>150)
            throw new ApiError(400,"LIBRARY_INVALID_METADATA");
        if(repository.update(account,id,revision,title,type,author)==0)
            throw new ApiError(412,"LIBRARY_REVISION_CONFLICT");
        return get(request,id);
    }

    /**
     * Owner-scoped, revision-checked and transactional soft-delete.
     * No file operation in HTTP transaction; durable worker physically removes bytes.
     * Tombstone stays in DB so old upload idempotency keys cannot resurrect a deleted card.
     */
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Deleting> delete(HttpServletRequest request,@PathVariable UUID id,
            @RequestHeader(value="If-Match",required=false) String ifMatch) {
        UUID account=owner(request);
        // Require a revision even for replay: this prevents blind destructive requests.
        long revision=parseRevision(ifMatch);
        var current=repository.findIncludingDeleting(account,id)
                .orElseThrow(()->new ApiError(404,"LIBRARY_MATERIAL_NOT_FOUND"));
        if ("DELETING".equals(current.state()))
            return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(new Deleting(id,"DELETING"));
        if (current.revision()!=revision)
            throw new ApiError(412,"LIBRARY_REVISION_CONFLICT");
        if (repository.markDeleting(account,id,revision)!=1)
            throw new ApiError(412,"LIBRARY_REVISION_CONFLICT");
        // #165: revoke shared access atomically with the tombstone and deletion intent.
        // Publish/withdraw paths lock the same material row, preventing a race.
        if(jdbc!=null) {
            var active=jdbc.sql("""
                SELECT id FROM library_publications
                WHERE material_id=:id AND state='PUBLISHED' FOR UPDATE
                """).param("id",id).query(UUID.class).list();
            for(UUID pub:active) {
                jdbc.sql("""
                    UPDATE library_publications SET state='WITHDRAWN',ended_at=now(),
                        publication_revision=publication_revision+1
                    WHERE id=:pub AND state='PUBLISHED'
                    """).param("pub",pub).update();
                jdbc.sql("""
                    INSERT INTO library_publication_events
                      (id,publication_id,actor_account_id,action)
                    VALUES(:event,:pub,:actor,'DELETE_WITHDRAW')
                    """).param("event",UUID.randomUUID()).param("pub",pub)
                    .param("actor",account).update();
            }
        }
        if(jdbc!=null) {
            jdbc.sql("""
                INSERT INTO community_work_events(id,work_id,actor_account_id,action,previous_work_id,reason_code,request_id)
                SELECT :event,work_id,:actor,'DELETE_DETACH',work_id,'MATERIAL_DELETE',:request
                FROM library_material_work_links WHERE material_id=:id
                """).param("event",UUID.randomUUID()).param("actor",account).param("request",UUID.randomUUID()).param("id",id).update();
            jdbc.sql("DELETE FROM library_material_work_links WHERE material_id=:id").param("id",id).update();
        }
        repository.queueDeletion(id);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
            .body(new Deleting(id,"DELETING"));
    }

    private String text(Object node,boolean nullable) {
        if(node==null && nullable) return null;
        if(!(node instanceof String valueRaw))
            throw new ApiError(400,"LIBRARY_INVALID_METADATA");
        String value=valueRaw.strip();
        return nullable&&value.isEmpty()?null:value;
    }

    private long parseRevision(String ifMatch) {
        if(ifMatch==null) throw new ApiError(428,"LIBRARY_PRECONDITION_REQUIRED");
        if(!ifMatch.matches("\"[1-9][0-9]*\""))
            throw new ApiError(400,"LIBRARY_INVALID_METADATA");
        try{return Long.parseLong(ifMatch.substring(1,ifMatch.length()-1));}
        catch(NumberFormatException e){throw new ApiError(400,"LIBRARY_INVALID_METADATA");}
    }

    private String sign(String message) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(cursorSecret,"HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch(Exception ex) {throw new IllegalStateException("Library cursor secret invalid",ex);}
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
