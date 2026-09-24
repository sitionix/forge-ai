package com.sitionix.forgeai.domain.remoteaccess;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public final class RemoteAccessModels {
    private RemoteAccessModels() {}
    public enum Role { GRANTOR, ACCESSOR }
    public enum Status { PROVISIONING, ACTIVE, REVOKING, REVOKED }
    public enum Connectivity { REACHABLE, UNREACHABLE, UNKNOWN }
    public enum SwitchStatus { DISABLED, ENABLED, DISABLING }
    public record InvitationRequest(String advertisedHost) {}
    public record ConnectRequest(String pairingToken) {
        @Override public String toString() { return "ConnectRequest[REDACTED]"; }
    }
    public record Endpoint(String host, int port, String username) {}
    public record Capabilities(boolean ready, List<String> supportedOperations, List<String> diagnostics) {}
    public record Control(SwitchStatus status, boolean ready, int pendingSessions,
                          int pendingInvitations, String diagnostic) {}
    public record Invitation(UUID id, UUID grantorInstanceId, Endpoint endpoint, Instant createdAt,
            Instant expiresAt, Instant consumedAt, Instant cancelledAt, UUID redeemedSessionId) {}
    public record InvitationCreated(Invitation invitation, String token) {
        @Override public String toString() { return "InvitationCreated[REDACTED]"; }
    }
    public record Session(UUID id, UUID invitationId, Role localRole, UUID grantorInstanceId,
            UUID accessorInstanceId, String peerDisplayName, Endpoint endpoint, String hostFingerprint,
            Status status, Instant createdAt, Instant provisioningExpiresAt, Instant activatedAt,
            Instant revokeRequestedAt, Instant revokedAt, Connectivity connectivity,
            Instant lastSeenAt, Instant lastCheckedAt, String failureCode, String failureMessage) {}
    public record Error(String code, String message, String correlationId) {}
}
