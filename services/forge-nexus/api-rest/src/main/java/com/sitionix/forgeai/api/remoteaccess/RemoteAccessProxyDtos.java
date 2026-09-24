package com.sitionix.forgeai.api.remoteaccess;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.sitionix.forgeai.domain.remoteaccess.RemoteAccessModels.*;
public final class RemoteAccessProxyDtos {
    private RemoteAccessProxyDtos() {}
    public record InvitationRequest(@Size(max=253) String advertisedHost) {}
    public record ConnectRequest(@NotBlank @Size(max=16384) String pairingToken) {
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
