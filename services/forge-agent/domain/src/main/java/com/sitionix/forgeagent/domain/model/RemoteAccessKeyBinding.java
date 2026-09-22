package com.sitionix.forgeagent.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Binding supplied by the protected SSH authorization source, never by the remote command. */
public record RemoteAccessKeyBinding(UUID grantorInstanceId, UUID sessionId, String fingerprint) {
    public RemoteAccessKeyBinding {
        Objects.requireNonNull(grantorInstanceId, "grantorInstanceId");
        Objects.requireNonNull(sessionId, "sessionId");
        if (fingerprint == null || !fingerprint.matches("SHA256:[A-Za-z0-9+/]{43}")) {
            throw new IllegalArgumentException("Invalid SSH key fingerprint");
        }
    }
}
