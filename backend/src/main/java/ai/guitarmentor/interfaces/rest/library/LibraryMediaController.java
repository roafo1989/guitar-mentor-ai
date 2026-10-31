package ai.guitarmentor.interfaces.rest.library;

import ai.guitarmentor.infrastructure.librarymedia.LibraryMediaInspector;
import ai.guitarmentor.infrastructure.librarymedia.LibraryMediaInspector.Audio;
import ai.guitarmentor.infrastructure.librarymedia.LibraryMediaInspector.Photo;
import ai.guitarmentor.infrastructure.persistence.LibraryMaterialRepository;
import ai.guitarmentor.infrastructure.persistence.LibraryObjectStore;
import ai.guitarmentor.infrastructure.persistence.LibraryObjectStore.Stored;
import ai.guitarmentor.infrastructure.security.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** FT-012-03: community photo/audio assets on the existing private library object pipeline. */
@RestController
@RequestMapping("/api/v1/library")
public final class LibraryMediaController {
    private static final long MIB=1024L*1024L;
    private static final long REQUEST_LIMIT=20*MIB;
    private static final int MAX_REQUEST_FILES=2;
    private static final int MAX_ASSETS=7;
    private static final int MAX_SCORE_PHOTO=5;
    private static final int MAX_AUDIO=2;
    private static final Set<String> PHOTO_EXT=Set.of("JPEG","PNG");
    private static final Set<String> AUDIO_EXT=Set.of("MP3","WAV");
    private static final ConcurrentHashMap<UUID,Semaphore> ACTOR_GATES=new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String,RateWindow> RATE_WINDOWS=new ConcurrentHashMap<>();
    private static final class RateWindow {
        private long minute=-1; private int count;
        synchronized boolean admit(int maximum) {
            long current=System.currentTimeMillis()/60000L;
            if(current!=minute) { minute=current; count=0; }
            return ++count<=maximum;
        }
    }
    private static final Semaphore GLOBAL_GATE=new Semaphore(2,true);
    private static final ThreadPoolExecutor IMAGE_WORKERS=new ThreadPoolExecutor(2,2,0L,
        TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(2),r->{Thread t=new Thread(r,"library-image-preview");t.setDaemon(true);return t;},
        new ThreadPoolExecutor.AbortPolicy());

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final LibraryMaterialRepository materials;
    private final LibraryObjectStore storage;
    private final boolean libraryEnabled;
    private final boolean mediaEnabled;
    @Value("${guitar-mentor.library.max-account-bytes:209715200}") private long maxAccountBytes=200*MIB;
    @Value("${guitar-mentor.library.max-account-files:100}") private int maxAccountFiles=100;

    public LibraryMediaController(JdbcClient jdbc, TransactionTemplate transactions,
            LibraryMaterialRepository materials, LibraryObjectStore storage,
            @Value("${guitar-mentor.library.enabled:false}") boolean libraryEnabled,
            @Value("${guitar-mentor.library.community-media-enabled:false}") boolean mediaEnabled) {
        this.jdbc=jdbc; this.transactions=transactions; this.materials=materials; this.storage=storage;
        this.libraryEnabled=libraryEnabled; this.mediaEnabled=mediaEnabled;
    }
    @jakarta.annotation.PostConstruct
    void validateFlags() {
        if(mediaEnabled && !libraryEnabled) throw new IllegalStateException("Community media requires library.enabled=true");
        if(mediaEnabled && Runtime.getRuntime().maxMemory()<512L*1024*1024)
            throw new IllegalStateException("Community media requires a JVM heap of at least 512 MiB");
    }

    public record Capabilities(boolean enabled, Map<String,List<String>> allowedExtensions,
            int maxFilesPerRequest,int maxFilesPerMaterial,int maxPhotosAndScores,int maxAudio,
            long maxRequestBytes,long maxPhotoBytes,long maxAudioBytes,int maxPhotoPixels,
            int maxPhotoSide,long maxAudioDurationMillis,int minAudioSampleRate,int maxAudioSampleRate,
            List<Integer> audioChannels,List<String> photoFormats,List<String> audioFormats,
            String wavEncoding,String[] roles) {}
    private record Staged(String name,String format,String role,String mediaType,Stored original,
            Stored preview,long duration,Integer channels,Integer sampleRate,Integer width,Integer height) {}

