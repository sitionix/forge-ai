package com.sitionix.forgeagent.domain.model;

import java.util.Objects;
import java.util.UUID;

public record RemoteAccessInvitationBinding(UUID grantorInstanceId, UUID invitationId, String fingerprint) {
    public RemoteAccessInvitationBinding {
        Objects.requireNonNull(grantorInstanceId);
        Objects.requireNonNull(invitationId);
        if (fingerprint == null || !fingerprint.matches("SHA256:[A-Za-z0-9+/]{43}")) {
            throw new IllegalArgumentException("Invalid pairing key binding");
        }
    }
}
