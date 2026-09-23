package com.sitionix.forgeagent.application.remoteaccess;

import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.time.Clock;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;

/** One authority owns admission. Never holds a database transaction across supervisor IO. */
@Service
public final class RemoteAccessExecutionService implements RemoteAccessPeerExecution {
    private final RemoteAccessSessionRepository sessions;
    private final ForgeInstanceIdentityRepository identity;
    private final RemoteAccessWorkloads workloads;
    private final RemoteAccessSessionGrants grants;
    private final Clock clock;
    private final UUID epoch=UUID.randomUUID();
    private final Object[] gates=IntStream.range(0,256).mapToObj(i -> new Object()).toArray();
    private volatile boolean ready;

    public RemoteAccessExecutionService(RemoteAccessSessionRepository sessions, ForgeInstanceIdentityRepository identity,
            RemoteAccessWorkloads workloads, RemoteAccessSessionGrants grants, Clock clock) {
        this.sessions=sessions; this.identity=identity; this.workloads=workloads; this.grants=grants; this.clock=clock;
    }

    @Override public void start(RemoteAccessKeyBinding binding, UUID attachmentId) {
        synchronized(gate(binding.sessionId())) {
            if (!ready) throw denied();
            var session=owned(binding);
            if (session.status()!=RemoteAccessSessionStatus.ACTIVE) throw denied();
            workloads.start(session.id(),attachmentId,epoch);
        }
    }

    @Override public RemoteAccessSessionStatus revoke(RemoteAccessKeyBinding binding) {
        RemoteAccessSession session;
        synchronized(gate(binding.sessionId())) {
            session=owned(binding);
            if (session.status()!=RemoteAccessSessionStatus.REVOKING && session.status()!=RemoteAccessSessionStatus.REVOKED) {
                if (!sessions.transition(session,session.requestRevoke(clock.instant()))) return owned(binding).status();
                session=owned(binding);
            }
        }
        // Cleanup may wait for systemd. Admissions now reject the persisted REVOKING row.
        if (session.status()==RemoteAccessSessionStatus.REVOKING && cleanup(session)) return RemoteAccessSessionStatus.REVOKED;
        return owned(binding).status();
    }

    private boolean cleanup(RemoteAccessSession session) {
        boolean stopped=false, removed=false;
        try { grants.remove(session); removed=true; } catch (RuntimeException unavailable) { /* still stop processes */ }
        try { workloads.stop(session.id()); stopped=true; } catch (RuntimeException unavailable) { /* retain intent */ }
        if (stopped && removed) {
            var revoked=session.confirmRevokedAndClearFailure(clock.instant());
            return sessions.transition(session,revoked);
        } else {
            sessions.recordFailure(session,"REMOTE_ACCESS_CLEANUP_PENDING","Managed session cleanup incomplete");
            return false;
        }
    }

    /** A lost DB/supervisor check stops lease renewal. Supervisor enforces its monotonic deadline. */
    public synchronized void maintain() {
        try {
            UUID local=identity.getOrCreate();
            var localSessions=sessions.findLocal(local);
            if (!ready) {
                workloads.reconcile(epoch);
                reconcileSessions(localSessions,local);
            }
            workloads.heartbeat(epoch);
            ready=true;
        } catch (RuntimeException unavailable) { ready=false; throw unavailable; }
    }

    public void reconcile() {
        UUID local=identity.getOrCreate();
        reconcileSessions(sessions.findLocal(local),local);
    }

    private void reconcileSessions(java.util.List<RemoteAccessSession> localSessions,UUID local) {
            for (var session:localSessions) {
                if (session.localRole()==RemoteAccessRole.GRANTOR && session.status()==RemoteAccessSessionStatus.REVOKING) {
                    revoke(new RemoteAccessKeyBinding(local,session.id(),session.sessionFingerprint()));
                }
            }
    }

    private RemoteAccessSession owned(RemoteAccessKeyBinding binding) {
        if (!identity.getOrCreate().equals(binding.grantorInstanceId())) throw denied();
        return sessions.findById(binding.sessionId()).filter(s -> s.localRole()==RemoteAccessRole.GRANTOR
                && s.grantorInstanceId().equals(binding.grantorInstanceId())
                && s.sessionFingerprint().equals(binding.fingerprint())).orElseThrow(RemoteAccessExecutionService::denied);
    }
    private Object gate(UUID id) { return gates[(id.hashCode() & 0x7fffffff)%gates.length]; }
    private static IllegalStateException denied() { return new IllegalStateException("Remote access execution denied"); }
}