    private UUID actor(HttpServletRequest request) {
        CurrentActor current=(CurrentActor)request.getAttribute("guitarMentorActor");
        if(current==null) throw new Failure(401,"UNAUTHENTICATED");
        if(!libraryEnabled) throw new Failure(503,"LIBRARY_DISABLED");
        return current.accountId();
    }
    private UUID mediaActor(HttpServletRequest request) {
        UUID owner=actor(request);
        if(!mediaEnabled) throw new Failure(503,"COMMUNITY_MEDIA_DISABLED");
        return owner;
    }

    @GetMapping("/capabilities")
    public ResponseEntity<Capabilities> capabilities(HttpServletRequest request) {
        actor(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Capabilities(mediaEnabled,
            mediaEnabled?Map.of("PHOTO_ORIGINAL",List.of("jpg","jpeg","png"),
                   "ORIGINAL_RECORDING",List.of("mp3","wav"),
                   "SYNTHESIZED_PREVIEW",List.of("mp3","wav")):Map.of(),
            mediaEnabled?MAX_REQUEST_FILES:0,mediaEnabled?MAX_ASSETS:5,MAX_SCORE_PHOTO,mediaEnabled?MAX_AUDIO:0,
            mediaEnabled?REQUEST_LIMIT:0,
            mediaEnabled?LibraryMediaInspector.MAX_PHOTO_BYTES:0,
            mediaEnabled?LibraryMediaInspector.MAX_AUDIO_BYTES:0,
            mediaEnabled?LibraryMediaInspector.MAX_PIXELS:0,mediaEnabled?LibraryMediaInspector.MAX_SIDE:0,
            mediaEnabled?LibraryMediaInspector.MAX_DURATION_MILLIS:0,mediaEnabled?8000:0,mediaEnabled?48000:0,
            mediaEnabled?List.of(1,2):List.of(),mediaEnabled?List.of("JPEG","PNG"):List.of(),mediaEnabled?List.of("MP3","WAV"):List.of(),
            mediaEnabled?"PCM16":null,
            mediaEnabled?new String[]{"PHOTO_ORIGINAL","ORIGINAL_RECORDING","SYNTHESIZED_PREVIEW"}:new String[0]));
    }

