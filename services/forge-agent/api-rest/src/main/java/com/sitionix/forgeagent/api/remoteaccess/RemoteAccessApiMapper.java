package com.sitionix.forgeagent.api.remoteaccess;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.RemoteAccessPairingTokens;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
@Component
@RequiredArgsConstructor
public class RemoteAccessApiMapper {
    private final RemoteAccessPairingTokens tokens;
    public RemoteAccessDtos.Endpoint endpoint(RemoteAccessEndpoint e) { return new RemoteAccessDtos.Endpoint(e.host(),e.port(),e.username()); }
    public RemoteAccessDtos.Capabilities capabilities(RemoteAccessCapabilities c) { return new RemoteAccessDtos.Capabilities(c.ready(),c.supportedOperations(),c.diagnostics()); }
    public RemoteAccessDtos.Invitation invitation(RemoteAccessInvitation i) {
        return new RemoteAccessDtos.Invitation(i.id(),i.grantorInstanceId(),endpoint(i.endpoint()),i.createdAt(),i.expiresAt(),i.consumedAt(),i.cancelledAt(),i.redeemedSessionId());
    }
    public RemoteAccessDtos.InvitationCreated created(RemoteAccessInvitationCreated c) { return new RemoteAccessDtos.InvitationCreated(invitation(c.invitation()),c.token().value()); }
    public RemoteAccessDtos.Session session(RemoteAccessSession s) {
        return new RemoteAccessDtos.Session(s.id(),s.invitationId(),s.localRole(),s.grantorInstanceId(),s.accessorInstanceId(),
            s.peerDisplayName(),endpoint(s.endpoint()),tokens.fingerprint(s.pinnedHostPublicKey()),s.status(),s.createdAt(),s.provisioningExpiresAt(),
            s.activatedAt(),s.revokeRequestedAt(),s.revokedAt(),s.connectivity(),s.lastSeenAt(),s.lastCheckedAt(),s.failureCode(),s.failureMessage());
    }
}
