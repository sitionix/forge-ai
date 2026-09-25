package com.sitionix.forgeagent.application.remoteaccess;

import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RemoteAccessManagement {
    private final RemoteAccessSessionRepository sessions;
    private final ForgeInstanceIdentityRepository identity;
    private final RemoteAccessPairingTransport transport;
    private final RemoteAccessPeerExecution grantor;
    private final RemoteAccessAccessorExecution accessor;
    private final Clock clock;
    private final RemoteAccessPairRepository pairs;
    private final RemoteAccessInvitations invitations;

    public List<RemoteAccessSession> list() { return sessions.findLocal(identity.getOrCreate()); }

    public RemoteAccessSession get(UUID id) {
        var local=identity.getOrCreate();
        return sessions.findById(id).filter(s -> (s.localRole()==RemoteAccessRole.GRANTOR
                ? s.grantorInstanceId() : s.accessorInstanceId()).equals(local))
                .orElseThrow(() -> new NotFoundException("REMOTE_ACCESS_SESSION_NOT_FOUND","Session not found"));
    }

    public RemoteAccessSession check(UUID id) {
        var session=get(id);
        var connectivity=RemoteAccessConnectivity.UNKNOWN;
        if (session.localRole()==RemoteAccessRole.ACCESSOR && session.localPrivateKeyReference()!=null
                && session.status()!=RemoteAccessSessionStatus.REVOKED) {
            try {
                if (transport.status(session)!=null) connectivity=RemoteAccessConnectivity.REACHABLE;
                else connectivity=RemoteAccessConnectivity.UNREACHABLE;
            } catch (RuntimeException unavailable) { connectivity=RemoteAccessConnectivity.UNREACHABLE; }
        }
        var now=clock.instant();
        if (session.lastCheckedAt()==null || !now.isBefore(session.lastCheckedAt())) {
            sessions.recordObservation(session,connectivity,now);
        }
        return get(id);
    }

    public RemoteAccessSession revoke(UUID id) {
        var pair=pairForSession(id);
        if (pair.isEmpty()) return revokeSingle(id);
        RuntimeException failure=null;
        var value=pair.get();
        UUID forward=value.forwardSessionId()==null?id:value.forwardSessionId();
        UUID reverse=value.reverseSessionId()!=null?value.reverseSessionId()
                : value.reverseInvitationId()==null?null
                : sessions.findByInvitation(value.reverseInvitationId()).map(RemoteAccessSession::id).orElse(null);
        for (var direction:java.util.stream.Stream.of(forward,reverse)
                .filter(java.util.Objects::nonNull).distinct().toList()) {
            try { revokeSingle(direction); }
            catch (RuntimeException incomplete) {
                if (failure==null) failure=incomplete;
                else failure.addSuppressed(incomplete);
            }
        }
        if (value.reverseInvitationId()!=null) {
            try { invitations.cancel(value.reverseInvitationId()); }
            catch (RuntimeException incomplete) {
                if (failure==null) failure=incomplete;
                else failure.addSuppressed(incomplete);
            }
        }
        if (failure!=null) throw failure;
        return get(id);
    }

    public boolean bridgeRevoked(UUID id) {
        var pair=pairForSession(id);
        if (pair.isEmpty()) return fullyRevoked(id);
        var value=pair.get();
        UUID forward=value.forwardSessionId()==null?id:value.forwardSessionId();
        if (!fullyRevoked(forward)) return false;
        UUID reverse=value.reverseSessionId()!=null?value.reverseSessionId()
                : value.reverseInvitationId()==null?null
                : sessions.findByInvitation(value.reverseInvitationId()).map(RemoteAccessSession::id).orElse(null);
        if (reverse!=null && !fullyRevoked(reverse)) return false;
        if (value.reverseInvitationId()!=null) {
            var invitation=invitations.get(value.reverseInvitationId());
            if (invitation.cancelledAt()==null && invitation.consumedAt()==null) return false;
        }
        return true;
    }

    private java.util.Optional<RemoteAccessPair> pairForSession(UUID id) {
        var linked=pairs.findBySession(id);
        if (linked.isPresent()) return linked;
        var session=sessions.findById(id);
        if (session.isEmpty() || session.get().localRole()!=RemoteAccessRole.ACCESSOR) return java.util.Optional.empty();
        return pairs.findById(session.get().invitationId()).filter(value ->
                value.localForwardRole()==RemoteAccessRole.ACCESSOR && value.forwardSessionId()==null);
    }

    private boolean fullyRevoked(UUID id) {
        return sessions.findById(id).filter(value -> value.status()==RemoteAccessSessionStatus.REVOKED
                && value.localPrivateKeyReference()==null).isPresent();
    }

    private RemoteAccessSession revokeSingle(UUID id) {
        var session=get(id);
        if (session.status()==RemoteAccessSessionStatus.REVOKED && session.localPrivateKeyReference()==null) return session;
        if (session.localRole()==RemoteAccessRole.ACCESSOR) return accessor.revoke(id);
        grantor.revoke(new RemoteAccessKeyBinding(session.grantorInstanceId(),id,session.sessionFingerprint()));
        return get(id);
    }
}
