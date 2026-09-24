package com.sitionix.forgeagent.domain.model;

import java.util.UUID;

/** Internal invitation delivered only over the authenticated first SSH direction. */
public record RemoteAccessReverseRequest(UUID pairId, String token) {
    @Override public String toString() { return "RemoteAccessReverseRequest[REDACTED]"; }
}
