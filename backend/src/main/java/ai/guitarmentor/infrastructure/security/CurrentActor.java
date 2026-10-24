package ai.guitarmentor.infrastructure.security;

import java.util.UUID;

/** Only trusted server-side OIDC identities can create an actor. */
public record CurrentActor(UUID accountId, UUID learnerId, String role) {}
