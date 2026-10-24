package ai.guitarmentor.infrastructure.security;

import java.io.Serializable;
import java.util.UUID;

/** Session principal holds no password and is revoked when access_version changes. */
public record LocalPrincipal(UUID accountId, long accessVersion) implements Serializable {}
