package ai.guitarmentor.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.*;

/** JSON endpoints. Mutating requests remain protected by the global CSRF filter. */
@RestController
@RequestMapping("/api/v1/local-auth")
public class LocalAuthController {
    private final LocalCredentialService service;
    private final ActorRepository actors;
    private final org.springframework.beans.factory.ObjectProvider<org.springframework.security.oauth2.client.registration.ClientRegistrationRepository> oidcClients;
    private final ConcurrentHashMap<String, Window> attempts = new ConcurrentHashMap<>();
    public LocalAuthController(LocalCredentialService service, ActorRepository actors,
            org.springframework.beans.factory.ObjectProvider<org.springframework.security.oauth2.client.registration.ClientRegistrationRepository> oidcClients) {
        this.service=service; this.actors=actors; this.oidcClients=oidcClients;
    }
    private record Window(long started,long attempts) {}
    // Conservative single-instance safety net, not a substitute for an edge/distributed limiter (#40).
    private void limit(HttpServletRequest req, String operation) {
        String key=operation+":"+req.getRemoteAddr();
        long now=System.currentTimeMillis();
        Window w=attempts.compute(key,(k,old)-> old==null || now-old.started>60000
            ?new Window(now,1):new Window(old.started,old.attempts+1));
        if(w.attempts>8) throw new RateLimited();
        if(attempts.size()>10000) attempts.entrySet().removeIf(e->now-e.getValue().started>60000);
    }
    @PostMapping("/register")
    ResponseEntity<Map<String,String>> register(@RequestBody Register req,HttpServletRequest http) {
        limit(http,"register");
        try {
            if(req.password()==null || !req.password().equals(req.confirmPassword()))
                return bad("INVALID_INPUT");
            service.register(req.invitation(),req.email(),req.login(),req.password());
            return ResponseEntity.accepted().body(Map.of("code","VERIFY_EMAIL"));
        } catch (IllegalArgumentException|SecurityException|org.springframework.dao.DataIntegrityViolationException ex) {
            return bad("REGISTRATION_REJECTED");
        }
    }
    @PostMapping("/verify")
    ResponseEntity<Map<String,String>> verify(@RequestBody Token req,HttpServletRequest http) {
        limit(http,"verify");
        try {service.verify(req.token());return ResponseEntity.ok(Map.of("code","EMAIL_VERIFIED"));}
        catch (SecurityException|org.springframework.dao.DataIntegrityViolationException ex) {
            return bad("TOKEN_INVALID");
        }
    }
    @PostMapping("/login")
    ResponseEntity<Map<String,String>> login(@RequestBody Login req,HttpServletRequest http,HttpServletResponse response) {
        limit(http,"login");
        try {
            UUID id=service.authenticate(req.identifier(),req.password());
            var version=actors.localVersion(id).orElseThrow(()->new SecurityException("INVALID_CREDENTIALS"));
            http.getSession(true);
            http.changeSessionId();
            var principal=new LocalPrincipal(id,version);
            var auth=new UsernamePasswordAuthenticationToken(principal,null,
                java.util.List.of(new SimpleGrantedAuthority("ROLE_USER")));
            var ctx=SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            SecurityContextHolder.setContext(ctx);
            new HttpSessionSecurityContextRepository().saveContext(ctx,http,
                response);
            return ResponseEntity.ok(Map.of("code","SIGNED_IN"));
        } catch(SecurityException ex) {
            return ResponseEntity.status(401).body(Map.of("code","INVALID_CREDENTIALS"));
        }
    }
    @PostMapping("/request-reset")
    ResponseEntity<Map<String,String>> requestReset(@RequestBody Email req,HttpServletRequest http) {
        limit(http,"reset-request");
        try {
            service.requestReset(req.email());
        } catch (RuntimeException deliveryFailure) {
            // Identical public response whether the address exists or SMTP is unavailable.
            // Deliberately never log the email or raw reset token.
            org.slf4j.LoggerFactory.getLogger(LocalAuthController.class)
                .warn("PASSWORD_RESET_DELIVERY_UNAVAILABLE");
        }
        return ResponseEntity.accepted().body(Map.of("code","IF_ACCOUNT_EXISTS_EMAIL_SENT"));
    }
    @PostMapping("/reset")
    ResponseEntity<Map<String,String>> reset(@RequestBody Reset req,HttpServletRequest http) {
        limit(http,"reset");
        try {service.reset(req.token(),req.password());return ResponseEntity.ok(Map.of("code","PASSWORD_RESET"));}
        catch(IllegalArgumentException|SecurityException ex) {return bad("TOKEN_INVALID");}
    }
    @GetMapping("/methods")
    ResponseEntity<Map<String,Boolean>> methods(HttpServletRequest http) {
        var actor=(CurrentActor) http.getAttribute("guitarMentorActor");
        if(actor == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(Map.of("passwordEnabled",actors.hasLocalCredential(actor.accountId()),
            "googleEnabled",actors.hasGoogleIdentity(actor.accountId()),
            "googleAvailable",oidcClients.getIfAvailable()!=null));
    }

    @PostMapping("/link/google/start")
    ResponseEntity<Map<String,String>> startGoogleLink(@RequestBody Login req,HttpServletRequest http) {
        limit(http,"google-link");
        var actor=(CurrentActor)http.getAttribute("guitarMentorActor");
        if(actor==null || !(SecurityContextHolder.getContext().getAuthentication().getPrincipal() instanceof LocalPrincipal))
            return ResponseEntity.status(403).body(Map.of("code","REAUTH_REQUIRED"));
        if(oidcClients.getIfAvailable()==null)
            return ResponseEntity.status(409).body(Map.of("code","GOOGLE_NOT_CONFIGURED"));
        try {
            var confirmed=service.authenticate(req.identifier(),req.password());
            if(!confirmed.equals(actor.accountId())) throw new SecurityException("REAUTH_REQUIRED");
            var session=http.getSession(true);
            session.setAttribute("guitarMentorLinkGoogleAccount",actor.accountId());
            session.setAttribute("guitarMentorLinkGoogleVersion",
                actors.localVersion(actor.accountId()).orElseThrow());
            session.setAttribute("guitarMentorLinkGoogleStarted",System.currentTimeMillis());
            return ResponseEntity.ok(Map.of("code","GOOGLE_LINK_STARTED"));
        } catch(SecurityException ex) {return bad("REAUTH_REQUIRED");}
    }

    @PostMapping("/link/password/start")
    ResponseEntity<Map<String,String>> startLink(@RequestBody LinkPassword req, HttpServletRequest http) {
        limit(http,"password-link");
        var actor=(CurrentActor)http.getAttribute("guitarMentorActor");
        if(actor==null || !(SecurityContextHolder.getContext().getAuthentication().getPrincipal()
                instanceof org.springframework.security.oauth2.core.oidc.user.OidcUser))
            return ResponseEntity.status(403).body(Map.of("code","REAUTH_REQUIRED"));
        Object loginAt=http.getSession(false)==null?null:http.getSession(false).getAttribute("guitarMentorAuthenticatedAt");
        if(!(loginAt instanceof Long timestamp) || System.currentTimeMillis()-timestamp>300000L)
            return ResponseEntity.status(403).body(Map.of("code","REAUTH_REQUIRED"));
        try {
            service.beginLink(actor.accountId(),req.email(),req.login(),req.password());
            return ResponseEntity.accepted().body(Map.of("code","VERIFY_EMAIL"));
        } catch(IllegalArgumentException|SecurityException|org.springframework.dao.DataIntegrityViolationException ex) {
            return bad("LINK_REJECTED");
        }
    }
    @PostMapping("/link/password/verify")
    ResponseEntity<Map<String,String>> verifyLink(@RequestBody Token req,HttpServletRequest http) {
        limit(http,"password-link-verify");
        var actor=(CurrentActor)http.getAttribute("guitarMentorActor");
        if(actor==null) return ResponseEntity.status(401).body(Map.of("code","UNAUTHENTICATED"));
        try {
            service.verifyLink(actor.accountId(),req.token());
            return ResponseEntity.ok(Map.of("code","PASSWORD_LINKED"));
        } catch(SecurityException|org.springframework.dao.DataIntegrityViolationException ex) {
            return bad("LINK_REJECTED");
        }
    }

    @PostMapping("/change")
    ResponseEntity<Map<String,String>> change(@RequestBody Change req,HttpServletRequest http) {
        limit(http,"change");
        var actor=(CurrentActor)http.getAttribute("guitarMentorActor");
        if(actor==null)return ResponseEntity.status(401).body(Map.of("code","UNAUTHENTICATED"));
        try {
            service.change(actor.accountId(),req.currentPassword(),req.newPassword());
            http.getSession().invalidate();
            SecurityContextHolder.clearContext();
            return ResponseEntity.ok(Map.of("code","PASSWORD_CHANGED"));
        }catch(IllegalArgumentException|SecurityException ex){return bad("INVALID_CREDENTIALS");}
    }
    @ExceptionHandler(RateLimited.class)
    ResponseEntity<Map<String,String>> limited() {
        return ResponseEntity.status(429).body(Map.of("code","RATE_LIMITED"));
    }
    private static ResponseEntity<Map<String,String>> bad(String code) {
        return ResponseEntity.badRequest().body(Map.of("code",code));
    }
    static class RateLimited extends RuntimeException {}
    public record Register(String invitation,String email,String login,String password,String confirmPassword) {}
    public record Token(String token) {}
    public record Login(String identifier,String password) {}
    public record Email(String email) {}
    public record Reset(String token,String password) {}
    public record Change(String currentPassword,String newPassword) {}
    public record LinkPassword(String email,String login,String password) {}
}
