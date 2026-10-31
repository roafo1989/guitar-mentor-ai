package ai.guitarmentor.interfaces.rest.library;

import ai.guitarmentor.infrastructure.security.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

/**
 * FT-012-02: admitted-only search, never a private-corpus or owner-scoped API.
 * Stable descending keyset with HMAC cursor bound to exact normalized filters.
 */
@RestController
@RequestMapping("/api/v1/community/publications")
public class CommunityPublicationSearchController {
    private static final Set<String> TYPES=Set.of("COMPOSITION","ETUDE","EXERCISE","OTHER");
    private static final String SQL="""
        SELECT p.id,p.title,p.author,p.material_type,p.version,p.created_at,
          COALESCE((SELECT array_agg(t.tag ORDER BY t.tag)
            FROM library_publication_tags t WHERE t.publication_id=p.id), ARRAY[]::text[]) AS tags,
          (:media_enabled AND EXISTS(SELECT 1 FROM library_publication_assets a
            JOIN library_assets source ON source.id=a.asset_id
              AND source.material_id=p.material_id AND source.object_key=a.object_key AND source.role=a.role AND source.processing_state='READY'
            WHERE a.publication_id=p.id AND a.role IN ('ORIGINAL_RECORDING','SYNTHESIZED_PREVIEW'))) AS has_audio
        FROM library_publications p
        JOIN library_materials m ON m.id=p.material_id AND m.state='READY'
        WHERE p.state='PUBLISHED'
          AND (:q = '' OR lower(p.title) LIKE :needle ESCAPE '!'
                        OR lower(COALESCE(p.author,'')) LIKE :needle ESCAPE '!')
          AND (:type = '' OR p.material_type=:type)
          AND (:tag_count=0 OR EXISTS(
             SELECT 1 FROM library_publication_tags t
             WHERE t.publication_id=p.id AND t.tag IN (:tags)))
          AND (CAST(:audio AS boolean) IS NULL OR ((:media_enabled AND EXISTS(
             SELECT 1 FROM library_publication_assets a
             JOIN library_assets source ON source.id=a.asset_id
               AND source.material_id=p.material_id AND source.object_key=a.object_key AND source.role=a.role AND source.processing_state='READY'
             WHERE a.publication_id=p.id AND a.role IN ('ORIGINAL_RECORDING','SYNTHESIZED_PREVIEW'))) = CAST(:audio AS boolean)))
          AND (CAST(:before AS timestamptz) IS NULL OR (p.created_at,p.id)<(CAST(:before AS timestamptz),CAST(:before_id AS uuid)))
        ORDER BY p.created_at DESC,p.id DESC LIMIT :max
        """;
    private final JdbcClient jdbc;
    private final byte[] secret;
    private final boolean enabled;
    private final boolean mediaEnabled;
    public CommunityPublicationSearchController(JdbcClient jdbc,
            @Value("${guitar-mentor.library.cursor-secret:}") String secret,
            @Value("${guitar-mentor.library.enabled:false}") boolean enabled,
            @Value("${guitar-mentor.library.community-media-enabled:false}") boolean mediaEnabled) {
        this.jdbc=jdbc;this.secret=secret.getBytes(StandardCharsets.UTF_8);
        this.enabled=enabled;this.mediaEnabled=mediaEnabled;
        if(enabled && this.secret.length<32)
            throw new IllegalStateException("Library search cursor secret requires >=32 UTF-8 bytes");
    }
    public record Summary(UUID id,String title,String author,String type,long version,
                          OffsetDateTime publishedAt,List<String> tags,boolean hasAudio,String detailUrl){}
    public record Page(List<Summary> items,String nextCursor){}
    private void authorize(HttpServletRequest request) {
        if(request.getAttribute("guitarMentorActor") instanceof CurrentActor) {
            if(!enabled) throw new SearchFailure(503,"LIBRARY_DISABLED");
            return;
        }
        throw new SearchFailure(401,"UNAUTHENTICATED");
    }
    private static String q(String raw) {
        if(raw==null) return "";
        String v=raw.strip().toLowerCase(Locale.ROOT);
        if(v.length()>100) throw new SearchFailure(400,"PUBLICATION_SEARCH_INVALID_FILTER");
        return v;
    }
    private static List<String> tags(List<String> raw) {
        if(raw==null) return List.of();
        if(raw.size()>10) throw new SearchFailure(400,"PUBLICATION_SEARCH_INVALID_FILTER");
        java.util.TreeSet<String> result=new java.util.TreeSet<>();
        for(String tag:raw) {
            if(tag==null) throw new SearchFailure(400,"PUBLICATION_SEARCH_INVALID_FILTER");
            String value=tag.strip().toLowerCase(Locale.ROOT);
            if(!value.matches("[\\p{L}\\p{N}][\\p{L}\\p{N} _-]{0,39}"))
                throw new SearchFailure(400,"PUBLICATION_SEARCH_INVALID_FILTER");
            result.add(value);
        }
        return List.copyOf(result);
    }
    private static OffsetDateTime timestamp(Object raw) {
        if(raw instanceof OffsetDateTime offset) return offset;
        if(raw instanceof java.sql.Timestamp value)
            return value.toInstant().atOffset(java.time.ZoneOffset.UTC);
        throw new IllegalStateException("Unsupported published_at timestamp");
    }
    private static String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch(Exception ex) {throw new IllegalStateException(ex);}
    }
    private String sign(String value) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret,"HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch(Exception e) {throw new IllegalStateException(e);}
    }
    @GetMapping
    public ResponseEntity<Page> search(HttpServletRequest request,
            @RequestParam(required=false) String q,
            @RequestParam(required=false) String type,
            @RequestParam(name="tag",required=false) List<String> tag,
            @RequestParam(required=false) Boolean hasAudio,
            @RequestParam(defaultValue="20") int limit,
            @RequestParam(required=false) String cursor) {
        authorize(request);
        if(limit<1 || limit>50) throw new SearchFailure(400,"PUBLICATION_SEARCH_INVALID_FILTER");
        String query=q(q),kind=type==null?"":type.strip().toUpperCase(Locale.ROOT);
        if(!kind.isEmpty() && !TYPES.contains(kind))
            throw new SearchFailure(400,"PUBLICATION_SEARCH_INVALID_FILTER");
        List<String> tags=tags(tag);
        String scope=sha(query+"|"+kind+"|"+String.join(",",tags)+"|"+hasAudio+"|"+limit);
        OffsetDateTime before=null;UUID beforeId=null;
        if(cursor!=null) {
            try {
                if(cursor.length()>800) throw new IllegalArgumentException();
                String data=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8);
                String[] parts=data.split("\\|",-1);
                if(parts.length!=5 || !parts[0].equals("v1") || !parts[1].equals(scope))
                    throw new IllegalArgumentException();
                String body=String.join("|",parts[0],parts[1],parts[2],parts[3]);
                if(!MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.US_ASCII),
                        parts[4].getBytes(StandardCharsets.US_ASCII)))
                    throw new IllegalArgumentException();
                before=OffsetDateTime.parse(parts[2]);
                beforeId=UUID.fromString(parts[3]);
            } catch(RuntimeException err) {
                throw new SearchFailure(400,"PUBLICATION_SEARCH_INVALID_CURSOR");
            }
        }
        String needle="%"+query.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        // The IN predicate must never receive an empty collection; tag_count controls semantics.
        List<String> predicateTags=tags.isEmpty()?List.of("__no_requested_tags__"):tags;
        List<Map<String,Object>> rows=jdbc.sql(SQL)
            .param("q",query).param("needle",needle).param("type",kind)
            .param("tag_count",tags.size()).param("tags",predicateTags).param("audio",hasAudio)
            .param("media_enabled",mediaEnabled)
            .param("before",before).param("before_id",beforeId).param("max",limit+1)
            .query().listOfRows();
        boolean more=rows.size()>limit;
        List<Map<String,Object>> shown=new ArrayList<>(rows.subList(0,Math.min(rows.size(),limit)));
        List<Summary> results=shown.stream().map(row -> {
            List<String> values;
            Object raw=row.get("tags");
            if(raw instanceof java.sql.Array array) {
                try {values=List.of((String[])array.getArray());}
                catch(java.sql.SQLException e){throw new IllegalStateException("Cannot decode tags",e);}
            } else if(raw instanceof String[] array) values=List.of(array);
            else values=List.of();
            return new Summary((UUID)row.get("id"),(String)row.get("title"),
                (String)row.get("author"),(String)row.get("material_type"),
                ((Number)row.get("version")).longValue(),timestamp(row.get("created_at")),
                values,Boolean.TRUE.equals(row.get("has_audio")),
                "/api/v1/community/publications/"+row.get("id"));
        }).toList();
        String next=null;
        if(more) {
            Map<String,Object> last=shown.getLast();
            String body="v1|"+scope+"|"+timestamp(last.get("created_at"))+"|"+last.get("id");
            next=Base64.getUrlEncoder().withoutPadding()
                .encodeToString((body+"|"+sign(body)).getBytes(StandardCharsets.UTF_8));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new Page(results,next));
    }
    static final class SearchFailure extends RuntimeException {
        final int status;
        SearchFailure(int status,String code){super(code);this.status=status;}
    }
    @ExceptionHandler(SearchFailure.class)
    ResponseEntity<Map<String,String>> error(SearchFailure failure) {
        return ResponseEntity.status(failure.status).cacheControl(CacheControl.noStore())
            .body(Map.of("code",failure.getMessage()));
    }
}
