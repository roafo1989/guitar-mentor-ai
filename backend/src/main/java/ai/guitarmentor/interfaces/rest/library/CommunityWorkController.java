package ai.guitarmentor.interfaces.rest.library;

import ai.guitarmentor.infrastructure.security.CurrentActor;
import ai.guitarmentor.infrastructure.persistence.LibraryObjectStore;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Shared identity is independent of private imported metadata and file ownership. */
@RestController
public class CommunityWorkController {
    private final JdbcClient jdbc;
    private final LibraryObjectStore storage;
    private final LibraryPublicationController publications;
    private final boolean enabled;
    public CommunityWorkController(JdbcClient jdbc, LibraryObjectStore storage,
            LibraryPublicationController publications,
            @Value("${guitar-mentor.library.enabled:false}") boolean enabled) {
        this.jdbc=jdbc; this.storage=storage; this.publications=publications; this.enabled=enabled;
    }
    public record Work(UUID id,String title,String artist,String status,long revision) {}
    public record Identity(String title,String artist) {}
    public record Reason(String reason) {}
    public record LinkRequest(UUID workId) {}
    public record Link(UUID materialId,UUID workId,long revision) {}
    public record WorkDetail(Work work,List<LibraryPublicationController.Publication> publications) {}
    public record Page(List<Work> items,String nextCursor) {}
    public record SplitItem(UUID materialId,long revision) {}
    public record SplitPlan(UUID planId,List<SplitItem> materials) {}
    public record SplitRequest(String title,String artist,String reason,List<SplitItem> materials) {}
    private CurrentActor actor(HttpServletRequest req,boolean admin) {
        if(!(req.getAttribute("guitarMentorActor") instanceof CurrentActor a)) throw new Failure(401,"UNAUTHENTICATED");
        if(!enabled) throw new Failure(503,"COMMUNITY_CATALOG_DISABLED");
        if(admin && !"OWNER".equals(a.role())) throw new Failure(403,"FORBIDDEN");
        return a;
    }
    private static String text(String raw,boolean required) {
        String s=raw==null?"":raw.strip();
        if((required && s.isEmpty()) || s.length()>150) throw new Failure(400,"INVALID_REQUEST");
        return s;
    }
    private static String reason(Reason r) {
        if(r==null || r.reason()==null || r.reason().isBlank() || r.reason().length()>500) throw new Failure(400,"INVALID_REQUEST");
        return r.reason().strip();
    }
    private static long revision(String raw) {
        if(raw==null) throw new Failure(428,"PRECONDITION_REQUIRED");
        try {if(!raw.matches("\"[1-9][0-9]*\"")) throw new IllegalArgumentException();
            return Long.parseLong(raw.substring(1,raw.length()-1));
        } catch(IllegalArgumentException e) {throw new Failure(400,"INVALID_REQUEST");}
    }
    private static UUID key(String raw) {
        if(raw==null) throw new Failure(428,"PRECONDITION_REQUIRED");
        try {return UUID.fromString(raw);} catch(IllegalArgumentException e) {throw new Failure(400,"INVALID_REQUEST");}
    }
    private Work work(UUID id,boolean lock) {
        return jdbc.sql("SELECT id,title,artist,identity_status,revision FROM community_works WHERE id=:id"+(lock?" FOR UPDATE":""))
            .param("id",id).query((rs,n)->new Work(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5)))
            .optional().orElseThrow(()->new Failure(404,"RESOURCE_NOT_FOUND"));
    }
    private void check(long actual,long expected) {if(actual!=expected) throw new Failure(412,"REVISION_CONFLICT");}
    private void event(UUID work,UUID actor,String action,UUID old,UUID next,String reason,UUID request) {
        event(work,actor,action,old,next,reason,request,null,null);
    }
    private void event(UUID work,UUID actor,String action,UUID old,UUID next,String reason,UUID request,String previousStatus,String newStatus) {
        jdbc.sql("INSERT INTO community_work_events(id,work_id,actor_account_id,action,previous_work_id,new_work_id,reason_code,request_id,previous_status,new_status) VALUES(:id,:work,:actor,:action,:old,:next,:reason,:request,:ps,:ns)")
            .param("id",UUID.randomUUID()).param("work",work).param("actor",actor).param("action",action)
            .param("old",old).param("next",next).param("reason",reason).param("request",request).param("ps",previousStatus).param("ns",newStatus).update();
    }
    // Per-actor/key transaction advisory lock serializes concurrent first requests without
    // taking an account FK lock in the inverse order of publish/delete material locks.
    private Map<String,Object> replay(UUID actor,UUID key,String signature) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:scope,204))").param("scope",actor+":"+key).query().singleRow();
        var rows=jdbc.sql("SELECT * FROM community_work_operations WHERE actor_account_id=:a AND idempotency_key=:k")
            .param("a",actor).param("k",key).query().listOfRows();
        if(rows.isEmpty()) return null;
        var row=rows.getFirst();
        if(!signature.equals(row.get("fingerprint"))) throw new Failure(409,"IDEMPOTENCY_KEY_REUSED");
        return row;
    }
    private void operation(UUID actor,UUID key,String signature,UUID id,long revision) {
        jdbc.sql("INSERT INTO community_work_operations VALUES(:a,:k,:s,:id,:r)")
            .param("a",actor).param("k",key).param("s",signature).param("id",id).param("r",revision).update();
    }
    private Work create(UUID actor,String title,String artist,String status) {
        UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO community_works(id,title,artist,normalized_title,normalized_artist,identity_status,created_by_account_id) VALUES(:id,:t,:a,:nt,:na,:s,:owner)")
            .param("id",id).param("t",title).param("a",artist).param("nt",title.toLowerCase(Locale.ROOT))
            .param("na",artist.toLowerCase(Locale.ROOT)).param("s",status).param("owner",actor).update();
        return work(id,false);
    }
    private <T> ResponseEntity<T> ok(T body,long revision) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag("\""+revision+"\"").body(body);
    }
    @PostMapping("/api/v1/community/work-candidates") @Transactional
    public ResponseEntity<Work> candidate(HttpServletRequest req,@RequestHeader(value="Idempotency-Key",required=false) String raw,@RequestBody Identity payload) {
        UUID a=actor(req,false).accountId(),k=key(raw);
        String t=text(payload.title(),true), ar=text(payload.artist(),false),sig="candidate|"+t.length()+":"+t+ar;
        var previous=replay(a,k,sig);
        if(previous!=null) {var w=work((UUID)previous.get("result_id"),false); return ok(w,w.revision());}
        Work w=create(a,t,ar,"CANDIDATE"); event(w.id(),a,"CREATE",null,w.id(),"",k,null,"CANDIDATE"); operation(a,k,sig,w.id(),1);
        return ok(w,1);
    }
    @GetMapping("/api/v1/community/work-candidates/{id}")
    public ResponseEntity<Work> candidateDetail(HttpServletRequest req,@PathVariable UUID id) {
        var a=actor(req,false);
        var allowed=jdbc.sql("SELECT id FROM community_works WHERE id=:id AND (created_by_account_id=:a OR :admin)")
            .param("id",id).param("a",a.accountId()).param("admin","OWNER".equals(a.role())).query(UUID.class).list();
        if(allowed.isEmpty()) throw new Failure(404,"RESOURCE_NOT_FOUND");
        Work w=work(id,false); return ok(w,w.revision());
    }
    @PostMapping("/api/v1/admin/community/works/{id}/verify") @Transactional
    public ResponseEntity<Work> verify(HttpServletRequest req,@PathVariable UUID id,
            @RequestHeader(value="If-Match",required=false) String match,@RequestBody Reason payload) {
        UUID a=actor(req,true).accountId(); long rev=revision(match);String reason=reason(payload);
        Work w=work(id,true); check(w.revision(),rev);
        if(!w.status().equals("CANDIDATE")) throw new Failure(409,"WORK_STATE_CONFLICT");
        jdbc.sql("UPDATE community_works SET identity_status='VERIFIED',revision=revision+1,updated_at=now() WHERE id=:id").param("id",id).update();
        event(id,a,"VERIFY",id,id,reason,UUID.randomUUID(),w.status(),"VERIFIED"); return ok(work(id,false),rev+1);
    }
    public record Metadata(String title,String artist,String reason) {}
    @PatchMapping("/api/v1/admin/community/works/{id}") @Transactional
    public ResponseEntity<Work> metadata(HttpServletRequest req,@PathVariable UUID id,
            @RequestHeader(value="If-Match",required=false) String match,@RequestBody Metadata p) {
        UUID a=actor(req,true).accountId();long rev=revision(match);String t=text(p.title(),true),ar=text(p.artist(),false),r=reason(new Reason(p.reason()));
        Work w=work(id,true);check(w.revision(),rev);
        jdbc.sql("UPDATE community_works SET title=:t,artist=:a,normalized_title=:nt,normalized_artist=:na,revision=revision+1,updated_at=now() WHERE id=:id")
            .param("t",t).param("a",ar).param("nt",t.toLowerCase(Locale.ROOT)).param("na",ar.toLowerCase(Locale.ROOT)).param("id",id).update();
        event(id,a,"METADATA",id,id,r,UUID.randomUUID());return ok(work(id,false),rev+1);
    }
    @PostMapping("/api/v1/admin/community/works/{id}/retire") @Transactional
    public ResponseEntity<Work> retire(HttpServletRequest req,@PathVariable UUID id,
            @RequestHeader(value="If-Match",required=false) String match,@RequestBody Reason p) {
        UUID a=actor(req,true).accountId();long rev=revision(match);String r=reason(p);
        Work w=work(id,true);check(w.revision(),rev);
        if(w.status().equals("RETIRED")) throw new Failure(409,"WORK_STATE_CONFLICT");
        jdbc.sql("UPDATE community_works SET identity_status='RETIRED',revision=revision+1,updated_at=now() WHERE id=:id").param("id",id).update();
        event(id,a,"RETIRE",id,id,r,UUID.randomUUID(),w.status(),"RETIRED");return ok(work(id,false),rev+1);
    }
    private long lockMaterial(UUID a,UUID id) {
        return jdbc.sql("SELECT revision FROM library_materials WHERE id=:id AND owner_account_id=:a AND state='READY' FOR UPDATE")
            .param("id",id).param("a",a).query(Long.class).optional().orElseThrow(()->new Failure(404,"RESOURCE_NOT_FOUND"));
    }
    @GetMapping("/api/v1/library/materials/{id}/work-link")
    public ResponseEntity<Link> link(HttpServletRequest req,@PathVariable UUID id) {
        UUID a=actor(req,false).accountId();
        long rev=jdbc.sql("SELECT revision FROM library_materials WHERE id=:id AND owner_account_id=:a AND state='READY'")
            .param("id",id).param("a",a).query(Long.class).optional().orElseThrow(()->new Failure(404,"RESOURCE_NOT_FOUND"));
        UUID w=jdbc.sql("SELECT work_id FROM library_material_work_links WHERE material_id=:id").param("id",id).query(UUID.class).optional().orElse(null);
        return ok(new Link(id,w,rev),rev);
    }
    @PutMapping("/api/v1/library/materials/{id}/work-link") @Transactional
    public ResponseEntity<Link> attach(HttpServletRequest req,@PathVariable UUID id,
            @RequestHeader(value="If-Match",required=false) String match,
            @RequestHeader(value="Idempotency-Key",required=false) String raw,@RequestBody LinkRequest payload) {
        if(payload.workId()==null) throw new Failure(400,"INVALID_REQUEST");
        return change(req,id,match,raw,payload.workId());
    }
    @DeleteMapping("/api/v1/library/materials/{id}/work-link") @Transactional
    public ResponseEntity<Link> detach(HttpServletRequest req,@PathVariable UUID id,
            @RequestHeader(value="If-Match",required=false) String match,
            @RequestHeader(value="Idempotency-Key",required=false) String raw) {return change(req,id,match,raw,null);}
    private ResponseEntity<Link> change(HttpServletRequest req,UUID id,String match,String raw,UUID target) {
        UUID a=actor(req,false).accountId(),k=key(raw);long rev=revision(match);
        String sig="link|"+id+"|"+rev+"|"+target; var previous=replay(a,k,sig);
        if(previous!=null) return ok(new Link(id,target,((Number)previous.get("result_revision")).longValue()),((Number)previous.get("result_revision")).longValue());
        check(lockMaterial(a,id),rev);
        UUID old=jdbc.sql("SELECT work_id FROM library_material_work_links WHERE material_id=:id").param("id",id).query(UUID.class).optional().orElse(null);
        if(target!=null) {
            // FOR SHARE prevents retire/verify races, while allowing independent authors to attach.
            String status=jdbc.sql("SELECT identity_status FROM community_works WHERE id=:id FOR SHARE").param("id",target).query(String.class).optional()
                .orElseThrow(()->new Failure(404,"RESOURCE_NOT_FOUND"));
            if(!status.equals("VERIFIED")) throw new Failure(409,"WORK_NOT_VERIFIED");
            if(old!=null && !old.equals(target)) throw new Failure(409,"WORK_LINK_CONFLICT");
        }
        long next=rev;
        if(!Objects.equals(old,target)) {
            jdbc.sql("DELETE FROM library_material_work_links WHERE material_id=:id").param("id",id).update();
            if(target!=null) jdbc.sql("INSERT INTO library_material_work_links(material_id,owner_account_id,work_id,created_by_account_id) VALUES(:id,:a,:w,:a)")
                .param("id",id).param("a",a).param("w",target).update();
            next++;
            jdbc.sql("UPDATE library_materials SET revision=revision+1,updated_at=now() WHERE id=:id").param("id",id).update();
            event(target==null?old:target,a,target==null?"DETACH":"ATTACH",old,target,"",k);
        }
        operation(a,k,sig,id,next);return ok(new Link(id,target,next),next);
    }
    // A single predicate for search/detail: every snapshotted asset must still be READY and present.
    private boolean available(UUID publication) {
        var assets=jdbc.sql("SELECT a.object_key,a.preview_object_key,a.role,s.processing_state,s.object_key AS current_key,s.preview_object_key AS current_preview FROM library_publication_assets a LEFT JOIN library_assets s ON s.id=a.asset_id AND s.material_id=(SELECT material_id FROM library_publications WHERE id=:p) WHERE a.publication_id=:p")
            .param("p",publication).query().listOfRows();
        if(assets.isEmpty()) return false;
        try {for(var a:assets) {
            if(!"READY".equals(a.get("processing_state")) || !Objects.equals(a.get("object_key"),a.get("current_key"))) return false;
            storage.file((String)a.get("object_key"));
            if("PHOTO_ORIGINAL".equals(a.get("role"))) {
                if(a.get("preview_object_key")==null || !Objects.equals(a.get("preview_object_key"),a.get("current_preview"))) return false;
                storage.file((String)a.get("preview_object_key"));
            }
        }return true;}catch(java.io.IOException e){return false;}
    }
    private List<UUID> visible(UUID work) {
        return jdbc.sql("SELECT p.id FROM library_publications p JOIN library_materials m ON m.id=p.material_id AND m.state='READY' WHERE p.work_id=:w AND p.state='PUBLISHED' ORDER BY p.created_at,p.id")
            .param("w",work).query(UUID.class).list().stream().filter(this::available).toList();
    }
    @GetMapping("/api/v1/community/works")
    public ResponseEntity<Page> search(HttpServletRequest req,@RequestParam(defaultValue="") String q,
            @RequestParam(defaultValue="20") int limit,@RequestParam(required=false) UUID cursor) {
        actor(req,false);String query=text(q,false).toLowerCase(Locale.ROOT);
        if(limit<1 || limit>50) throw new Failure(400,"INVALID_REQUEST");
        String needle="%"+query.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        List<Work> found=new ArrayList<>();UUID after=cursor;
        while(found.size()<=limit) {
            var ids=jdbc.sql("SELECT id FROM community_works WHERE identity_status='VERIFIED' AND (normalized_title LIKE :q ESCAPE '!' OR normalized_artist LIKE :q ESCAPE '!') AND (CAST(:c AS uuid) IS NULL OR id>CAST(:c AS uuid)) ORDER BY id LIMIT 100")
                .param("q",needle).param("c",after).query(UUID.class).list();
            for(UUID id:ids) {if(!visible(id).isEmpty()) found.add(work(id,false));if(found.size()>limit) break;}
            if(ids.size()<100 || found.size()>limit) break;
            after=ids.getLast();
        }
        boolean more=found.size()>limit;if(more) found.removeLast();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Page(found,more?found.getLast().id().toString():null));
    }
    @GetMapping("/api/v1/community/works/{id}")
    public ResponseEntity<WorkDetail> detail(HttpServletRequest req,@PathVariable UUID id) {
        actor(req,false);Work w=work(id,false);var ids=visible(id);
        if(!w.status().equals("VERIFIED") || ids.isEmpty()) throw new Failure(404,"RESOURCE_NOT_FOUND");
        return ok(new WorkDetail(w,ids.stream().map(p->publications.publishedDetail(req,p).getBody()).toList()),w.revision());
    }
    private static String splitSignature(SplitRequest p) {
        if(p==null || p.materials()==null || p.materials().stream().anyMatch(i->i==null || i.materialId()==null || i.revision()<1)) throw new Failure(400,"INVALID_REQUEST");
        // Length-prefixed fields avoid delimiter ambiguity; sorted IDs make plans order independent.
        String t=text(p.title(),true),a=text(p.artist(),false),r=reason(new Reason(p.reason()));
        return t.length()+":"+t+a.length()+":"+a+r.length()+":"+r+p.materials().stream().sorted(Comparator.comparing(SplitItem::materialId)).toList();
    }
    // Split never reads foreign private material metadata. Only explicitly published material IDs
    // are eligible; their authors must republish to change the public immutable snapshot.
    private List<SplitItem> splitPlan(UUID source,SplitRequest p) {
        splitSignature(p);
        if(p.materials()==null || p.materials().isEmpty() || p.materials().size()>50) throw new Failure(400,"INVALID_REQUEST");
        var items=p.materials().stream().sorted(Comparator.comparing(SplitItem::materialId)).toList();
        if(items.stream().map(SplitItem::materialId).distinct().count()!=items.size()) throw new Failure(400,"INVALID_REQUEST");
        for(var item:items) {
            var rows=jdbc.sql("SELECT m.revision FROM library_materials m JOIN library_material_work_links l ON l.material_id=m.id WHERE m.id=:id AND m.state='READY' AND l.work_id=:w AND EXISTS(SELECT 1 FROM library_publications p WHERE p.material_id=m.id AND p.state='PUBLISHED' AND p.work_id=:w)")
                .param("id",item.materialId()).param("w",source).query(Long.class).list();
            if(rows.isEmpty()) throw new Failure(404,"RESOURCE_NOT_FOUND"); check(rows.getFirst(),item.revision());
        }return items;
    }
    @PostMapping("/api/v1/admin/community/works/{id}/split/dry-run") @Transactional
    public ResponseEntity<SplitPlan> dryRun(HttpServletRequest req,@PathVariable UUID id,
            @RequestHeader(value="If-Match",required=false) String match,@RequestBody SplitRequest p) {
        UUID a=actor(req,true).accountId();Work w=work(id,false);check(w.revision(),revision(match));
        if(!w.status().equals("VERIFIED")) throw new Failure(409,"WORK_NOT_VERIFIED");
        var items=splitPlan(id,p);UUID plan=UUID.randomUUID();
        jdbc.sql("INSERT INTO community_work_split_plans(id,actor_account_id,work_id,work_revision,fingerprint) VALUES(:id,:a,:w,:r,:f)")
            .param("id",plan).param("a",a).param("w",id).param("r",w.revision()).param("f",splitSignature(p)).update();
        return ok(new SplitPlan(plan,items),w.revision());
    }
    @PostMapping("/api/v1/admin/community/works/{id}/split") @Transactional
    public ResponseEntity<Work> split(HttpServletRequest req,@PathVariable UUID id,
            @RequestHeader(value="If-Match",required=false) String match,
            @RequestHeader(value="Split-Plan",required=false) UUID planId,@RequestBody SplitRequest p) {
        UUID a=actor(req,true).accountId();long rev=revision(match);
        if(planId==null) throw new Failure(428,"PRECONDITION_REQUIRED");
        var plans=jdbc.sql("SELECT * FROM community_work_split_plans WHERE id=:id AND actor_account_id=:a AND work_id=:w AND expires_at>now() FOR UPDATE")
            .param("id",planId).param("a",a).param("w",id).query().listOfRows();
        if(plans.isEmpty()) throw new Failure(409,"SPLIT_PLAN_INVALID");
        var saved=plans.getFirst();
        if(!splitSignature(p).equals(saved.get("fingerprint")) || ((Number)saved.get("work_revision")).longValue()!=rev) throw new Failure(409,"SPLIT_PLAN_INVALID");
        if(saved.get("result_id")!=null) {Work result=work((UUID)saved.get("result_id"),false);return ok(result,result.revision());}
        // All material mutations acquire material before Work; sorted material locks prevent deadlocks.
        var plan=splitPlan(id,p);
        for(var item:plan) {
            long current=jdbc.sql("SELECT revision FROM library_materials WHERE id=:id FOR UPDATE").param("id",item.materialId()).query(Long.class).single();
            check(current,item.revision());
        }
        Work w=work(id,true);check(w.revision(),rev);splitPlan(id,p);
        if(!w.status().equals("VERIFIED")) throw new Failure(409,"WORK_NOT_VERIFIED");
        Work target=create(a,text(p.title(),true),text(p.artist(),false),"VERIFIED");UUID request=UUID.randomUUID();
        for(var item:plan) {
            jdbc.sql("UPDATE library_material_work_links SET work_id=:target,revision=revision+1 WHERE material_id=:m AND work_id=:source")
                .param("target",target.id()).param("m",item.materialId()).param("source",id).update();
            jdbc.sql("UPDATE library_materials SET revision=revision+1,updated_at=now() WHERE id=:id").param("id",item.materialId()).update();
            event(target.id(),a,"SPLIT_LINK",id,target.id(),p.reason(),request);
        }
        jdbc.sql("UPDATE community_works SET revision=revision+1,updated_at=now() WHERE id=:id").param("id",id).update();
        event(id,a,"SPLIT",id,target.id(),p.reason(),request);
        jdbc.sql("UPDATE community_work_split_plans SET result_id=:result WHERE id=:id").param("result",target.id()).param("id",planId).update();
        return ok(target,1);
    }
    static final class Failure extends RuntimeException {
        final int status;Failure(int status,String code){super(code);this.status=status;}
    }
    @ExceptionHandler(Failure.class)
    ResponseEntity<Map<String,String>> error(Failure e) {return ResponseEntity.status(e.status).cacheControl(CacheControl.noStore()).body(Map.of("code",e.getMessage()));}
}
