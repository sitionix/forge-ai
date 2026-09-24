package com.sitionix.forgeagent.application.remoteaccess;

import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RemoteAccessAccessorExecution {
    private final RemoteAccessSessionRepository sessions;
    private final ForgeInstanceIdentityRepository identity;
    private final RemoteAccessRevokeTransport transport;
    private final RemoteAccessCredentialStore credentials;
    private final RemoteAccessCommandTransport commands;
    private final RemoteAccessSwitch access;
    private final Clock clock;
    private final Object[] gates=java.util.stream.IntStream.range(0,256).mapToObj(i -> new Object()).toArray();

    public RemoteAccessCommandExecution start(UUID id,RemoteAccessCommand command) {
        return access.admit(() -> startEnabled(id,command));
    }

    private RemoteAccessCommandExecution startEnabled(UUID id,RemoteAccessCommand command) {
        synchronized(gate(id)) {
            var session=owned(id);
            if (session.status()!=RemoteAccessSessionStatus.ACTIVE) throw new IllegalStateException("Active accessor session required");
            return commands.start(session,command);
        }
    }

    private Object gate(UUID id) { return gates[(id.hashCode() & 0x7fffffff)%gates.length]; }

    public RemoteAccessSession revoke(UUID id) {
        RemoteAccessSession session;
        synchronized(gate(id)) {
            session=owned(id);
            if (session.status()!=RemoteAccessSessionStatus.REVOKING && session.status()!=RemoteAccessSessionStatus.REVOKED) {
                if (!sessions.transition(session,session.requestRevoke(clock.instant()))) return owned(id);
                session=owned(id);
            }
        }
        if (session.status()==RemoteAccessSessionStatus.REVOKING) {
            try {
                if (transport.revoke(session)!=RemoteAccessSessionStatus.REVOKED) throw new IllegalStateException();
                var revoked=session.confirmRemoteRevokedAndClearFailure(clock.instant());
                if (!sessions.transition(session,revoked)) return owned(id);
                session=owned(id);
                // Database timestamp precision may differ; the CAS version identifies a newer writer.
                if (session.version()!=revoked.version()) return session;
            } catch (RuntimeException unavailable) {
                sessions.recordFailure(session,"REMOTE_ACCESS_REVOKE_UNCONFIRMED","Remote cleanup confirmation unavailable; credential retained");
                return owned(id);
            }
        }
        if (session.status()==RemoteAccessSessionStatus.REVOKED && session.localPrivateKeyReference()!=null) {
            try {
                credentials.delete(session.localPrivateKeyReference());
                var cleared=session.clearRevokedCredentialAndFailure();
                sessions.transition(session,cleared);
            } catch (RuntimeException unavailable) {
                sessions.recordFailure(session,"REMOTE_ACCESS_CREDENTIAL_CLEANUP_PENDING","Remote revoke confirmed; local credential cleanup incomplete");
            }
        }
        return owned(id);
    }
    public void reconcile() {
        for (var session:sessions.findLocal(identity.getOrCreate())) {
            if (session.localRole()==RemoteAccessRole.ACCESSOR && (session.status()==RemoteAccessSessionStatus.REVOKING
                    || session.status()==RemoteAccessSessionStatus.REVOKED && session.localPrivateKeyReference()!=null)) revoke(session.id());
        }
    }
    private RemoteAccessSession owned(UUID id) {
        UUID local=identity.getOrCreate();
        return sessions.findById(id).filter(s -> s.localRole()==RemoteAccessRole.ACCESSOR && s.accessorInstanceId().equals(local))
                .orElseThrow(() -> new IllegalStateException("Local accessor session required"));
    }
}