    @PostMapping(path="/materials/{materialId}/files",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<LibraryMaterialController.MaterialView> upload(HttpServletRequest request,
            @PathVariable UUID materialId,
            @RequestHeader(value="If-Match",required=false) String ifMatch,
            @RequestHeader(value="Idempotency-Key",required=false) String rawKey,
            @RequestPart("metadata") Map<String,Object> metadata,
            @RequestPart("files") List<MultipartFile> files) {
        UUID owner=mediaActor(request);
        rate(owner);
        long expected=parseRevision(ifMatch);
        UUID key=parseKey(rawKey);
        String role=parseRole(metadata);
        Semaphore actorGate=ACTOR_GATES.computeIfAbsent(owner,id->new Semaphore(1,true));
        if(!actorGate.tryAcquire()) throw new Failure(409,"LIBRARY_UPLOAD_IN_PROGRESS");
        try {
            if(!GLOBAL_GATE.tryAcquire()) throw new Failure(429,"LIBRARY_RATE_LIMITED");
            try { return performUpload(owner,materialId,expected,key,role,files); }
            finally { GLOBAL_GATE.release(); }
        } finally { actorGate.release(); }
    }

    private ResponseEntity<LibraryMaterialController.MaterialView> performUpload(UUID owner,UUID materialId,long expected,
            UUID key,String role,List<MultipartFile> files) {
        if(files==null || files.isEmpty() || files.size()>MAX_REQUEST_FILES)
            throw new Failure(400,"LIBRARY_INVALID_FILES");
        materials.find(owner,materialId).orElseThrow(()->new Failure(404,"LIBRARY_MATERIAL_NOT_FOUND"));
        boolean photo="PHOTO_ORIGINAL".equals(role);
        long predicted=0, reserveBytes=0;
        List<String> extensions=new ArrayList<>();
        for(MultipartFile file:files) {
            String name=safeName(file.getOriginalFilename());
            String ext=extension(name);
            if(!(photo?PHOTO_EXT:AUDIO_EXT).contains(ext)) throw new Failure(415,"LIBRARY_UNSUPPORTED_FORMAT");
            long size=file.getSize();
            long max=photo?LibraryMediaInspector.MAX_PHOTO_BYTES:LibraryMediaInspector.MAX_AUDIO_BYTES;
            if(size<=0 || size>max) throw new Failure(413,photo?"PHOTO_SIZE_EXCEEDED":"AUDIO_SIZE_EXCEEDED");
            predicted+=size;
            if(predicted>REQUEST_LIMIT) throw new Failure(413,"LIBRARY_UPLOAD_TOO_LARGE");
            extensions.add(ext);
        }
        reserveBytes=predicted+(photo?(long)files.size()*LibraryMediaInspector.MAX_PHOTO_BYTES:0);
        int physicalFiles=files.size()+(photo?files.size():0);
        boolean replay=reserve(owner,key,reserveBytes,physicalFiles);
        if(!replay) {
            var fresh=materials.find(owner,materialId).orElseThrow(()->new Failure(404,"LIBRARY_MATERIAL_NOT_FOUND"));
            if(fresh.revision()!=expected) { abort(owner,key,"LIBRARY_REVISION_CONFLICT"); throw new Failure(412,"LIBRARY_REVISION_CONFLICT"); }
        }
        List<Staged> staged=new ArrayList<>();
        List<String> stagedKeys=new ArrayList<>();
        boolean committed=false;
        try {
            for(int i=0;i<files.size();i++) {
                MultipartFile file=files.get(i);
                String name=safeName(file.getOriginalFilename());
                String ext=extensions.get(i);
                byte[] bytes=file.getBytes();
                String declared=file.getContentType();
                Stored original;
                Stored preview=null;
                String mediaType;
                long duration=0; Integer channels=null,rate=null,width=null,height=null;
                if(photo) {
                    Photo info=inspectPhotoBounded(bytes,ext,declared);
                    mediaType=info.mediaType(); width=info.width(); height=info.height();
                    original=storage.put(new ByteArrayInputStream(bytes),LibraryMediaInspector.MAX_PHOTO_BYTES);
                    stagedKeys.add(original.key()); if(!replay) recordObject(owner,key,original.key());
                    preview=storage.put(new ByteArrayInputStream(info.preview()),LibraryMediaInspector.MAX_PHOTO_BYTES);
                    stagedKeys.add(preview.key()); if(!replay) recordObject(owner,key,preview.key());
                } else {
                    Audio info=LibraryMediaInspector.inspectAudio(bytes,ext,declared);
                    mediaType=info.mediaType(); duration=info.durationMillis(); channels=info.channels(); rate=info.sampleRate();
                    original=storage.put(new ByteArrayInputStream(bytes),LibraryMediaInspector.MAX_AUDIO_BYTES);
                    stagedKeys.add(original.key()); if(!replay) recordObject(owner,key,original.key());
                }
                Staged item=new Staged(name,ext,role,mediaType,original,preview,duration,channels,rate,width,height);
                staged.add(item);
            }
            String fingerprint=fingerprint(materialId,expected,role,staged);
            if(!replay) commitIntent(owner,key);
            long actualBytes=staged.stream().mapToLong(s->s.original().length()+(s.preview()==null?0:s.preview().length())).sum();
            record Outcome(boolean replay) {}
            Outcome outcome=transactions.execute(status -> {
                jdbc.sql("SELECT account_id FROM library_account_usage WHERE account_id=:owner FOR UPDATE")
                    .param("owner",owner).query().singleRow();
                var op=jdbc.sql("""
                    SELECT fingerprint,state,material_id,reserved_bytes,reserved_files
                    FROM library_upload_operations WHERE owner_account_id=:owner AND idempotency_key=:key FOR UPDATE
                    """).param("owner",owner).param("key",key).query().singleRow();
                if(replay) {
                    if(!"SUCCEEDED".equals(op.get("state")) || !fingerprint.equals(op.get("fingerprint"))
                            || !materialId.equals(op.get("material_id"))) throw new Failure(409,"IDEMPOTENCY_KEY_REUSED");
                    return new Outcome(true);
                }
                var locked=jdbc.sql("""
                    SELECT revision,state FROM library_materials
                    WHERE id=:id AND owner_account_id=:owner FOR UPDATE
                    """).param("id",materialId).param("owner",owner).query().listOfRows();
                if(locked.isEmpty() || !"READY".equals(locked.getFirst().get("state")))
                    throw new Failure(404,"LIBRARY_MATERIAL_NOT_FOUND");
                if(((Number)locked.getFirst().get("revision")).longValue()!=expected)
                    throw new Failure(412,"LIBRARY_REVISION_CONFLICT");
                if(!"COMMITTING".equals(op.get("state"))) throw new Failure(409,"LIBRARY_UPLOAD_IN_PROGRESS");
                long reserved=((Number)op.get("reserved_bytes")).longValue();
                long reservedFiles=((Number)op.get("reserved_files")).longValue();
                int actualFiles=staged.size()+(photo?staged.size():0);
                if(actualBytes>reserved || actualFiles!=reservedFiles) throw new Failure(413,"LIBRARY_UPLOAD_TOO_LARGE");
                long total=jdbc.sql("SELECT count(*) FROM library_assets WHERE material_id=:id")
                    .param("id",materialId).query(Long.class).single();
                long scorePhoto=jdbc.sql("SELECT count(*) FROM library_assets WHERE material_id=:id AND role IN ('SCORE_ORIGINAL','PHOTO_ORIGINAL')")
                    .param("id",materialId).query(Long.class).single();
                long audio=jdbc.sql("SELECT count(*) FROM library_assets WHERE material_id=:id AND role IN ('ORIGINAL_RECORDING','SYNTHESIZED_PREVIEW')")
                    .param("id",materialId).query(Long.class).single();
                if(total+staged.size()>MAX_ASSETS) throw new Failure(409,"LIBRARY_MATERIAL_ASSET_LIMIT");
                if(photo && scorePhoto+staged.size()>MAX_SCORE_PHOTO) throw new Failure(409,"LIBRARY_MATERIAL_PHOTO_LIMIT");
                if(!photo && audio+staged.size()>MAX_AUDIO) throw new Failure(409,"LIBRARY_MATERIAL_AUDIO_LIMIT");
                if(jdbc.sql("UPDATE library_materials SET revision=revision+1,updated_at=now() WHERE id=:id AND revision=:revision AND state='READY'")
                    .param("id",materialId).param("revision",expected).update()!=1)
                    throw new Failure(412,"LIBRARY_REVISION_CONFLICT");
                for(Staged item:staged) {
                    jdbc.sql("""
                        INSERT INTO library_assets(id,material_id,object_key,original_name,format,byte_size,sha256,
                            role,media_type,duration_millis,channels,sample_rate,width,height,
                            preview_object_key,preview_byte_size,processing_state)
                        VALUES(:id,:material,:key,:name,:format,:size,:sha,:role,:mime,:duration,:channels,:rate,
                            :width,:height,:preview,:previewSize,'READY')
                        """).param("id",UUID.randomUUID()).param("material",materialId)
                        .param("key",item.original().key()).param("name",item.name()).param("format",item.format())
                        .param("size",item.original().length()).param("sha",item.original().sha256())
                        .param("role",item.role()).param("mime",item.mediaType())
                        .param("duration",item.duration()==0?null:item.duration()).param("channels",item.channels())
                        .param("rate",item.sampleRate()).param("width",item.width()).param("height",item.height())
                        .param("preview",item.preview()==null?null:item.preview().key())
                        .param("previewSize",item.preview()==null?null:item.preview().length()).update();
                }
                int usageUpdated=jdbc.sql("""
                    UPDATE library_account_usage SET charged_bytes=charged_bytes+:bytes,
                        charged_files=charged_files+:files,reserved_bytes=reserved_bytes-:reservedBytes,
                        reserved_files=reserved_files-:reservedFiles,revision=revision+1
                    WHERE account_id=:owner AND reserved_bytes>=:reservedBytes AND reserved_files>=:reservedFiles
                    """).param("bytes",actualBytes).param("files",actualFiles).param("reservedBytes",reserved)
                    .param("reservedFiles",reservedFiles).param("owner",owner).update();
                if(usageUpdated!=1) throw new IllegalStateException("Library quota reservation invariant violated");
                int operationUpdated=jdbc.sql("""
                    UPDATE library_upload_operations SET fingerprint=:fingerprint,state='SUCCEEDED',
                        material_id=:material,reserved_bytes=0,reserved_files=0,updated_at=now()
                    WHERE owner_account_id=:owner AND idempotency_key=:key AND state='COMMITTING'
                    """).param("fingerprint",fingerprint).param("material",materialId)
                    .param("owner",owner).param("key",key).update();
                if(operationUpdated!=1) throw new IllegalStateException("Library upload operation invariant violated");
                return new Outcome(false);
            });
            committed=!outcome.replay();
            if(outcome.replay()) cleanupKeys(stagedKeys);
            LibraryMaterialController.MaterialView result=view(owner,materialId);
            return ResponseEntity.status(outcome.replay()?200:201).header("Idempotency-Replayed",Boolean.toString(outcome.replay()))
                .eTag("\""+result.revision()+"\"").cacheControl(CacheControl.noStore()).body(result);
        } catch(LibraryMediaInspector.InvalidMedia e) {
            if(!committed) {cleanupKeys(stagedKeys);if(!replay) abort(owner,key,e.code());}
            int status=e.code().contains("EXCEEDED")||e.code().contains("SIZE")||e.code().contains("TOO_LARGE")?413:
                (e.code().contains("TIMEOUT")||e.code().contains("BUSY")||e.code().contains("INTERRUPTED")?503:400);
            throw new Failure(status,e.code());
        } catch(IOException e) {
            if(!committed) {cleanupKeys(stagedKeys);if(!replay) abort(owner,key,"LIBRARY_STORAGE_UNAVAILABLE");}
            throw new Failure(503,"LIBRARY_STORAGE_UNAVAILABLE");
        } catch(RuntimeException e) {
            if(!committed) {cleanupKeys(stagedKeys);if(!replay) abort(owner,key,e instanceof Failure f?f.code:"LIBRARY_UPLOAD_FAILED");}
            throw e;
        }
    }

    private static Photo inspectPhotoBounded(byte[] bytes,String extension,String mime) throws IOException {
        Future<Photo> task;
        try { task=IMAGE_WORKERS.submit(()->LibraryMediaInspector.inspectPhoto(bytes,extension,mime)); }
        catch(java.util.concurrent.RejectedExecutionException e) { throw new LibraryMediaInspector.InvalidMedia("PHOTO_PROCESSING_BUSY",e); }
        try { return task.get(30,TimeUnit.SECONDS); }
        catch(TimeoutException e) { task.cancel(true); throw new LibraryMediaInspector.InvalidMedia("PHOTO_PROCESSING_TIMEOUT",e); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); task.cancel(true); throw new LibraryMediaInspector.InvalidMedia("PHOTO_PROCESSING_INTERRUPTED",e); }
        catch(ExecutionException e) {
            Throwable cause=e.getCause();
            if(cause instanceof LibraryMediaInspector.InvalidMedia invalid) throw invalid;
            throw new LibraryMediaInspector.InvalidMedia("PHOTO_DECODE_FAILED",cause);
        }
    }

