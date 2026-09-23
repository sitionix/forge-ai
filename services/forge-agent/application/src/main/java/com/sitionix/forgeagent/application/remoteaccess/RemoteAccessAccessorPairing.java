package com.sitionix.forgeagent.application.remoteaccess;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RemoteAccessAccessorPairing {
    private final RemoteAccessSessionRepository sessions;
    private final ForgeInstanceIdentityRepository identity;
    private final RemoteAccessPairingTokens tokens;
    private final RemoteAccessProvisioningService provisioning;
    private final RemoteAccessPairingTransport transport;
    private final Clock clock;

    public RemoteAccessSession connect(String token,String displayName) {
        RemoteAccessGrantorPairing.requireDisplayName(displayName);
        UUID local=identity.getOrCreate();
        try (var details=tokens.decode(token,local)) {
            RemoteAccessSession attempt;
            boolean fresh=false;
            // Only local preparation is serialized; no SSH call or network wait under this lock/DB transaction.
            synchronized (this) {
                var existing=sessions.findByInvitation(details.invitationId());
                if (existing.isPresent()) {
                    attempt=existing.get();
                    requireSamePeer(attempt,details,local);
                } else {
                    try (var keys=tokens.generate()) {
                        var now=clock.instant();
                        UUID id=UUID.randomUUID();
                        var candidate=new RemoteAccessSession(id,details.invitationId(),RemoteAccessRole.ACCESSOR,
                                details.grantorInstanceId(),local,details.displayName(),details.endpoint(),details.hostPublicKey(),
                                keys.publicKey(),keys.fingerprint(),id,RemoteAccessSessionStatus.PROVISIONING,now,now.plusSeconds(300),
                                null,null,null,RemoteAccessConnectivity.UNKNOWN,null,null,null,null,0);
                        attempt=provisioning.createAccessorSession(candidate,keys.privateKey());
                        fresh=true;
                    }
                }
            }
            if (fresh) {
                try { transport.redeem(attempt,details.privateKey(),displayName); }
                catch (RuntimeException unavailable) {
                    sessions.recordFailure(attempt,"REMOTE_ACCESS_PAIRING_UNCONFIRMED","Pairing response unavailable; session-key recovery required");
                }
            }
            // Never send a consumed invitation key as a recovery credential.
            return resume(attempt.id());
        }
    }

    public RemoteAccessSession resume(UUID sessionId) {
        var session=owned(sessionId);
        if (session.status()!=RemoteAccessSessionStatus.PROVISIONING) return session;
        if (!clock.instant().isBefore(session.provisioningExpiresAt())) return expire(session);
        try {
            if (transport.confirm(session)!=RemoteAccessSessionStatus.ACTIVE) throw new IllegalStateException("Activation not confirmed");
            var current=owned(sessionId);
            if (current.status()==RemoteAccessSessionStatus.PROVISIONING) {
                if (!clock.instant().isBefore(current.provisioningExpiresAt())) return expire(current);
                sessions.transition(current,current.activate(clock.instant()));
            }
            var result=owned(sessionId);
            if (result.status()==RemoteAccessSessionStatus.ACTIVE && result.failureCode()!=null) {
                sessions.recordFailure(result,null,null);
                result=owned(sessionId);
            }
            return result;
        } catch (RuntimeException unavailable) {
            var current=owned(sessionId);
            if (current.status()==RemoteAccessSessionStatus.PROVISIONING) {
                sessions.recordFailure(current,"REMOTE_ACCESS_CONFIRMATION_UNAVAILABLE","Session-key confirmation unavailable");
            }
            return owned(sessionId);
        }
    }

    public void reconcile() {
        UUID local=identity.getOrCreate();
        for (var session:sessions.findLocal(local)) {
            if (session.localRole()==RemoteAccessRole.ACCESSOR && session.status()==RemoteAccessSessionStatus.PROVISIONING) resume(session.id());
        }
    }

    private RemoteAccessSession expire(RemoteAccessSession session) {
        if (!sessions.transition(session,session.requestRevoke(clock.instant()))) return owned(session.id());
        var current=owned(session.id());
        sessions.recordFailure(current,"REMOTE_ACCESS_PROVISIONING_EXPIRED","Remote cleanup confirmation unavailable; credential retained");
        return owned(session.id());
    }
    private RemoteAccessSession owned(UUID id) {
        UUID local=identity.getOrCreate();
        return sessions.findById(id).filter(value -> value.localRole()==RemoteAccessRole.ACCESSOR && value.accessorInstanceId().equals(local))
                .orElseThrow(() -> new NotFoundException("REMOTE_ACCESS_SESSION_NOT_FOUND","Accessor session not found"));
    }
    private static void requireSamePeer(RemoteAccessSession session,RemoteAccessPairingDetails details,UUID local) {
        if (session.localRole()!=RemoteAccessRole.ACCESSOR || !session.accessorInstanceId().equals(local)
                || !session.grantorInstanceId().equals(details.grantorInstanceId()) || !session.endpoint().equals(details.endpoint())
                || !session.pinnedHostPublicKey().equals(details.hostPublicKey())) {
            throw new ConflictException("REMOTE_ACCESS_PAIRING_CONFLICT","Invitation is bound to another peer identity");
        }
    }
}
