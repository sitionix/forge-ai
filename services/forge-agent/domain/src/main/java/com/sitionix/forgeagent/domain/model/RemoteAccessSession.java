package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RemoteAccessSession(
        UUID id,
        UUID invitationId,
        RemoteAccessRole localRole,
        UUID grantorInstanceId,
        UUID accessorInstanceId,
        String peerDisplayName,
        RemoteAccessEndpoint endpoint,
        String pinnedHostPublicKey,
        String sessionPublicKey,
        String sessionFingerprint,
        UUID localPrivateKeyReference,
        RemoteAccessSessionStatus status,
        Instant createdAt,
        Instant provisioningExpiresAt,
        Instant activatedAt,
        Instant revokeRequestedAt,
        Instant revokedAt,
        RemoteAccessConnectivity connectivity,
        Instant lastSeenAt,
        Instant lastCheckedAt,
        String failureCode,
        String failureMessage,
        long version) {

    public RemoteAccessSession {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(invitationId, "invitationId must not be null");
        Objects.requireNonNull(localRole, "localRole must not be null");
        Objects.requireNonNull(grantorInstanceId, "grantorInstanceId must not be null");
        Objects.requireNonNull(accessorInstanceId, "accessorInstanceId must not be null");
        if (grantorInstanceId.equals(accessorInstanceId)) {
            throw new IllegalArgumentException("grantorInstanceId and accessorInstanceId must be different");
        }
        requireText(peerDisplayName, "peerDisplayName");
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        requireText(pinnedHostPublicKey, "pinnedHostPublicKey");
        requireText(sessionPublicKey, "sessionPublicKey");
        requireText(sessionFingerprint, "sessionFingerprint");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(provisioningExpiresAt, "provisioningExpiresAt must not be null");
        Objects.requireNonNull(connectivity, "connectivity must not be null");
        if (!provisioningExpiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("provisioningExpiresAt must be after createdAt");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        validateKeyReference(localRole, status, localPrivateKeyReference);
        validateLifecycle(status, createdAt, provisioningExpiresAt, activatedAt, revokeRequestedAt, revokedAt);
        validateObservationTimes(createdAt, lastSeenAt, lastCheckedAt);
        validateFailure(failureCode, failureMessage);
    }

    public RemoteAccessSession activate(Instant activatedAt) {
        Objects.requireNonNull(activatedAt, "activatedAt must not be null");
        if (status != RemoteAccessSessionStatus.PROVISIONING) {
            throw new IllegalStateException("only a provisioning session can be activated");
        }
        if (activatedAt.isBefore(createdAt) || !activatedAt.isBefore(provisioningExpiresAt)) {
            throw new IllegalStateException("session provisioning has expired");
        }
        return copy(RemoteAccessSessionStatus.ACTIVE, activatedAt, null, null,
                localPrivateKeyReference, version + 1);
    }

    public RemoteAccessSession requestRevoke(Instant requestedAt) {
        Objects.requireNonNull(requestedAt, "requestedAt must not be null");
        if (status == RemoteAccessSessionStatus.REVOKING || status == RemoteAccessSessionStatus.REVOKED) {
            return this;
        }
        if (requestedAt.isBefore(createdAt) || activatedAt != null && requestedAt.isBefore(activatedAt)) {
            throw new IllegalArgumentException("revoke request time is before the current lifecycle state");
        }
        return copy(RemoteAccessSessionStatus.REVOKING, activatedAt, requestedAt, null,
                localPrivateKeyReference, version + 1);
    }

    public RemoteAccessSession confirmRevoked(Instant confirmedAt) {
        Objects.requireNonNull(confirmedAt, "confirmedAt must not be null");
        if (status == RemoteAccessSessionStatus.REVOKED) {
            return this;
        }
        if (status != RemoteAccessSessionStatus.REVOKING) {
            throw new IllegalStateException("only a revoking session can be confirmed revoked");
        }
        if (confirmedAt.isBefore(revokeRequestedAt)) {
            throw new IllegalArgumentException("revocation confirmation precedes its request");
        }
        return copy(RemoteAccessSessionStatus.REVOKED, activatedAt, revokeRequestedAt, confirmedAt,
                null, version + 1);
    }

    /** Persist remote acknowledgement before attempting local credential deletion. */
    public RemoteAccessSession confirmRemoteRevoked(Instant confirmedAt) {
        if (localRole != RemoteAccessRole.ACCESSOR) throw new IllegalStateException("Accessor required");
        var confirmed = confirmRevoked(confirmedAt);
        return confirmed.copy(confirmed.status(), confirmed.activatedAt(), confirmed.revokeRequestedAt(),
                confirmed.revokedAt(), localPrivateKeyReference, confirmed.version());
    }

    public RemoteAccessSession clearRevokedCredential() {
        if (localRole != RemoteAccessRole.ACCESSOR || status != RemoteAccessSessionStatus.REVOKED) {
            throw new IllegalStateException("Confirmed remote revoke required");
        }
        return copy(status, activatedAt, revokeRequestedAt, revokedAt, null, version + 1);
    }

    /** Resolve diagnostics in the same version as confirmed lifecycle success. */
    public RemoteAccessSession confirmRevokedAndClearFailure(Instant confirmedAt) {
        var confirmed = confirmRevoked(confirmedAt);
        return confirmed.version() == version ? confirmed : confirmed.withFailure(null, null, confirmed.version());
    }

    public RemoteAccessSession confirmRemoteRevokedAndClearFailure(Instant confirmedAt) {
        var confirmed = confirmRemoteRevoked(confirmedAt);
        return confirmed.version() == version ? confirmed : confirmed.withFailure(null, null, confirmed.version());
    }

    public RemoteAccessSession clearRevokedCredentialAndFailure() {
        var cleared = clearRevokedCredential();
        return cleared.withFailure(null, null, cleared.version());
    }

    public RemoteAccessSession withFailure(String code, String message) {
        return withFailure(code, message, version + 1);
    }

    private RemoteAccessSession withFailure(String code, String message, long newVersion) {
        return new RemoteAccessSession(id, invitationId, localRole, grantorInstanceId, accessorInstanceId,
                peerDisplayName, endpoint, pinnedHostPublicKey, sessionPublicKey, sessionFingerprint,
                localPrivateKeyReference, status, createdAt, provisioningExpiresAt, activatedAt,
                revokeRequestedAt, revokedAt, connectivity, lastSeenAt, lastCheckedAt, code, message, newVersion);
    }

    private RemoteAccessSession copy(RemoteAccessSessionStatus newStatus, Instant newActivatedAt,
            Instant newRevokeRequestedAt, Instant newRevokedAt, UUID newKeyReference, long newVersion) {
        return new RemoteAccessSession(id, invitationId, localRole, grantorInstanceId, accessorInstanceId,
                peerDisplayName, endpoint, pinnedHostPublicKey, sessionPublicKey, sessionFingerprint,
                newKeyReference, newStatus, createdAt, provisioningExpiresAt, newActivatedAt,
                newRevokeRequestedAt, newRevokedAt, connectivity, lastSeenAt, lastCheckedAt,
                failureCode, failureMessage, newVersion);
    }

    private static void validateKeyReference(RemoteAccessRole role, RemoteAccessSessionStatus status,
            UUID keyReference) {
        if (role == RemoteAccessRole.GRANTOR && keyReference != null) {
            throw new IllegalArgumentException("grantor sessions must not have a local private key reference");
        }
        if (role == RemoteAccessRole.ACCESSOR && status != RemoteAccessSessionStatus.REVOKED
                && keyReference == null) {
            throw new IllegalArgumentException("accessor sessions require a local private key reference");
        }
    }

    private static void validateLifecycle(RemoteAccessSessionStatus status, Instant createdAt,
            Instant provisioningExpiresAt, Instant activatedAt, Instant revokeRequestedAt, Instant revokedAt) {
        if (activatedAt != null && (activatedAt.isBefore(createdAt) || !activatedAt.isBefore(provisioningExpiresAt))) {
            throw new IllegalArgumentException("activatedAt must be within the provisioning lifetime");
        }
        if (revokeRequestedAt != null && (revokeRequestedAt.isBefore(createdAt)
                || activatedAt != null && revokeRequestedAt.isBefore(activatedAt))) {
            throw new IllegalArgumentException("revokeRequestedAt is before the current lifecycle state");
        }
        if (revokedAt != null && (revokeRequestedAt == null || revokedAt.isBefore(revokeRequestedAt))) {
            throw new IllegalArgumentException("revokedAt must not precede revokeRequestedAt");
        }
        boolean valid = switch (status) {
            case PROVISIONING -> activatedAt == null && revokeRequestedAt == null && revokedAt == null;
            case ACTIVE -> activatedAt != null && revokeRequestedAt == null && revokedAt == null;
            case REVOKING -> revokeRequestedAt != null && revokedAt == null;
            case REVOKED -> revokeRequestedAt != null && revokedAt != null;
        };
        if (!valid) {
            throw new IllegalArgumentException("lifecycle timestamps do not match status " + status);
        }
    }

    private static void validateObservationTimes(Instant createdAt, Instant lastSeenAt, Instant lastCheckedAt) {
        if (lastSeenAt != null && lastSeenAt.isBefore(createdAt)
                || lastCheckedAt != null && lastCheckedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("observation times must not be before createdAt");
        }
        if (lastSeenAt != null && (lastCheckedAt == null || lastSeenAt.isAfter(lastCheckedAt))) {
            throw new IllegalArgumentException("lastSeenAt must not be after lastCheckedAt");
        }
    }

    private static void validateFailure(String code, String message) {
        if ((code == null) != (message == null)) {
            throw new IllegalArgumentException("failureCode and failureMessage must be set together");
        }
        if (code != null) {
            requireText(code, "failureCode");
            requireText(message, "failureMessage");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