    private boolean reserve(UUID owner,UUID key,long bytes,int files) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            jdbc.sql("INSERT INTO library_account_usage(account_id) VALUES(:owner) ON CONFLICT DO NOTHING").param("owner",owner).update();
            var usage=jdbc.sql("SELECT charged_bytes,charged_files,reserved_bytes,reserved_files FROM library_account_usage WHERE account_id=:owner FOR UPDATE")
                .param("owner",owner).query().singleRow();
            var existing=jdbc.sql("SELECT state,terminal_code FROM library_upload_operations WHERE owner_account_id=:owner AND idempotency_key=:key")
                .param("owner",owner).param("key",key).query().listOfRows();
            if(!existing.isEmpty()) {
                String state=(String)existing.getFirst().get("state");
                if("SUCCEEDED".equals(state)) return true;
                if("FAILED".equals(state)) throw new Failure(409,Objects.toString(existing.getFirst().get("terminal_code"),"LIBRARY_UPLOAD_PREVIOUSLY_FAILED"));
                throw new Failure(409,"LIBRARY_UPLOAD_IN_PROGRESS");
            }
            long used=((Number)usage.get("charged_bytes")).longValue()+((Number)usage.get("reserved_bytes")).longValue();
            long count=((Number)usage.get("charged_files")).longValue()+((Number)usage.get("reserved_files")).longValue();
            if(bytes>maxAccountBytes-used || files>maxAccountFiles-count) throw new Failure(409,"LIBRARY_QUOTA_EXCEEDED");
            jdbc.sql("UPDATE library_account_usage SET reserved_bytes=reserved_bytes+:bytes,reserved_files=reserved_files+:files,revision=revision+1 WHERE account_id=:owner")
                .param("bytes",bytes).param("files",files).param("owner",owner).update();
            jdbc.sql("""
                INSERT INTO library_upload_operations(id,owner_account_id,idempotency_key,state,reserved_bytes,reserved_files,expires_at,lease_owner,last_lease_at)
                VALUES(:id,:owner,:key,'RECEIVING',:bytes,:files,now()+interval '24 hours',:lease,now())
                """).param("id",UUID.randomUUID()).param("owner",owner).param("key",key).param("bytes",bytes)
                .param("files",files).param("lease",UUID.randomUUID()).update();
            return false;
        }));
    }
    private void recordObject(UUID owner,UUID key,String objectKey) {
        int changed=transactions.execute(status -> jdbc.sql("""
            UPDATE library_upload_operations SET manifest=jsonb_set(manifest,'{keys}',
                COALESCE(manifest->'keys','[]'::jsonb)||jsonb_build_array(CAST(:object AS text)),true),
                last_lease_at=now(),updated_at=now()
            WHERE owner_account_id=:owner AND idempotency_key=:key AND state='RECEIVING'
            """).param("object",objectKey).param("owner",owner).param("key",key).update());
        if(changed!=1) throw new Failure(409,"LIBRARY_UPLOAD_INTERRUPTED");
    }
    private void commitIntent(UUID owner,UUID key) {
        int changed=transactions.execute(status -> jdbc.sql("UPDATE library_upload_operations SET state='COMMITTING',last_lease_at=now(),updated_at=now() WHERE owner_account_id=:owner AND idempotency_key=:key AND state='RECEIVING'")
            .param("owner",owner).param("key",key).update());
        if(changed!=1) throw new Failure(409,"LIBRARY_UPLOAD_INTERRUPTED");
    }
    private void abort(UUID owner,UUID key,String code) {
        transactions.executeWithoutResult(status -> {
            jdbc.sql("SELECT account_id FROM library_account_usage WHERE account_id=:owner FOR UPDATE").param("owner",owner).query().singleRow();
            var op=jdbc.sql("SELECT state,reserved_bytes,reserved_files FROM library_upload_operations WHERE owner_account_id=:owner AND idempotency_key=:key FOR UPDATE")
                .param("owner",owner).param("key",key).query().listOfRows();
            if(op.isEmpty() || !Set.of("RECEIVING","COMMITTING").contains(op.getFirst().get("state"))) return;
            var row=op.getFirst();
            jdbc.sql("UPDATE library_account_usage SET reserved_bytes=reserved_bytes-:bytes,reserved_files=reserved_files-:files,revision=revision+1 WHERE account_id=:owner")
                .param("bytes",row.get("reserved_bytes")).param("files",row.get("reserved_files")).param("owner",owner).update();
            int operationUpdated=jdbc.sql("UPDATE library_upload_operations SET state='FAILED',reserved_bytes=0,reserved_files=0,terminal_code=:code,updated_at=now() WHERE owner_account_id=:owner AND idempotency_key=:key AND state IN ('RECEIVING','COMMITTING')")
                .param("code",code).param("owner",owner).param("key",key).update();
            if(operationUpdated!=1) throw new IllegalStateException("Library upload abort invariant violated");
        });
    }
    private void cleanupKeys(List<String> keys) {
        for(String key:keys) cleanupKey(key);
    }
    private void cleanupKey(String key) {
        try {
            Boolean used=jdbc.sql("SELECT EXISTS(SELECT 1 FROM library_assets WHERE object_key=:key OR preview_object_key=:key)")
                .param("key",key).query(Boolean.class).single();
            if(Boolean.FALSE.equals(used)) storage.delete(key);
        } catch(Exception ignored) { /* Orphan recovery owns uncertain keys. */ }
    }
    private static String fingerprint(UUID material,long revision,String role,List<Staged> files) {
        StringBuilder value=new StringBuilder(material.toString()).append('|').append(revision).append('|').append(role);
        for(Staged s:files) value.append('\n').append(s.name().length()).append(':').append(s.name())
            .append(':').append(s.original().length()).append(':').append(s.original().sha256());
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e) {throw new IllegalStateException(e);}
    }

    @DeleteMapping("/materials/{materialId}/files/{assetId}")
    public ResponseEntity<Map<String,String>> delete(HttpServletRequest request,@PathVariable UUID materialId,
            @PathVariable UUID assetId,@RequestHeader(value="If-Match",required=false) String ifMatch) {
        UUID owner=mediaActor(request); rate(owner); long revision=parseRevision(ifMatch);
        String state=transactions.execute(status -> {
            jdbc.sql("INSERT INTO library_account_usage(account_id) VALUES(:owner) ON CONFLICT DO NOTHING").param("owner",owner).update();
            jdbc.sql("SELECT account_id FROM library_account_usage WHERE account_id=:owner FOR UPDATE").param("owner",owner).query().singleRow();
            var material=jdbc.sql("SELECT revision FROM library_materials WHERE id=:id AND owner_account_id=:owner AND state='READY' FOR UPDATE")
                .param("id",materialId).param("owner",owner).query().listOfRows();
            if(material.isEmpty()) throw new Failure(404,"LIBRARY_MATERIAL_NOT_FOUND");
            if(((Number)material.getFirst().get("revision")).longValue()!=revision) throw new Failure(412,"LIBRARY_REVISION_CONFLICT");
            var assets=jdbc.sql("SELECT role,processing_state FROM library_assets WHERE id=:asset AND material_id=:material FOR UPDATE")
                .param("asset",assetId).param("material",materialId).query().listOfRows();
            if(assets.isEmpty()) throw new Failure(404,"LIBRARY_ASSET_NOT_FOUND");
            String assetState=(String)assets.getFirst().get("processing_state");
            if("DELETING".equals(assetState)) return "DELETING";
            if(!Set.of("READY","REJECTED").contains(assetState)) throw new Failure(409,"LIBRARY_ASSET_NOT_READY");
            Long published=jdbc.sql("SELECT count(*) FROM library_publication_assets a JOIN library_publications p ON p.id=a.publication_id WHERE a.asset_id=:asset AND p.state='PUBLISHED'")
                .param("asset",assetId).query(Long.class).single();
            if(published>0) throw new Failure(409,"CATALOG_ASSET_PUBLISHED");
            String role=(String)assets.getFirst().get("role");
            if("SCORE_ORIGINAL".equals(role)||"PHOTO_ORIGINAL".equals(role)) {
                Long readyRemaining=jdbc.sql("SELECT count(*) FROM library_assets WHERE material_id=:material AND role IN ('SCORE_ORIGINAL','PHOTO_ORIGINAL') AND processing_state='READY'")
                    .param("material",materialId).query(Long.class).single();
                if("READY".equals(assetState) && readyRemaining<=1)
                    throw new Failure(409,"CATALOG_LAST_SCORE");
            }
            jdbc.sql("UPDATE library_assets SET processing_state='DELETING' WHERE id=:asset").param("asset",assetId).update();
            int revisionUpdated=jdbc.sql("UPDATE library_materials SET revision=revision+1,updated_at=now() WHERE id=:id")
                .param("id",materialId).update();
            if(revisionUpdated!=1) throw new IllegalStateException("Library material revision invariant violated");
            jdbc.sql("INSERT INTO library_asset_deletion_jobs(asset_id,material_id,state) VALUES(:asset,:material,'PENDING') ON CONFLICT DO NOTHING")
                .param("asset",assetId).param("material",materialId).update();
            return "DELETING";
        });
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(Map.of("assetId",assetId.toString(),"state",state));
    }

    @RequestMapping(path="/materials/{materialId}/files/{assetId}/preview",method={RequestMethod.GET,RequestMethod.HEAD})
    public ResponseEntity<?> preview(HttpServletRequest request,@PathVariable UUID materialId,@PathVariable UUID assetId) {
        UUID owner=mediaActor(request);
        var rows=jdbc.sql("""
            SELECT a.preview_object_key,a.preview_byte_size,a.media_type,a.role,a.processing_state
            FROM library_assets a JOIN library_materials m ON m.id=a.material_id
            WHERE m.owner_account_id=:owner AND m.id=:material AND m.state='READY' AND a.id=:asset
              AND a.role='PHOTO_ORIGINAL' AND a.processing_state='READY' AND a.preview_object_key IS NOT NULL
            """).param("owner",owner).param("material",materialId).param("asset",assetId).query().listOfRows();
        if(rows.isEmpty()) {
            var unavailable=jdbc.sql("""
                SELECT a.id FROM library_assets a JOIN library_materials m ON m.id=a.material_id
                WHERE m.owner_account_id=:owner AND m.id=:material AND a.id=:asset
                  AND a.role='PHOTO_ORIGINAL' AND a.processing_state='REJECTED'
                """).param("owner",owner).param("material",materialId).param("asset",assetId).query().listOfRows();
            if(!unavailable.isEmpty()) throw new Failure(404,"LIBRARY_FILE_UNAVAILABLE");
            throw new Failure(404,"LIBRARY_PREVIEW_NOT_FOUND");
        }
        try {
            var row=rows.getFirst(); var path=storage.file((String)row.get("preview_object_key"));
            HttpHeaders h=new HttpHeaders(); h.setCacheControl(CacheControl.noStore()); h.add("X-Content-Type-Options","nosniff");
            h.setContentType(MediaType.parseMediaType((String)row.get("media_type")));
            h.setContentLength(Files.size(path));
            String suffix=((String)row.get("media_type")).endsWith("png")?"png":"jpg";
            h.setContentDisposition(ContentDisposition.inline().filename("preview."+suffix).build());
            if("HEAD".equals(request.getMethod())) return ResponseEntity.ok().headers(h).build();
            return ResponseEntity.ok().headers(h).body(new FileSystemResource(path));
        } catch(java.nio.file.NoSuchFileException e) { throw new Failure(404,"LIBRARY_FILE_UNAVAILABLE"); }
          catch(IOException e) { throw new Failure(503,"LIBRARY_STORAGE_UNAVAILABLE"); }
    }

    private LibraryMaterialController.MaterialView view(UUID owner,UUID materialId) {
        var material=materials.find(owner,materialId).orElseThrow(()->new Failure(404,"LIBRARY_MATERIAL_NOT_FOUND"));
        List<LibraryMaterialController.FileView> files=materials.assets(owner,materialId).stream().map(a ->
            new LibraryMaterialController.FileView(a.id(),a.originalName(),a.format(),a.byteSize(),a.sha256(),
                "REJECTED".equals(a.processingState())?"MEDIA_UNAVAILABLE":
                    ("PDF".equals(a.format())?"PDF_HEADER_CHECKED":"ORIGINAL_UNVERIFIED"),
                "/api/v1/library/materials/"+materialId+"/files/"+a.id()+"/content",a.role(),a.mediaType(),
                a.durationMillis(),a.channels(),a.sampleRate(),a.width(),a.height(),a.processingState(),
                a.previewAvailable(),"PHOTO_ORIGINAL".equals(a.role())&&!a.previewAvailable()?"MEDIA_UNAVAILABLE":null,
                a.previewAvailable()?"/api/v1/library/materials/"+materialId+"/files/"+a.id()+"/preview":null)
        ).toList();
        return new LibraryMaterialController.MaterialView(material.id(),material.title(),material.type(),
            material.author(),material.state(),"PRIVATE","UNREVIEWED",material.revision(),
            material.createdAt(),material.updatedAt(),files);
    }
    private static void rate(UUID owner) {
        if(!RATE_WINDOWS.computeIfAbsent(owner.toString(),k->new RateWindow()).admit(10))
            throw new Failure(429,"LIBRARY_RATE_LIMITED");
    }
    private static String parseRole(Map<String,Object> metadata) {
        if(metadata==null || metadata.size()!=1 || !(metadata.get("role") instanceof String role)
                || !Set.of("PHOTO_ORIGINAL","ORIGINAL_RECORDING","SYNTHESIZED_PREVIEW").contains(role))
            throw new Failure(400,"LIBRARY_INVALID_ROLE");
        return role;
    }
    private static long parseRevision(String raw) {
        if(raw==null) throw new Failure(428,"LIBRARY_PRECONDITION_REQUIRED");
        if(!raw.matches("\"[1-9][0-9]*\"")) throw new Failure(400,"LIBRARY_INVALID_REVISION");
        try{return Long.parseLong(raw.substring(1,raw.length()-1));}catch(NumberFormatException e){throw new Failure(400,"LIBRARY_INVALID_REVISION");}
    }
    private static UUID parseKey(String raw) {
        if(raw==null) throw new Failure(428,"LIBRARY_IDEMPOTENCY_REQUIRED");
        try {
            UUID key=UUID.fromString(raw);
            if(!key.toString().equalsIgnoreCase(raw)) throw new IllegalArgumentException("Non-canonical UUID");
            return key;
        } catch(IllegalArgumentException e){throw new Failure(400,"LIBRARY_INVALID_IDEMPOTENCY_KEY");}
    }
    private static String safeName(String name) {
        if(name==null || name.isBlank() || name.length()>255 || name.contains("/") || name.contains("\\") || name.chars().anyMatch(c->c<32))
            throw new Failure(400,"LIBRARY_INVALID_FILENAME");
        return name;
    }
    private static String extension(String name) {
        int dot=name.lastIndexOf('.');
        if(dot<0) return "";
        String extension=name.substring(dot+1).toUpperCase(Locale.ROOT);
        return "JPG".equals(extension)?"JPEG":extension;
    }
    static final class Failure extends RuntimeException {
        final int status; final String code;
        Failure(int status,String code){super(code);this.status=status;this.code=code;}
    }
    @ExceptionHandler(Failure.class)
    public ResponseEntity<Map<String,String>> problem(Failure e) {
        var response=ResponseEntity.status(e.status).cacheControl(CacheControl.noStore());
        if(e.status==429 || "LIBRARY_UPLOAD_IN_PROGRESS".equals(e.code)) response.header(HttpHeaders.RETRY_AFTER,"3");
        return response.body(Map.of("code",e.code));
    }
}
