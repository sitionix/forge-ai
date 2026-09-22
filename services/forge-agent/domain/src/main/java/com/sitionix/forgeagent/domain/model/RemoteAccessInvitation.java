package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RemoteAccessInvitation(
        UUID id,
        UUID grantorInstanceId,
        RemoteAccessEndpoint endpoint,
        String pairingPublicKey,
        String pairingFingerprint,
        Instant createdAt,
        Instant expiresAt,
        Instant consumedAt,
        Instant cancelledAt,
        UUID redeemedSessionId) {

    public RemoteAccessInvitation {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(grantorInstanceId, "grantorInstanceId must not be null");
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        requireText(pairingPublicKey, "pairingPublicKey");
        requireText(pairingFingerprint, "pairingFingerprint");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
        if ((consumedAt == null) != (redeemedSessionId == null)) {
            throw new IllegalArgumentException("consumedAt and redeemedSessionId must be set together");
        }
        if (consumedAt != null && (consumedAt.isBefore(createdAt) || !consumedAt.isBefore(expiresAt))) {
            throw new IllegalArgumentException("consumedAt must be within the invitation lifetime");
        }
        if (cancelledAt != null && cancelledAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("cancelledAt must not be before createdAt");
        }
        if (consumedAt != null && cancelledAt != null) {
            throw new IllegalArgumentException("an invitation cannot be both consumed and cancelled");
        }
    }

    public boolean isUsable(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return consumedAt == null && cancelledAt == null && now.isBefore(expiresAt);
    }

    public RemoteAccessInvitation redeem(UUID sessionId, Instant redeemedAt) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        validateEventTime(redeemedAt);
        if (!isUsable(redeemedAt)) {
            throw new IllegalStateException("invitation is not usable");
        }
        return new RemoteAccessInvitation(id, grantorInstanceId, endpoint, pairingPublicKey, pairingFingerprint,
                createdAt, expiresAt, redeemedAt, null, sessionId);
    }

    public RemoteAccessInvitation cancel(Instant cancelledAt) {
        Objects.requireNonNull(cancelledAt, "cancelledAt must not be null");
        if (this.cancelledAt != null) {
            return this;
        }
        validateEventTime(cancelledAt);
        if (consumedAt != null) {
            throw new IllegalStateException("consumed invitation cannot be cancelled");
        }
        return new RemoteAccessInvitation(id, grantorInstanceId, endpoint, pairingPublicKey, pairingFingerprint,
                createdAt, expiresAt, null, cancelledAt, null);
    }

    private void validateEventTime(Instant eventTime) {
        Objects.requireNonNull(eventTime, "event time must not be null");
        if (eventTime.isBefore(createdAt)) {
            throw new IllegalArgumentException("event time must not be before createdAt");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
