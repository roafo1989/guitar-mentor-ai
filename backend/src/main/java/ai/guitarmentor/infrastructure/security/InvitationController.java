package ai.guitarmentor.infrastructure.security;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InvitationController {
    private final InvitationService invitations;
    private final ActorRepository actors;
    public InvitationController(InvitationService invitations, ActorRepository actors) { this.invitations=invitations; this.actors=actors; }

    @PostMapping("/api/v1/invitations/activate")
    ResponseEntity<Map<String,String>> activate(@RequestBody ActivateRequest body,
                                                @AuthenticationPrincipal OidcUser identity,
                                                jakarta.servlet.http.HttpServletRequest request) {
        try {
            var account = invitations.activate(body.token(), identity);
            request.getSession(true).setAttribute("guitarMentorAccessVersion",
                actors.localVersion(account).orElseThrow());
            return ResponseEntity.ok(Map.of("code","INVITATION_ACCEPTED"));
        } catch (SecurityException | org.springframework.dao.DataIntegrityViolationException ex) {
            return ResponseEntity.status(403).body(Map.of("code","INVITATION_INVALID"));
        }
    }

    public record ActivateRequest(String token) {}
}
