package ai.guitarmentor.infrastructure.security;

import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Run only with --spring.main.web-application-type=none and explicit admin command.
 * Never exposes management actions over HTTP.
 */
@Component
@ConditionalOnProperty(name="guitar-mentor.invitation-admin.action")
public class InvitationAdminCli implements ApplicationRunner {
    private final InvitationService service;
    private final org.springframework.core.env.Environment env;
    public InvitationAdminCli(InvitationService service, org.springframework.core.env.Environment env) {
        this.service=service; this.env=env;
    }
    @Override
    public void run(ApplicationArguments args) {
        String mode=env.getProperty("spring.main.web-application-type","");
        if (!"none".equalsIgnoreCase(mode))
            throw new IllegalStateException("Invitation administration requires non-web mode");
        String action=env.getRequiredProperty("guitar-mentor.invitation-admin.action");
        switch(action) {
            case "invite", "bootstrap-owner" -> {
                String email=env.getRequiredProperty("guitar-mentor.invitation-admin.email");
                String learner=env.getProperty("guitar-mentor.invitation-admin.learner");
                String token=service.issue(email,learner==null?null:UUID.fromString(learner),
                        action.equals("bootstrap-owner"));
                // This is a secret shown exactly once to the local operator. Do not save stdout logs.
                System.out.println("INVITATION_TOKEN="+token);
            }
            case "disable", "revoke" -> {
                UUID id=UUID.fromString(env.getRequiredProperty("guitar-mentor.invitation-admin.id"));
                if (action.equals("disable")) service.disable(id);
                else service.revoke(id);
                System.out.println("ADMIN_OPERATION_OK");
            }
            default -> throw new IllegalArgumentException("Unknown invitation action");
        }
    }
}
