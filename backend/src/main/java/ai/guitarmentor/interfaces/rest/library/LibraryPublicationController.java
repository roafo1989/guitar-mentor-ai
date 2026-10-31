package ai.guitarmentor.interfaces.rest.library;

import ai.guitarmentor.infrastructure.persistence.LibraryObjectStore;
import ai.guitarmentor.infrastructure.security.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.nio.file.Path;
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
    private final boolean mediaEnabled;

    public LibraryPublicationController(JdbcClient jdbc,LibraryObjectStore storage,
            @Value("${guitar-mentor.library.enabled:false}") boolean enabled,
            @Value("${guitar-mentor.library.community-media-enabled:false}") boolean mediaEnabled) {
        this.jdbc=jdbc;this.storage=storage;this.enabled=enabled;this.mediaEnabled=mediaEnabled;
    }
    public record AssetRight(UUID assetId,String provenance, @com.fasterxml.jackson.annotation.JsonAlias("basis") String rightsBasis, @com.fasterxml.jackson.annotation.JsonAlias("evidence") String rightsEvidence, String attribution) { public AssetRight(UUID id,String p,String b,String e){ this(id,p,b,e,""); } }
    public record PublishRequest(
        @com.fasterxml.jackson.annotation.JsonAlias("assetRights") List<AssetRight> assets,
        List<String> tags) {
        public PublishRequest(List<AssetRight> assets) {this(assets,List.of());}
    }
    public record AssetSnapshot(UUID id,String originalName,String format,long byteSize,String sha256,
            String role,String mediaType,Long durationMillis,Integer channels,Integer sampleRate,
            Integer width,Integer height,String provenance,String rightsBasis,String rightsEvidence,
            String attribution,String downloadUrl,String previewUrl) {}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Publication(UUID id,UUID materialId,UUID ownerAccountId,long version,long sourceRevision,
            String title,String type,String author,String state,long revision,List<AssetSnapshot> assets,
            List<String> tags) {}
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
        List<String> tags=jdbc.sql("""
            SELECT tag FROM library_publication_tags WHERE publication_id=:id ORDER BY tag
            """).param("id",id).query(String.class).list();
        var files=jdbc.sql("""
            SELECT a.asset_id,a.original_name,a.format,a.byte_size,a.sha256,a.role,a.media_type,
                a.duration_millis,a.channels,a.sample_rate,a.width,a.height,a.provenance,a.rights_basis,
                a.rights_evidence,a.attribution,a.preview_object_key,source.processing_state
            FROM library_publication_assets a LEFT JOIN library_assets source ON source.id=a.asset_id
            WHERE a.publication_id=:id ORDER BY a.asset_id
            """).param("id",id).query().listOfRows().stream()
            .filter(a->mediaEnabled || "SCORE_ORIGINAL".equals(a.get("role")))
            .map(a->new AssetSnapshot(
                (UUID)a.get("asset_id"),(String)a.get("original_name"),
                (String)a.get("format"),((Number)a.get("byte_size")).longValue(),
                (String)a.get("sha256"),(String)a.get("role"),(String)a.get("media_type"),
                a.get("duration_millis")==null?null:((Number)a.get("duration_millis")).longValue(),
                a.get("channels")==null?null:((Number)a.get("channels")).intValue(),
                a.get("sample_rate")==null?null:((Number)a.get("sample_rate")).intValue(),
                a.get("width")==null?null:((Number)a.get("width")).intValue(),
                a.get("height")==null?null:((Number)a.get("height")).intValue(),
                (String)a.get("provenance"),(String)a.get("rights_basis"),
                includePrivateEvidence?(String)a.get("rights_evidence"):null,(String)a.get("attribution"),
                "PUBLISHED".equals(row.get("state"))?filePath(id,(UUID)a.get("asset_id")):null,
                "PUBLISHED".equals(row.get("state")) && "READY".equals(a.get("processing_state")) && a.get("preview_object_key")!=null?
                    "/api/v1/community/publications/"+id+"/files/"+a.get("asset_id")+"/preview":null)).toList();
        return new Publication(id,includePrivateEvidence?(UUID)row.get("material_id"):null,
            includePrivateEvidence?(UUID)row.get("owner_account_id"):null,((Number)row.get("version")).longValue(),
            ((Number)row.get("material_revision")).longValue(),(String)row.get("title"),(String)row.get("material_type"),
            (String)row.get("author"),(String)row.get("state"),
            ((Number)row.get("publication_revision")).longValue(),files,tags);
    }


    private static UUID idempotencyKey(String raw) {
        if(raw==null) throw new Failure(428,"PUBLICATION_IDEMPOTENCY_REQUIRED");
        try {return UUID.fromString(raw);}
        catch(IllegalArgumentException ex){throw new Failure(400,"PUBLICATION_INVALID_IDEMPOTENCY_KEY");}
    }
    private static List<String> normalizedTags(List<String> supplied) {
        if(supplied==null) return List.of();
        if(supplied.size()>10) throw new Failure(400,"PUBLICATION_INVALID_TAGS");
        java.util.TreeSet<String> tags=new java.util.TreeSet<>();
        for(String raw:supplied) {
            if(raw==null) throw new Failure(400,"PUBLICATION_INVALID_TAGS");
            String tag=raw.strip().toLowerCase(Locale.ROOT);
            if(!tag.matches("[\\p{L}\\p{N}][\\p{L}\\p{N} _-]{0,39}"))
                throw new Failure(400,"PUBLICATION_INVALID_TAGS");
            tags.add(tag);
        }
        return List.copyOf(tags);
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
        List<String> tags=normalizedTags(payload.tags());
        String fingerprintInput=material+"|"+revision+"|"+String.join("\n",normalized);
        // Compatibility: V28 existing publications have no tags and used the original fingerprint.
        // Identical replay after V29 must still return 200 rather than a false 409.
        if(!tags.isEmpty()) fingerprintInput+="|tags="+String.join(",",tags);
        byte[] body=fingerprintInput.getBytes(StandardCharsets.UTF_8);
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
        var sourceAssets=jdbc.sql("""
            SELECT id,original_name,format,byte_size,sha256,object_key,role,media_type,
                duration_millis,channels,sample_rate,width,height,preview_object_key,preview_byte_size,processing_state
            FROM library_assets WHERE material_id=:id ORDER BY id FOR SHARE
            """).param("id",materialId).query().listOfRows();
        // Select only explicitly attested assets belonging to this material. Never infer
        // membership from an asset UUID, title or owner supplied by the client.
        var assets=sourceAssets.stream().filter(asset->rights.containsKey((UUID)asset.get("id"))).toList();
        long scorePhoto=assets.stream().filter(a->Set.of("SCORE_ORIGINAL","PHOTO_ORIGINAL").contains(a.get("role"))).count();
        long audio=assets.stream().filter(a->Set.of("ORIGINAL_RECORDING","SYNTHESIZED_PREVIEW").contains(a.get("role"))).count();
        if(assets.isEmpty() || assets.size()>7 || scorePhoto<1 || scorePhoto>5 || audio>2
                || assets.size()!=rights.size())
            throw new Failure(400,"PUBLICATION_INVALID_RIGHTS");
        if(assets.stream().anyMatch(a->!"READY".equals(a.get("processing_state"))
                || ("PHOTO_ORIGINAL".equals(a.get("role")) && a.get("preview_object_key")==null)))
            throw new Failure(409,"CATALOG_ASSET_NOT_READY");
        if(!mediaEnabled && assets.stream().anyMatch(a->!"SCORE_ORIGINAL".equals(a.get("role"))))
            throw new Failure(503,"COMMUNITY_MEDIA_DISABLED");
        try {
            for(var asset:assets) {
                storage.file((String)asset.get("object_key"));
                if("PHOTO_ORIGINAL".equals(asset.get("role"))) storage.file((String)asset.get("preview_object_key"));
            }
        } catch(java.nio.file.NoSuchFileException e) { throw new Failure(409,"CATALOG_ASSET_NOT_READY"); }
          catch(IOException e) { throw new Failure(503,"PUBLICATION_STORAGE_UNAVAILABLE"); }
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
                   role,media_type,duration_millis,channels,sample_rate,width,height,preview_object_key,preview_byte_size,
                   provenance,rights_basis,rights_evidence,attribution)
                VALUES(:publication,:id,:name,:format,:size,:sha,:key,:role,:mime,:duration,:channels,:rate,
                   :width,:height,:preview,:previewSize,:provenance,:basis,:evidence,:attribution)
                """).param("publication",pub).param("id",asset.get("id"))
                .param("name",asset.get("original_name")).param("format",asset.get("format"))
                .param("size",asset.get("byte_size")).param("sha",asset.get("sha256"))
                .param("key",asset.get("object_key")).param("role",asset.get("role"))
                .param("mime",asset.get("media_type")).param("duration",asset.get("duration_millis"))
                .param("channels",asset.get("channels")).param("rate",asset.get("sample_rate"))
                .param("width",asset.get("width")).param("height",asset.get("height"))
                .param("preview",asset.get("preview_object_key")).param("previewSize",asset.get("preview_byte_size"))
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
        for(String tag:normalizedTags(payload.tags())) {
            jdbc.sql("INSERT INTO library_publication_tags(publication_id,tag) VALUES(:id,:tag)")
                .param("id",pub).param("tag",tag).update();
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
            SELECT a.object_key,a.original_name,a.format,a.byte_size,a.role,a.media_type
            FROM library_publications p
            JOIN library_materials m ON m.id=p.material_id AND m.state='READY'
            JOIN library_publication_assets a ON a.publication_id=p.id
            JOIN library_assets source ON source.id=a.asset_id
                 AND source.material_id=p.material_id AND source.object_key=a.object_key
                 AND source.processing_state='READY' AND source.role=a.role
                 AND source.media_type=a.media_type
            WHERE p.id=:publication AND p.state='PUBLISHED' AND a.asset_id=:asset
            """).param("publication",publicationId).param("asset",assetId).query().listOfRows();
        if(rows.isEmpty()) {
            var unavailable=jdbc.sql("""
                SELECT a.role FROM library_publications p JOIN library_publication_assets a ON a.publication_id=p.id
                LEFT JOIN library_assets source ON source.id=a.asset_id
                WHERE p.id=:publication AND p.state='PUBLISHED' AND a.asset_id=:asset
                  AND (source.id IS NULL OR source.processing_state<>'READY')
                """).param("publication",publicationId).param("asset",assetId).query().listOfRows();
            if(!unavailable.isEmpty()) {
                if(!Set.of("SCORE_ORIGINAL").contains(unavailable.getFirst().get("role")) && !mediaEnabled)
                    throw new Failure(503,"COMMUNITY_MEDIA_DISABLED");
                throw new Failure(404,"CATALOG_FILE_UNAVAILABLE");
            }
            throw new Failure(404,"PUBLICATION_NOT_FOUND");
        }
        var asset=rows.getFirst();
        if(!Set.of("SCORE_ORIGINAL").contains(asset.get("role")) && !mediaEnabled)
            throw new Failure(503,"COMMUNITY_MEDIA_DISABLED");
        try {
            var path=storage.file((String)asset.get("object_key"));
            var headers=new HttpHeaders();
            headers.setCacheControl(CacheControl.noStore());
            headers.add("X-Content-Type-Options","nosniff");
            boolean audio=Set.of("ORIGINAL_RECORDING","SYNTHESIZED_PREVIEW").contains(asset.get("role"));
            headers.setContentDisposition(audio?ContentDisposition.inline().filename((String)asset.get("original_name"),StandardCharsets.UTF_8).build():
                ContentDisposition.attachment().filename((String)asset.get("original_name"),StandardCharsets.UTF_8).build());
            headers.setContentType(MediaType.parseMediaType(Objects.toString(asset.get("media_type"),
                "PDF".equals(asset.get("format"))?"application/pdf":"application/octet-stream")));
            long length=Files.size(path); headers.setContentLength(length); headers.set(HttpHeaders.ACCEPT_RANGES,audio?"bytes":"none");
            if("HEAD".equals(request.getMethod())) return ResponseEntity.ok().headers(headers).build();
            String range=request.getHeader(HttpHeaders.RANGE);
            if(range!=null) {
                if(!audio) return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE,"bytes */"+length).cacheControl(CacheControl.noStore()).build();
                long[] bounds;
                try { bounds=parseRange(range,length); }
                catch(Failure invalid) { if(invalid.status==416) return ResponseEntity.status(416)
                    .header(HttpHeaders.CONTENT_RANGE,"bytes */"+length).cacheControl(CacheControl.noStore()).build(); throw invalid; }
                long count=bounds[1]-bounds[0]+1;
                headers.set(HttpHeaders.CONTENT_RANGE,"bytes "+bounds[0]+"-"+bounds[1]+"/"+length); headers.setContentLength(count);
                return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).headers(headers)
                    .body(new org.springframework.core.io.InputStreamResource(rangeStream(path,bounds[0],count)));
            }
            return ResponseEntity.ok().headers(headers).body(new FileSystemResource(path));
        } catch(java.nio.file.NoSuchFileException e) {throw new Failure(404,"CATALOG_FILE_UNAVAILABLE");}
          catch(IOException e) {throw new Failure(503,"PUBLICATION_STORAGE_UNAVAILABLE");}
    }

    @RequestMapping(path="/api/v1/community/publications/{publicationId}/files/{assetId}/preview",
        method={RequestMethod.GET,RequestMethod.HEAD})
    public ResponseEntity<?> publishedPreview(HttpServletRequest request,
            @PathVariable UUID publicationId,@PathVariable UUID assetId) {
        actor(request);
        if(!mediaEnabled) throw new Failure(503,"COMMUNITY_MEDIA_DISABLED");
        var rows=jdbc.sql("""
            SELECT a.preview_object_key,a.media_type
            FROM library_publications p
            JOIN library_materials m ON m.id=p.material_id AND m.state='READY'
            JOIN library_publication_assets a ON a.publication_id=p.id AND a.asset_id=:asset
            JOIN library_assets source ON source.id=a.asset_id AND source.material_id=p.material_id
              AND source.processing_state='READY' AND source.role='PHOTO_ORIGINAL'
              AND source.role=a.role AND source.media_type=a.media_type
              AND source.preview_object_key=a.preview_object_key AND a.preview_object_key IS NOT NULL
            WHERE p.id=:publication AND p.state='PUBLISHED'
            """).param("publication",publicationId).param("asset",assetId).query().listOfRows();
        if(rows.isEmpty()) {
            var unavailable=jdbc.sql("""
                SELECT a.asset_id FROM library_publications p JOIN library_publication_assets a ON a.publication_id=p.id
                LEFT JOIN library_assets source ON source.id=a.asset_id
                WHERE p.id=:publication AND p.state='PUBLISHED' AND a.asset_id=:asset
                  AND (source.id IS NULL OR source.processing_state<>'READY' OR source.preview_object_key IS NULL)
                """).param("publication",publicationId).param("asset",assetId).query().listOfRows();
            if(!unavailable.isEmpty()) throw new Failure(404,"CATALOG_FILE_UNAVAILABLE");
            throw new Failure(404,"PUBLICATION_PREVIEW_NOT_FOUND");
        }
        try {
            var row=rows.getFirst(); var path=storage.file((String)row.get("preview_object_key"));
            HttpHeaders headers=new HttpHeaders(); headers.setCacheControl(CacheControl.noStore());
            headers.add("X-Content-Type-Options","nosniff"); headers.setContentType(MediaType.parseMediaType((String)row.get("media_type")));
            headers.setContentLength(Files.size(path)); headers.setContentDisposition(ContentDisposition.inline().filename("preview."+
                (((String)row.get("media_type")).endsWith("png")?"png":"jpg")).build());
            if("HEAD".equals(request.getMethod())) return ResponseEntity.ok().headers(headers).build();
            return ResponseEntity.ok().headers(headers).body(new FileSystemResource(path));
        } catch(java.nio.file.NoSuchFileException e) { throw new Failure(404,"CATALOG_FILE_UNAVAILABLE"); }
          catch(IOException e) { throw new Failure(503,"PUBLICATION_STORAGE_UNAVAILABLE"); }
    }

    private static InputStream rangeStream(Path path,long start,long count) throws IOException {
        InputStream input=Files.newInputStream(path);
        try {
            input.skipNBytes(start);
            return new FilterInputStream(input) {
                private long remaining=count;
                @Override public int read() throws IOException {
                    if(remaining<=0) return -1;
                    int value=super.read();
                    if(value>=0) remaining--;
                    return value;
                }
                @Override public int read(byte[] bytes,int offset,int length) throws IOException {
                    if(remaining<=0) return -1;
                    int read=super.read(bytes,offset,(int)Math.min((long)length,remaining));
                    if(read>0) remaining-=read;
                    return read;
                }
            };
        } catch(IOException e) {
            try { input.close(); } catch(IOException ignored) {}
            throw e;
        }
    }

    private static long[] parseRange(String raw,long length) {
        if(!raw.startsWith("bytes=") || raw.indexOf(',')>=0) throw new Failure(416,"LIBRARY_INVALID_RANGE");
        String[] parts=raw.substring(6).split("-",-1);
        if(parts.length!=2) throw new Failure(416,"LIBRARY_INVALID_RANGE");
        try {
            long start,end;
            if(parts[0].isEmpty()) {long suffix=Long.parseLong(parts[1]); if(suffix<=0) throw new NumberFormatException(); start=Math.max(0,length-suffix); end=length-1;}
            else {start=Long.parseLong(parts[0]); end=parts[1].isEmpty()?length-1:Long.parseLong(parts[1]);}
            if(start<0 || start>=length || end<start) throw new NumberFormatException();
            return new long[]{start,Math.min(end,length-1)};
        } catch(NumberFormatException e) {throw new Failure(416,"LIBRARY_INVALID_RANGE");}
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
