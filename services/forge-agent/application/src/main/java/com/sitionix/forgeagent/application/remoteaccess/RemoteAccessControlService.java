package com.sitionix.forgeagent.application.remoteaccess;

import com.sitionix.forgeagent.domain.model.RemoteAccessSessionStatus;
import com.sitionix.forgeagent.domain.model.RemoteAccessSwitchStatus;
import com.sitionix.forgeagent.domain.port.RemoteAccessSetup;
import lombok.RequiredArgsConstructor;

/** Coordinates the persisted admission fence with existing per-session revoke paths. */
@RequiredArgsConstructor
public class RemoteAccessControlService {
    private final RemoteAccessSwitch access;
    private final RemoteAccessInvitations invitations;
    private final RemoteAccessManagement management;
    private final RemoteAccessSetup setup;

    public RemoteAccessControlStatus status() {
        var state=access.status();
        var capabilities=setup.capabilities();
        int sessions=(int)management.list().stream().filter(session ->
                session.status()!=RemoteAccessSessionStatus.REVOKED || session.localPrivateKeyReference()!=null).count();
        int invites=(int)invitations.list().stream().filter(invitation ->
                invitation.consumedAt()==null && invitation.cancelledAt()==null).count();
        return new RemoteAccessControlStatus(state.status(),capabilities.ready(),sessions,invites,
                state.status()==RemoteAccessSwitchStatus.DISABLING ? "REMOTE_ACCESS_CLEANUP_PENDING" :
                        capabilities.ready() ? null : "REMOTE_ACCESS_SETUP_NOT_READY");
    }

    public RemoteAccessControlStatus enable() {
        if (!setup.capabilities().ready()) throw new IllegalStateException("Remote Access setup is not ready");
        var next=access.enable();
        if (next.status()!=RemoteAccessSwitchStatus.ENABLED) {
            throw new IllegalStateException("Remote Access cleanup is still pending");
        }
        return status();
    }

    public RemoteAccessControlStatus disable() {
        var next=access.beginDisable();
        if (next.status()==RemoteAccessSwitchStatus.DISABLED) return status();
        if (next.status()!=RemoteAccessSwitchStatus.DISABLING) {
            throw new IllegalStateException("Remote Access disable was not persisted");
        }
        // Per-peer SSH revoke can take up to 90 seconds. HTTP only persists the fence;
        // the dedicated recovery worker performs bounded cleanup and survives restarts.
        return status();
    }

    public void reconcile() {
        if (access.status().status()!=RemoteAccessSwitchStatus.DISABLING) return;
        boolean failed=false;
        for (var invitation:invitations.list()) {
            try { invitations.cancel(invitation.id()); }
            catch (RuntimeException incomplete) { failed=true; }
        }
        for (var session:management.list()) {
            if (session.status()==RemoteAccessSessionStatus.REVOKED && session.localPrivateKeyReference()==null) continue;
            try { management.revoke(session.id()); }
            catch (RuntimeException incomplete) { failed=true; }
        }
        var observed=status();
        if (!failed && observed.pendingSessions()==0 && observed.pendingInvitations()==0) {
            access.finishDisable();
        }
    }
}
