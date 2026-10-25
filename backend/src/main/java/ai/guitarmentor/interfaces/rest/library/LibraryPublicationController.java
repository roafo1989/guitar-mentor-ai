package ai.guitarmentor.interfaces.rest.library;

import ai.guitarmentor.infrastructure.persistence.LibraryObjectStore;
import ai.guitarmentor.infrastructure.security.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * FT-012-01: explicit, immutable publication revisions with item-level rights.
 * Public access means authenticated/admitted users only. Shared searching is #166.
 * Imported private musical-index records are not related to these tables.
 */
@RestController
public class LibraryPublicationController {
    private static final Set<String> BASES=Set.of("OWN_WORK","LICENSE","PUBLIC_DOMAIN","PERMISSION");
    private static final Set<String> PROVENANCES=Set.of("CREATED","LICENSED","PUBLIC_DOMAIN","PERMISSION");
    private final JdbcClient jdbc;
    private final LibraryObjectStore storage;
    private final boolean enabled;

    public LibraryPublicationController(JdbcClient jdbc,LibraryObjectStore storage,
            @Value("${guitar-mentor.library.enabled:false}") boolean enabled) {
        this.jdbc=jdbc;this.storage=storage;this.enabled=enabled;
    }
    public record AssetRight(UUID assetId,String provenance, @com.fasterxml.jackson.annotation.JsonAlias("basis") String rightsBasis, @com.fasterxml.jackson.annotation.JsonAlias("evidence") String rightsEvidence, String attribution) { public AssetRight(UUID id,String p,String b,String e){ this(id,p,b,e,""); } }
    public record PublishRequest(@com.fasterxml.jackson.annotation.JsonAlias("assetRights") List<AssetRight> assets) {}
    public record AssetSnapshot(UUID id,String originalName,String format,long byteSize,String sha256,
            String provenance,String rightsBasis,String rightsEvidence,String attribution,String downloadUrl) {}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Publication(UUID id,UUID materialId,UUID ownerAccountId,long version,
            String title,String type,String author,String state,long revision,List<AssetSnapshot> assets) {}
    public record HideRequest(String reason) {}

    private CurrentActor actor(HttpServletRequest request) {
        CurrentActor a=(CurrentActor)request.getAttribute("guitarMentorActor");
        if(a==null) throw new Failure(401,"UNAUTHENTICATED");
        if(!enabled) throw new Failure(503,"LIBRARY_DISABLED");
        return a;
    }
    private void audit(UUID publicationId,UUID actor,String action,String reason) {
        jdbc.sql("""
            INSERT INTO library_publication_events
                (id,publication_id,actor_account_id,action,reason)
            VALUES(:id,:publication,:actor,:action,:reason)
            """).param("id",UUID.randomUUID()).param("publication",publicationId)
            .param("actor",actor).param("action",action).param("reason",reason).update();
    }
    private static long revision(String ifMatch) {
        if(ifMatch==null) throw new Failure(428,"PUBLICATION_PRECONDITION_REQUIRED");
        if(!ifMatch.matches("\"[1-9][0-9]*\"")) throw new Failure(400,"PUBLICATION_INVALID_REVISION");
        try {return Long.parseLong(ifMatch.substring(1,ifMatch.length()-1));}
        catch(NumberFormatException e){throw new Failure(400,"PUBLICATION_INVALID_REVISION");}
    }
    private static String detailPath(UUID id) {return "/api/v1/community/publications/"+id;}
    private static String filePath(UUID id,UUID asset) {
        return detailPath(id)+"/files/"+asset+"/content";
    }
    private Publication snapshot(Map<String,Object> row,boolean includePrivateEvidence) {
        UUID id=(UUID)row.get("id");
        var files=jdbc.sql("""
            SELECT asset_id,original_name,format,byte_size,sha256,provenance,rights_basis,rights_evidence,attribution
            FROM library_publication_assets WHERE publication_id=:id ORDER BY asset_id
            """).param("id",id).query().listOfRows().stream().map(a->new AssetSnapshot(
                (UUID)a.get("asset_id"),(String)a.get("original_name"),
                (String)a.get("format"),((Number)a.get("byte_size")).longValue(),
                (String)a.get("sha256"),(String)a.get("provenance"),
                (String)a.get("rights_basis"),includePrivateEvidence?(String)a.get("rights_evidence"):null,
                (String)a.get("attribution"),"PUBLISHED".equals(row.get("state"))?filePath(id,(UUID)a.get("asset_id")):null)).toList();
        return new Publication(id,includePrivateEvidence?(UUID)row.get("material_id"):null,
            includePrivateEvidence?(UUID)row.get("owner_account_id"):null,((Number)row.get("version")).longValue(),
            (String)row.get("title"),(String)row.get("material_type"),
            (String)row.get("author"),(String)row.get("state"),
            ((Number)row.get("publication_revision")).longValue(),files);
    }


