package ai.guitarmentor.interfaces.rest.library;

import ai.guitarmentor.infrastructure.persistence.LibraryMaterialRepository;
import ai.guitarmentor.infrastructure.persistence.LibraryObjectStore;
import ai.guitarmentor.infrastructure.persistence.LibraryObjectStore.Stored;
import ai.guitarmentor.infrastructure.security.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.FileSystemResource;

/** FT-011-02: originals remain private and never run through a converter. */
@RestController
@RequestMapping("/api/v1/library/materials")
public final class LibraryOriginalController {
    private static final long MAX_FILE_BYTES=20L*1024*1024;
    private static final long ACCOUNT_BYTES=200L*1024*1024;
    private static final int ACCOUNT_FILES=100;
    private static final Set<String> TYPES=Set.of("EXERCISE","ETUDE","COMPOSITION","OTHER");
    private static final Set<String> FORMATS=Set.of("PDF","GTP","GP3","GP4","GP5","GPX","GP");
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final LibraryMaterialRepository repository;
    private final LibraryObjectStore storage;
    private final boolean enabled;
    @Value("${guitar-mentor.library.max-upload-bytes:20971520}")
    private long maxUploadBytes=MAX_FILE_BYTES;
    @Value("${guitar-mentor.library.max-account-bytes:209715200}")
    private long maxAccountBytes=ACCOUNT_BYTES;
    @Value("${guitar-mentor.library.max-account-files:100}")
    private int maxAccountFiles=ACCOUNT_FILES;
    @Value("${guitar-mentor.library.max-files-per-material:5}")
    private int maxFilesPerMaterial=5;

    @jakarta.annotation.PostConstruct
    void validateConfiguration() {
        if(maxUploadBytes<1024 || maxUploadBytes>MAX_FILE_BYTES
            || maxAccountBytes<maxUploadBytes || maxAccountFiles<maxFilesPerMaterial
            || maxFilesPerMaterial<1 || maxFilesPerMaterial>5)
            throw new IllegalStateException("Invalid private library quota configuration");
    }

    public LibraryOriginalController(JdbcClient jdbc, TransactionTemplate transactions,
            LibraryMaterialRepository repository, LibraryObjectStore storage,
            @Value("${guitar-mentor.library.enabled:false}") boolean enabled) {
        this.jdbc=jdbc; this.transactions=transactions; this.repository=repository;
        this.storage=storage; this.enabled=enabled;
    }

    public record Metadata(String title,String type,String author) {}
    private record Incoming(String name,String format,Stored object) {}

    private UUID actor(HttpServletRequest request) {
        CurrentActor a=(CurrentActor)request.getAttribute("guitarMentorActor");
        if(a==null) throw new Failure(401,"UNAUTHENTICATED");
        if(!enabled) throw new Failure(503,"LIBRARY_DISABLED");
        return a.accountId();
    }

