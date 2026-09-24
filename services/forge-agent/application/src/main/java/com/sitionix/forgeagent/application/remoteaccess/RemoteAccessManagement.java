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
        var session=get(id);
        if (session.localRole()==RemoteAccessRole.ACCESSOR) return accessor.revoke(id);
        grantor.revoke(new RemoteAccessKeyBinding(session.grantorInstanceId(),id,session.sessionFingerprint()));
        return get(id);
    }
}