    private static UUID idempotencyKey(String raw) {
        if(raw==null) throw new Failure(428,"PUBLICATION_IDEMPOTENCY_REQUIRED");
        try {return UUID.fromString(raw);}
        catch(IllegalArgumentException ex){throw new Failure(400,"PUBLICATION_INVALID_IDEMPOTENCY_KEY");}
    }
    private static String fingerprint(UUID material,long revision,PublishRequest payload) {
        if(payload==null || payload.assets()==null || payload.assets().isEmpty())
            throw new Failure(400,"PUBLICATION_INVALID_RIGHTS");
        List<String> normalized=new ArrayList<>();
        for(AssetRight a:payload.assets()) {
            if(a==null || a.assetId()==null) throw new Failure(400,"PUBLICATION_INVALID_RIGHTS");
            normalized.add(a.assetId()+"|"+a.provenance()+"|"+a.rightsBasis()
                +"|"+a.rightsEvidence()+"|"+a.attribution());
        }
        Collections.sort(normalized);
        byte[] body=(material+"|"+revision+"|"+String.join("\n",normalized))
            .getBytes(StandardCharsets.UTF_8);
        try {return HexFormat.of().formatHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(body));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }

    @PostMapping("/api/v1/library/materials/{materialId}/publications")
    @Transactional
    public ResponseEntity<Publication> publish(HttpServletRequest request,
            @PathVariable UUID materialId,
            @RequestHeader(value="If-Match",required=false) String ifMatch,
            @RequestHeader(value="Idempotency-Key",required=false) String idempotency,
            @RequestBody PublishRequest payload) {
        UUID owner=actor(request).accountId();
        long currentRevision=revision(ifMatch);
        UUID key=idempotencyKey(idempotency);
        String signature=fingerprint(materialId,currentRevision,payload);
        var previous=jdbc.sql("""
            SELECT * FROM library_publications
            WHERE owner_account_id=:owner AND idempotency_key=:key
            """).param("owner",owner).param("key",key).query().listOfRows();
        if(!previous.isEmpty()) {
            var old=previous.getFirst();
            if(!materialId.equals(old.get("material_id"))
                    || !signature.equals(old.get("request_fingerprint")))
                throw new Failure(409,"PUBLICATION_IDEMPOTENCY_CONFLICT");
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(snapshot(old,true));
        }
        // Serialized with PATCH and DELETE on the material row; no uncommitted asset selection.
        var source=jdbc.sql("""
            SELECT id,owner_account_id,title,material_type,author,revision
            FROM library_materials
            WHERE id=:id AND owner_account_id=:owner AND state='READY' FOR UPDATE
            """).param("id",materialId).param("owner",owner).query().listOfRows();
        if(source.isEmpty()) throw new Failure(404,"LIBRARY_MATERIAL_NOT_FOUND");
        var material=source.getFirst();
        if(((Number)material.get("revision")).longValue()!=currentRevision)
            throw new Failure(412,"LIBRARY_REVISION_CONFLICT");
        long already=jdbc.sql("""
            SELECT count(*) FROM library_publications
            WHERE material_id=:id AND state='PUBLISHED'
            """).param("id",materialId).query(Long.class).single();
        if(already!=0) throw new Failure(409,"PUBLICATION_ALREADY_ACTIVE");

        var assets=jdbc.sql("""
            SELECT id,original_name,format,byte_size,sha256,object_key
            FROM library_assets WHERE material_id=:id ORDER BY id FOR SHARE
            """).param("id",materialId).query().listOfRows();
        if(assets.isEmpty() || assets.size()>5 || payload==null || payload.assets()==null
                || payload.assets().size()!=assets.size())
            throw new Failure(400,"PUBLICATION_INVALID_RIGHTS");
        Map<UUID,AssetRight> rights=new HashMap<>();
        for(var right:payload.assets()) {
            if(right==null || right.assetId()==null || right.rightsBasis()==null
                    || !BASES.contains(right.rightsBasis())
                    || (right.provenance()!=null && !PROVENANCES.contains(right.provenance()))
                    || (right.attribution()!=null && right.attribution().length()>2000)
                    || right.rightsEvidence()==null || right.rightsEvidence().isBlank()
                    || right.rightsEvidence().trim().length()<8
                    || right.rightsEvidence().length()>500
                    || rights.putIfAbsent(right.assetId(),right)!=null)
                throw new Failure(400,"PUBLICATION_INVALID_RIGHTS");
        }
        // Assertions must be made independently for every asset. A material-level checkbox
        // cannot attest rights to a second file. Evidence is recorded, not independently verified.
        for(var asset:assets) {
            UUID assetId=(UUID)asset.get("id");
            if(!rights.containsKey(assetId)) throw new Failure(400,"PUBLICATION_INVALID_RIGHTS");
        }
        long latest=jdbc.sql("""
            SELECT COALESCE(max(version),0) FROM library_publications WHERE material_id=:id
            """).param("id",materialId).query(Long.class).single();
        UUID pub=UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO library_publications
              (id,material_id,owner_account_id,version,material_revision,idempotency_key,
               request_fingerprint,title,material_type,author,state)
            VALUES(:id,:material,:owner,:version,:revision,:key,:fingerprint,:title,:type,:author,'PUBLISHED')
            """).param("id",pub).param("material",materialId).param("owner",owner)
            .param("version",latest+1).param("revision",currentRevision)
            .param("key",key).param("fingerprint",signature)
            .param("title",material.get("title")).param("type",material.get("material_type"))
            .param("author",material.get("author")).update();
        for(var asset:assets) {
            var right=rights.get((UUID)asset.get("id"));
            jdbc.sql("""
                INSERT INTO library_publication_assets
                  (publication_id,asset_id,original_name,format,byte_size,sha256,object_key,
                   provenance,rights_basis,rights_evidence,attribution)
                VALUES(:publication,:id,:name,:format,:size,:sha,:key,:provenance,:basis,:evidence,:attribution)
                """).param("publication",pub).param("id",asset.get("id"))
                .param("name",asset.get("original_name")).param("format",asset.get("format"))
                .param("size",asset.get("byte_size")).param("sha",asset.get("sha256"))
                .param("key",asset.get("object_key"))
                .param("provenance",right.provenance()==null?switch(right.rightsBasis()) {
                    case "OWN_WORK" -> "CREATED";
                    case "LICENSE" -> "LICENSED";
                    case "PUBLIC_DOMAIN" -> "PUBLIC_DOMAIN";
                    default -> "PERMISSION";
                }:right.provenance())
                .param("basis",right.rightsBasis())
                .param("evidence",right.rightsEvidence().trim())
                .param("attribution",right.attribution()==null?"":right.attribution().trim()).update();
        }
        audit(pub,owner,"PUBLISH",null);
        var item=jdbc.sql("SELECT * FROM library_publications WHERE id=:id")
            .param("id",pub).query().singleRow();
        return ResponseEntity.created(java.net.URI.create(detailPath(pub)))
            .cacheControl(CacheControl.noStore()).body(snapshot(item,true));
    }

    /** Existing direct service tests; HTTP calls always require an explicit UUID key. */
    ResponseEntity<Publication> publish(HttpServletRequest request,UUID materialId,
            String ifMatch,PublishRequest payload) {
        return publish(request,materialId,ifMatch,UUID.randomUUID().toString(),payload);
    }

    /** Owner can see own full history; another actor cannot enumerate it. */
    @GetMapping("/api/v1/library/materials/{materialId}/publications")
    public ResponseEntity<List<Publication>> ownerHistory(HttpServletRequest request,
            @PathVariable UUID materialId) {
        UUID owner=actor(request).accountId();
        boolean belongs=jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM library_materials
                          WHERE id=:id AND owner_account_id=:owner)
            """).param("id",materialId).param("owner",owner).query(Boolean.class).single();
        if(!belongs) throw new Failure(404,"LIBRARY_MATERIAL_NOT_FOUND");
        var rows=jdbc.sql("""
            SELECT * FROM library_publications
            WHERE material_id=:id AND owner_account_id=:owner ORDER BY version DESC
            """).param("id",materialId).param("owner",owner).query().listOfRows();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(rows.stream().map(row->snapshot(row,true)).toList());
    }

    @GetMapping("/api/v1/community/publications/{publicationId}")
    public ResponseEntity<Publication> publishedDetail(HttpServletRequest request,
            @PathVariable UUID publicationId) {
        actor(request);
        var records=jdbc.sql("""
            SELECT p.* FROM library_publications p
            JOIN library_materials m ON m.id=p.material_id
            WHERE p.id=:id AND p.state='PUBLISHED' AND m.state='READY'
            """).param("id",publicationId).query().listOfRows();
        if(records.isEmpty()) throw new Failure(404,"PUBLICATION_NOT_FOUND");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(snapshot(records.getFirst(),false));
    }

    public record WithdrawRequest(String reason) {}

    @PostMapping("/api/v1/library/materials/{materialId}/publications/{publicationId}/withdraw")
    @Transactional
    public ResponseEntity<Publication> withdraw(HttpServletRequest request,
            @PathVariable UUID materialId,@PathVariable UUID publicationId,
            @RequestHeader(value="If-Match",required=false) String ifMatch,
            @RequestBody(required=false) WithdrawRequest payload) {
        UUID owner=actor(request).accountId();
        long requested=revision(ifMatch);
        jdbc.sql("""
            SELECT id FROM library_materials
            WHERE id=:material AND owner_account_id=:owner FOR UPDATE
            """).param("material",materialId).param("owner",owner).query(UUID.class).optional()
            .orElseThrow(()->new Failure(404,"PUBLICATION_NOT_FOUND"));
        var versions=jdbc.sql("""
            SELECT * FROM library_publications
            WHERE id=:publication AND material_id=:material AND owner_account_id=:owner
            FOR UPDATE
            """).param("publication",publicationId).param("material",materialId)
                .param("owner",owner).query().listOfRows();
        if(versions.isEmpty()) throw new Failure(404,"PUBLICATION_NOT_FOUND");
        var original=versions.getFirst();
        // Idempotent retry: do not duplicate audit events or resurrect HIDDEN revisions.
        if(!"PUBLISHED".equals(original.get("state")))
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(snapshot(original,true));
        if(((Number)original.get("publication_revision")).longValue()!=requested)
            throw new Failure(412,"PUBLICATION_REVISION_CONFLICT");
        String reason=payload==null?null:payload.reason();
        if(reason!=null && reason.length()>500)
            throw new Failure(400,"PUBLICATION_INVALID_REASON");
        jdbc.sql("""
            UPDATE library_publications SET state='WITHDRAWN',ended_at=now(),
                publication_revision=publication_revision+1 WHERE id=:publication
            """).param("publication",publicationId).update();
        audit(publicationId,owner,"WITHDRAW",reason);
        var state=jdbc.sql("SELECT * FROM library_publications WHERE id=:id")
            .param("id",publicationId).query().singleRow();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(snapshot(state,true));
    }

    /** Existing integration tests; live HTTP requests require publication If-Match. */
    ResponseEntity<Publication> withdraw(HttpServletRequest request,UUID materialId,UUID publicationId) {
        return withdraw(request,materialId,publicationId,"\"1\"",null);
    }

    @PostMapping({"/api/v1/community/publications/{publicationId}/hide",
        "/api/v1/admin/catalog/publications/{publicationId}/hide"})
    @Transactional
    public ResponseEntity<Publication> hide(HttpServletRequest request,
            @PathVariable UUID publicationId,
            @RequestHeader(value="If-Match",required=false) String ifMatch,
            @RequestBody HideRequest payload) {
        CurrentActor moderator=actor(request);
        if(!"OWNER".equals(moderator.role()))
            throw new Failure(403,"PUBLICATION_MODERATION_FORBIDDEN");
        long expected=revision(ifMatch);
        String reason=payload==null?null:payload.reason();
        if(reason==null || reason.trim().length()<8 || reason.length()>500)
            throw new Failure(400,"PUBLICATION_INVALID_REASON");
        // Serialize with the owner DELETE and WITHDRAW operations.
        UUID material=jdbc.sql("""
            SELECT m.id FROM library_publications p JOIN library_materials m ON m.id=p.material_id
            WHERE p.id=:id FOR UPDATE OF m
            """).param("id",publicationId).query(UUID.class).optional()
            .orElseThrow(()->new Failure(404,"PUBLICATION_NOT_FOUND"));
        var rows=jdbc.sql("""
            SELECT * FROM library_publications WHERE id=:id FOR UPDATE
            """).param("id",publicationId).query().listOfRows();
        if(rows.isEmpty()) throw new Failure(404,"PUBLICATION_NOT_FOUND");
        var old=rows.getFirst();
        if("HIDDEN".equals(old.get("state")))
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(snapshot(old,false)); // repeat never duplicates audit
        if(!"PUBLISHED".equals(old.get("state")))
            throw new Failure(404,"PUBLICATION_NOT_FOUND");
        if(((Number)old.get("publication_revision")).longValue()!=expected)
            throw new Failure(412,"PUBLICATION_REVISION_CONFLICT");
        jdbc.sql("""
            UPDATE library_publications
            SET state='HIDDEN',ended_at=now(),publication_revision=publication_revision+1
            WHERE id=:id AND state='PUBLISHED'
            """).param("id",publicationId).update();
        audit(publicationId,moderator.accountId(),"HIDE",reason.trim());
        var current=jdbc.sql("SELECT * FROM library_publications WHERE id=:id")
            .param("id",publicationId).query().singleRow();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(snapshot(current,false));
    }

    /** Direct service regression tests; HTTP requires If-Match. */
    ResponseEntity<Publication> hide(HttpServletRequest request,UUID id,HideRequest payload) {
        return hide(request,id,"\"1\"",payload);
    }

    @RequestMapping(path="/api/v1/community/publications/{publicationId}/files/{assetId}/content",
        method={RequestMethod.GET,RequestMethod.HEAD})
    public ResponseEntity<?> publishedContent(HttpServletRequest request,
            @PathVariable UUID publicationId,@PathVariable UUID assetId) {
        actor(request);
        var rows=jdbc.sql("""
            SELECT a.object_key,a.original_name,a.format,a.byte_size
            FROM library_publications p
            JOIN library_materials m ON m.id=p.material_id AND m.state='READY'
            JOIN library_publication_assets a ON a.publication_id=p.id
            JOIN library_assets source ON source.id=a.asset_id
                 AND source.material_id=p.material_id AND source.object_key=a.object_key
            WHERE p.id=:publication AND p.state='PUBLISHED' AND a.asset_id=:asset
            """).param("publication",publicationId).param("asset",assetId).query().listOfRows();
        if(rows.isEmpty()) throw new Failure(404,"PUBLICATION_NOT_FOUND");
        var asset=rows.getFirst();
        try {
            var path=storage.file((String)asset.get("object_key"));
            var headers=new HttpHeaders();
            headers.setCacheControl(CacheControl.noStore());
            headers.add("X-Content-Type-Options","nosniff");
            headers.setContentDisposition(ContentDisposition.attachment()
                .filename((String)asset.get("original_name"),StandardCharsets.UTF_8).build());
            headers.setContentType("PDF".equals(asset.get("format"))?
                MediaType.APPLICATION_PDF:MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentLength(Files.size(path));
            if("HEAD".equals(request.getMethod()))
                return ResponseEntity.ok().headers(headers).build();
            return ResponseEntity.ok().headers(headers).body(new FileSystemResource(path));
        } catch(IOException e) {throw new Failure(503,"PUBLICATION_STORAGE_UNAVAILABLE");}
    }

    static final class Failure extends RuntimeException {
        final int status; final String code;
        Failure(int status,String code){super(code);this.status=status;this.code=code;}
    }
    @ExceptionHandler(Failure.class)
    ResponseEntity<Map<String,String>> error(Failure e) {
        return ResponseEntity.status(e.status).cacheControl(CacheControl.noStore())
            .body(Map.of("code",e.code));
    }
}