    /** Acquire quota and the actor-scoped idempotency key before disk I/O. */
    private boolean reserve(UUID owner,UUID key,long bytes,int files) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            jdbc.sql("INSERT INTO library_account_usage(account_id) VALUES(:owner) ON CONFLICT DO NOTHING")
                .param("owner",owner).update();
            Map<String,Object> usage=jdbc.sql("""
                SELECT charged_bytes,charged_files,reserved_bytes,reserved_files
                FROM library_account_usage WHERE account_id=:owner FOR UPDATE
                """).param("owner",owner).query().singleRow();
            List<Map<String,Object>> existing=jdbc.sql("""
                SELECT state,terminal_code FROM library_upload_operations
                WHERE owner_account_id=:owner AND idempotency_key=:key
                """).param("owner",owner).param("key",key).query().listOfRows();
            if(!existing.isEmpty()) {
                String state=(String)existing.getFirst().get("state");
                if("SUCCEEDED".equals(state)) return true;
                if("FAILED".equals(state)) {
                    String terminal=(String)existing.getFirst().get("terminal_code");
                    throw new Failure(409,terminal==null?"LIBRARY_UPLOAD_PREVIOUSLY_FAILED":terminal);
                }
                throw new Failure(409,"LIBRARY_UPLOAD_IN_PROGRESS");
            }
            long used=((Number)usage.get("charged_bytes")).longValue()
                +((Number)usage.get("reserved_bytes")).longValue();
            long count=((Number)usage.get("charged_files")).longValue()
                +((Number)usage.get("reserved_files")).longValue();
            if(bytes>maxAccountBytes-used || files>maxAccountFiles-count)
                throw new Failure(409,"LIBRARY_QUOTA_EXCEEDED");
            jdbc.sql("""
                UPDATE library_account_usage SET reserved_bytes=reserved_bytes+:bytes,
                  reserved_files=reserved_files+:files,revision=revision+1
                WHERE account_id=:owner
                """).param("bytes",bytes).param("files",files).param("owner",owner).update();
            jdbc.sql("""
                INSERT INTO library_upload_operations
                (id,owner_account_id,idempotency_key,state,reserved_bytes,reserved_files,expires_at,
                    lease_owner,last_lease_at)
                VALUES (:id,:owner,:key,'RECEIVING',:bytes,:files,now()+interval '24 hours',:lease,now())
                """).param("id",UUID.randomUUID()).param("owner",owner).param("key",key)
                .param("bytes",bytes).param("files",files).param("lease",UUID.randomUUID()).update();
            return false;
        }));
    }

    /** Durable manifest prevents orphan recovery from racing an active upload. */
    private void recordObject(UUID owner,UUID key,String objectKey) {
        if(transactions.execute(status -> jdbc.sql("""
            UPDATE library_upload_operations
            SET manifest=jsonb_set(manifest,'{keys}',
                COALESCE(manifest->'keys','[]'::jsonb)
                  || jsonb_build_array(CAST(:object AS text)),true),
                last_lease_at=now(),updated_at=now()
            WHERE owner_account_id=:owner AND idempotency_key=:key AND state='RECEIVING'
            """).param("object",objectKey).param("owner",owner).param("key",key).update())!=1)
            throw new Failure(409,"LIBRARY_UPLOAD_INTERRUPTED");
    }

    /** Committed intent is recoverable if the process stops before the READY transaction. */
    private void commitIntent(UUID owner,UUID key) {
        if(transactions.execute(status -> jdbc.sql("""
            UPDATE library_upload_operations SET state='COMMITTING',
                last_lease_at=now(),updated_at=now()
            WHERE owner_account_id=:owner AND idempotency_key=:key AND state='RECEIVING'
            """).param("owner",owner).param("key",key).update())!=1)
            throw new Failure(409,"LIBRARY_UPLOAD_INTERRUPTED");
    }

    private void abort(UUID owner,UUID key,String code) {
        transactions.executeWithoutResult(status -> {
            jdbc.sql("INSERT INTO library_account_usage(account_id) VALUES(:owner) ON CONFLICT DO NOTHING")
                .param("owner",owner).update();
            jdbc.sql("SELECT account_id FROM library_account_usage WHERE account_id=:owner FOR UPDATE")
                .param("owner",owner).query().singleRow();
            List<Map<String,Object>> rows=jdbc.sql("""
                SELECT state,reserved_bytes,reserved_files FROM library_upload_operations
                WHERE owner_account_id=:owner AND idempotency_key=:key FOR UPDATE
                """).param("owner",owner).param("key",key).query().listOfRows();
            if(rows.isEmpty() || !Set.of("RECEIVING","COMMITTING").contains(rows.getFirst().get("state"))) return;
            var row=rows.getFirst();
            jdbc.sql("""
                UPDATE library_account_usage SET reserved_bytes=reserved_bytes-:bytes,
                    reserved_files=reserved_files-:files,revision=revision+1
                WHERE account_id=:owner
                """).param("owner",owner).param("bytes",row.get("reserved_bytes"))
                .param("files",row.get("reserved_files")).update();
            jdbc.sql("""
                UPDATE library_upload_operations SET state='FAILED',reserved_bytes=0,reserved_files=0,
                   terminal_code=:code,updated_at=now() WHERE owner_account_id=:owner AND idempotency_key=:key
                """).param("owner",owner).param("key",key).param("code",code).update();
        });
    }

    private static final java.util.concurrent.ConcurrentHashMap<String,Window> RATE_WINDOWS =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final class Window {
        private long minute=-1;
        private int count=0;
        synchronized boolean admit(int maximum) {
            long now=System.currentTimeMillis()/60000L;
            if(now!=minute) {minute=now;count=0;}
            return ++count<=maximum;
        }
    }
    private static void rate(String key,int maximum) {
        if(!RATE_WINDOWS.computeIfAbsent(key,k->new Window()).admit(maximum))
            throw new Failure(429,"LIBRARY_RATE_LIMITED");
    }
    private static final java.util.concurrent.Semaphore GLOBAL_UPLOADS =
        new java.util.concurrent.Semaphore(4,true);
    private static final java.util.concurrent.ConcurrentHashMap<UUID,java.util.concurrent.Semaphore>
        ACTOR_UPLOADS=new java.util.concurrent.ConcurrentHashMap<>();

    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(HttpServletRequest request,
            @RequestHeader(value="Idempotency-Key",required=false) String idempotency,
            @RequestPart("metadata") Map<String,Object> metadata,
            @RequestPart("files") List<MultipartFile> files) {
        UUID owner=actor(request);
        Metadata parsed=parseMetadata(metadata);
        rate("account:"+owner,10);
        rate("source:"+request.getRemoteAddr(),30);
        var actorGate=ACTOR_UPLOADS.computeIfAbsent(owner,id->new java.util.concurrent.Semaphore(1,true));
        if(!actorGate.tryAcquire()) throw new Failure(409,"LIBRARY_UPLOAD_IN_PROGRESS");
        try {
            if(!GLOBAL_UPLOADS.tryAcquire()) throw new Failure(429,"LIBRARY_RATE_LIMITED");
            try {return performUpload(request,idempotency,parsed,files);}
            finally {GLOBAL_UPLOADS.release();}
        } finally {actorGate.release();}
    }

    /** Direct-call compatibility for service tests; HTTP always uses strict JSON map validation. */
    ResponseEntity<?> upload(HttpServletRequest request,String key,Metadata metadata,
            List<MultipartFile> files) {
        if(metadata==null) throw new Failure(400,"LIBRARY_INVALID_METADATA");
        Map<String,Object> values=new java.util.HashMap<>();
        values.put("title",metadata.title());values.put("type",metadata.type());
        if(metadata.author()!=null) values.put("author",metadata.author());
        return upload(request,key,values,files);
    }

    private static Metadata parseMetadata(Map<String,Object> values) {
        if(values==null || !values.keySet().containsAll(Set.of("title","type"))
                || !Set.of("title","type","author").containsAll(values.keySet()))
            throw new Failure(400,"LIBRARY_INVALID_METADATA");
        if(!(values.get("title") instanceof String title)
                || !(values.get("type") instanceof String type)
                || values.get("author")!=null && !(values.get("author") instanceof String))
            throw new Failure(400,"LIBRARY_INVALID_METADATA");
        return new Metadata(title,type,(String)values.get("author"));
    }

    private ResponseEntity<?> performUpload(HttpServletRequest request,
            String idempotency, Metadata metadata, List<MultipartFile> files) {
        UUID owner=actor(request);
        UUID key;
        try {key=UUID.fromString(idempotency);} catch(Exception e) {
            throw new Failure(400,"LIBRARY_INVALID_IDEMPOTENCY_KEY");
        }
        if(metadata==null || metadata.title()==null || metadata.title().strip().isEmpty()
                || metadata.title().strip().length()>150 || !TYPES.contains(metadata.type())
                || metadata.author()!=null && metadata.author().strip().length()>150)
            throw new Failure(400,"LIBRARY_INVALID_METADATA");
        if(files==null || files.isEmpty() || files.size()>maxFilesPerMaterial)
            throw new Failure(400,"LIBRARY_INVALID_FILES");
        String title=metadata.title().strip();
        String author=metadata.author()==null?null:metadata.author().strip();
        long predicted=0;
        for(MultipartFile file:files) {
            String name=file.getOriginalFilename();
            if(name==null || name.isBlank() || name.length()>255
                    || name.contains("/") || name.contains("\\") || name.chars().anyMatch(c->c<32))
                throw new Failure(400,"LIBRARY_INVALID_FILENAME");
            int dot=name.lastIndexOf('.');
            String format=dot<0?"":name.substring(dot+1).toUpperCase(Locale.ROOT);
            if(!FORMATS.contains(format)) throw new Failure(415,"LIBRARY_UNSUPPORTED_FORMAT");
            if(file.getSize()<=0) throw new Failure(400,"LIBRARY_INVALID_FILES");
            predicted+=file.getSize();
            if(predicted>maxUploadBytes) throw new Failure(413,"LIBRARY_UPLOAD_TOO_LARGE");
            if("PDF".equals(format)) {
                try(var input=file.getInputStream()) {
                    if(input.read()!='%' || input.read()!='P' || input.read()!='D'
                            || input.read()!='F' || input.read()!='-')
                        throw new Failure(400,"LIBRARY_INVALID_PDF");
                } catch(IOException e) {throw new Failure(503,"LIBRARY_STORAGE_UNAVAILABLE");}
            }
        }
        boolean replay=reserve(owner,key,predicted,files.size());
        List<Incoming> staged=new ArrayList<>();
        long total=0;
        boolean committed=false;
        try {
            for(MultipartFile file:files) {
                String name=file.getOriginalFilename();
                if(name==null || name.isBlank() || name.length()>255
                        || name.contains("/") || name.contains("\\") || name.chars().anyMatch(c->c<32))
                    throw new Failure(400,"LIBRARY_INVALID_FILENAME");
                int dot=name.lastIndexOf('.');
                String format=dot<0?"":name.substring(dot+1).toUpperCase(Locale.ROOT);
                if(!FORMATS.contains(format)) throw new Failure(415,"LIBRARY_UNSUPPORTED_FORMAT");
                long remaining=maxUploadBytes-total;
                if(remaining<=0 || file.getSize()>remaining)
                    throw new Failure(413,"LIBRARY_UPLOAD_TOO_LARGE");
                Stored object;
                try(var stream=file.getInputStream()) {
                    object=storage.put(stream,remaining);
                }
                staged.add(new Incoming(name,format,object));
                if(!replay) recordObject(owner,key,object.key());
                if(format.equals("PDF")) {
                    try(var stream=Files.newInputStream(storage.file(object.key()))) {
                        if(stream.read()!='%' || stream.read()!='P' || stream.read()!='D'
                                || stream.read()!='F' || stream.read()!='-')
                            throw new Failure(400,"LIBRARY_INVALID_PDF");
                    }
                }
                total+=object.length();
            }
            String fingerprint=fingerprint(title,metadata.type(),author,staged);
            if(!replay) commitIntent(owner,key);
            long totalBytes=total;
            record Outcome(UUID id,boolean replay) {}
            Outcome outcome=transactions.execute(status -> {
                jdbc.sql("SELECT account_id FROM library_account_usage WHERE account_id=:owner FOR UPDATE")
                    .param("owner",owner).query().singleRow();
                Map<String,Object> operation=jdbc.sql("""
                    SELECT fingerprint,state,material_id,reserved_bytes,reserved_files
                    FROM library_upload_operations WHERE owner_account_id=:owner AND idempotency_key=:key
                    FOR UPDATE
                    """).param("owner",owner).param("key",key).query().singleRow();
                if(replay) {
                    if(!"SUCCEEDED".equals(operation.get("state")))
                        throw new Failure(409,"LIBRARY_UPLOAD_IN_PROGRESS");
                    if(!fingerprint.equals(operation.get("fingerprint")))
                        throw new Failure(409,"IDEMPOTENCY_KEY_REUSED");
                    UUID id=(UUID)operation.get("material_id");
                    if(repository.find(owner,id).isEmpty())
                        throw new Failure(410,"LIBRARY_UPLOAD_RESULT_DELETED");
                    return new Outcome(id,true);
                }
                if(!"COMMITTING".equals(operation.get("state")))
                    throw new Failure(409,"LIBRARY_UPLOAD_IN_PROGRESS");
                long reservedBytes=((Number)operation.get("reserved_bytes")).longValue();
                long reservedFiles=((Number)operation.get("reserved_files")).longValue();
                if(totalBytes>reservedBytes || staged.size()!=reservedFiles)
                    throw new Failure(413,"LIBRARY_UPLOAD_TOO_LARGE");
                UUID id=UUID.randomUUID();
                jdbc.sql("""
                    INSERT INTO library_materials(id,owner_account_id,title,material_type,author,state)
                    VALUES(:id,:owner,:title,:type,:author,'READY')
                    """).param("id",id).param("owner",owner).param("title",title)
                    .param("type",metadata.type()).param("author",author).update();
                for(Incoming input:staged) {
                    Stored obj=input.object();
                    jdbc.sql("""
                        INSERT INTO library_assets(id,material_id,object_key,original_name,format,byte_size,sha256)
                        VALUES(:id,:material,:object,:name,:format,:length,:digest)
                        """).param("id",UUID.randomUUID()).param("material",id)
                        .param("object",obj.key()).param("name",input.name())
                        .param("format",input.format()).param("length",obj.length())
                        .param("digest",obj.sha256()).update();
                }
                jdbc.sql("""
                    UPDATE library_account_usage SET charged_bytes=charged_bytes+:bytes,
                      charged_files=charged_files+:files,reserved_bytes=reserved_bytes-:reservedBytes,
                      reserved_files=reserved_files-:reservedFiles,revision=revision+1
                    WHERE account_id=:owner
                    """).param("bytes",totalBytes).param("files",staged.size())
                    .param("reservedBytes",reservedBytes).param("reservedFiles",reservedFiles)
                    .param("owner",owner).update();
                jdbc.sql("""
                    UPDATE library_upload_operations SET fingerprint=:fingerprint,state='SUCCEEDED',
                      material_id=:material,reserved_bytes=0,reserved_files=0,updated_at=now()
                    WHERE owner_account_id=:owner AND idempotency_key=:key
                    """).param("owner",owner).param("key",key)
                      .param("fingerprint",fingerprint).param("material",id).update();
                return new Outcome(id,false);
            });
            committed=!outcome.replay();
            if(outcome.replay()) cleanup(staged);
            var material=repository.find(owner,outcome.id()).orElseThrow();
            return ResponseEntity.status(outcome.replay()?200:201)
                .header("Idempotency-Replayed",Boolean.toString(outcome.replay()))
                .header(HttpHeaders.LOCATION,"/api/v1/library/materials/"+outcome.id())
                .cacheControl(CacheControl.noStore())
                .body(new LibraryMaterialController.MaterialView(material.id(),material.title(),
                    material.type(),material.author(),material.state(),"PRIVATE","UNREVIEWED",
                    material.revision(),material.createdAt(),material.updatedAt(),
                    repository.assets(owner,material.id()).stream()
                        .map(a->new LibraryMaterialController.FileView(a.id(),a.originalName(),
                            a.format(),a.byteSize(),a.sha256(),
                            a.format().equals("PDF")?"PDF_HEADER_CHECKED":"ORIGINAL_UNVERIFIED",
                            "/api/v1/library/materials/"+material.id()+"/files/"+a.id()+"/content"))
                        .toList()));
        } catch(LibraryObjectStore.FileTooLarge e) {
            if(!committed) {cleanup(staged); if(!replay) abort(owner,key,"LIBRARY_UPLOAD_TOO_LARGE");} throw new Failure(413,"LIBRARY_UPLOAD_TOO_LARGE");
        } catch(LibraryObjectStore.EmptyFile e) {
            if(!committed) {cleanup(staged); if(!replay) abort(owner,key,"LIBRARY_INVALID_FILES");} throw new Failure(400,"LIBRARY_INVALID_FILES");
        } catch(IOException e) {
            if(!committed) {cleanup(staged); if(!replay) abort(owner,key,"LIBRARY_STORAGE_UNAVAILABLE");} throw new Failure(503,"LIBRARY_STORAGE_UNAVAILABLE");
        } catch(RuntimeException e) {
            if(!committed) {cleanup(staged); if(!replay) abort(owner,key,e instanceof Failure failure?failure.code:"LIBRARY_UPLOAD_FAILED");} throw e;
        }
    }

    private void cleanup(List<Incoming> files) {
        for(Incoming input:files) {
            try {
                // DB commit can succeed even when its acknowledgement is lost.
                // Never compensate a physical key until a live DB read proves it unreferenced.
                Boolean referenced=jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM library_assets WHERE object_key=:key)
                    """).param("key",input.object().key()).query(Boolean.class).single();
                if(Boolean.FALSE.equals(referenced)) storage.delete(input.object().key());
            } catch(Exception ignored) {
                // Fail closed: deferred orphan reconciler retries after DB/storage recovery.
            }
        }
    }
    private static String fingerprint(String title,String type,String author,List<Incoming> files) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            StringBuilder data=new StringBuilder(title).append('\n').append(type)
                .append('\n').append(author==null?"":author);
            for(Incoming f:files) data.append('\n').append(f.name().length()).append(':')
                .append(f.name()).append(':').append(f.object().length())
                .append(':').append(f.object().sha256());
            return HexFormat.of().formatHex(digest.digest(data.toString().getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }

    @RequestMapping(path="/{materialId}/files/{assetId}/content",method={RequestMethod.GET,RequestMethod.HEAD})
    public ResponseEntity<?> content(HttpServletRequest request,
            @PathVariable UUID materialId,@PathVariable UUID assetId) {
        UUID owner=actor(request);
        List<Map<String,Object>> rows=jdbc.sql("""
            SELECT a.object_key,a.original_name,a.format,a.byte_size
            FROM library_assets a JOIN library_materials m ON m.id=a.material_id
            WHERE m.owner_account_id=:owner AND m.id=:material AND m.state='READY' AND a.id=:asset
            """).param("owner",owner).param("material",materialId)
            .param("asset",assetId).query().listOfRows();
        if(rows.isEmpty()) throw new Failure(404,"LIBRARY_MATERIAL_NOT_FOUND");
        Map<String,Object> asset=rows.get(0);
        try {
            var path=storage.file((String)asset.get("object_key"));
            var headers=new HttpHeaders();
            headers.setCacheControl(CacheControl.noStore());
            headers.add("X-Content-Type-Options","nosniff");
            headers.setContentDisposition(org.springframework.http.ContentDisposition.attachment()
                .filename((String)asset.get("original_name"),StandardCharsets.UTF_8).build());
            headers.setContentType("PDF".equals(asset.get("format"))?MediaType.APPLICATION_PDF:MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentLength(Files.size(path));
            if("HEAD".equals(request.getMethod())) return ResponseEntity.ok().headers(headers).build();
            return ResponseEntity.ok().headers(headers).body(new FileSystemResource(path));
        } catch(IOException e) {throw new Failure(503,"LIBRARY_STORAGE_UNAVAILABLE");}
    }
    static final class Failure extends RuntimeException {
        final int status; final String code;
        Failure(int status,String code) {super(code);this.status=status;this.code=code;}
    }
    @ExceptionHandler(Failure.class)
    public ResponseEntity<?> problem(Failure e) {
        var response=ResponseEntity.status(e.status).cacheControl(CacheControl.noStore());
        if(e.status==429 || "LIBRARY_UPLOAD_IN_PROGRESS".equals(e.code))
            response.header(HttpHeaders.RETRY_AFTER,"3");
        return response.body(Map.of("code",e.code));
    }
}
