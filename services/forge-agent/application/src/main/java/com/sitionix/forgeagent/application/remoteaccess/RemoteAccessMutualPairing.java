package com.sitionix.forgeagent.application.remoteaccess;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

/** Coordinates two independently confirmed SSH directions from one human invitation. */
@RequiredArgsConstructor
public class RemoteAccessMutualPairing {
    private final RemoteAccessAccessorPairing accessor;
    private final RemoteAccessInvitations invitations;
    private final RemoteAccessSetup setup;
    private final RemoteAccessPairingTokens tokens;
    private final ForgeInstanceIdentityRepository identity;
    private final RemoteAccessPairRepository pairs;
    private final RemoteAccessSessionRepository sessions;
    private final RemoteAccessReverseInvitationStore secrets;
    private final RemoteAccessReverseExchange exchange;
    private final Clock clock;
    private final RemoteAccessManagement management;

    public RemoteAccessSession connect(String token,String displayName) {
        UUID pairId;
        synchronized (this) {
            try (var peer=tokens.decode(token,identity.getOrCreate())) {
                pairId=peer.invitationId();
                if (pairs.findById(pairId).isEmpty()) {
                    // The chosen source address is the one Linux routes toward this actual peer.
                    var endpoint=setup.advertisedEndpointForPeer(peer.endpoint());
                    var reverse=invitations.create(endpoint,setup.displayName());
                    boolean secretStored=false;
                    try {
                        secrets.store(pairId,reverse.token());
                        secretStored=true;
                        pairs.insert(RemoteAccessPair.connector(pairId,reverse.invitation().id(),clock.instant()));
                    } catch (RuntimeException incomplete) {
                        if (secretStored) try { secrets.delete(pairId); } catch (RuntimeException ignored) { incomplete.addSuppressed(ignored); }
                        try { invitations.cancel(reverse.invitation().id()); } catch (RuntimeException ignored) { incomplete.addSuppressed(ignored); }
                        throw incomplete;
                    }
                } else if (pairs.findById(pairId).orElseThrow().localForwardRole()!=RemoteAccessRole.ACCESSOR) {
                    throw new ConflictException("REMOTE_ACCESS_PAIRING_CONFLICT","Invitation already belongs to another pairing direction");
                }
            }
        }
        var forward=accessor.connect(token,displayName);
        var pair=pairs.findById(pairId).orElseThrow();
        if (pair.forwardSessionId()==null) {
            pairs.transition(pair,pair.withForwardSession(forward.id()));
            pair=pairs.findById(pairId).orElseThrow();
        }
        if (!forward.id().equals(pair.forwardSessionId()))
            throw new ConflictException("REMOTE_ACCESS_PAIRING_CONFLICT","Invitation belongs to another local session");
        return advance(pair,forward);
    }

    public RemoteAccessSession resume(UUID sessionId) {
        var pair=pairs.findBySession(sessionId).orElseThrow(() -> new ConflictException(
                "REMOTE_ACCESS_PAIRING_CONFLICT","Session does not belong to a mutual pair"));
        if (pair.localForwardRole()!=RemoteAccessRole.ACCESSOR || !sessionId.equals(pair.forwardSessionId()))
            throw new ConflictException("REMOTE_ACCESS_PAIRING_CONFLICT","Session cannot resume this pairing direction");
        return advance(pair,accessor.resume(sessionId));
    }

    public boolean connected(UUID sessionId) {
        var pair=pairs.findBySession(sessionId);
        if (pair.isEmpty()) return false;
        var value=pair.get();
        if (value.forwardSessionId()==null || value.reverseSessionId()==null) return false;
        var forward=sessions.findById(value.forwardSessionId());
        var reverse=sessions.findById(value.reverseSessionId());
        return forward.isPresent() && reverse.isPresent()
                && forward.get().localRole()==value.localForwardRole()
                && reverse.get().localRole()!=value.localForwardRole()
                && forward.get().grantorInstanceId().equals(reverse.get().accessorInstanceId())
                && forward.get().accessorInstanceId().equals(reverse.get().grantorInstanceId())
                && value.connected(forward.get().status(),reverse.get().status());
    }

    public UUID pairId(UUID sessionId) {
        return pairs.findBySession(sessionId).map(RemoteAccessPair::id).orElse(null);
    }

    public boolean internalInvitation(UUID invitationId) { return pairs.isReverseInvitation(invitationId); }

    public void resumeIfPending(UUID sessionId) {
        var pair=pairs.findBySession(sessionId);
        if (pair.isPresent() && pair.get().localForwardRole()==RemoteAccessRole.ACCESSOR
                && sessionId.equals(pair.get().forwardSessionId()) && pair.get().reverseSessionId()==null) {
            resume(sessionId);
        }
    }

    public void reconcile() {
        for (var snapshot:pairs.findConnectorPairs()) {
            try {
                var pair=pairs.findById(snapshot.id()).orElseThrow();
                if (pair.reverseSessionId()!=null) {
                    secrets.delete(pair.id());
                    continue;
                }
                if (pair.forwardSessionId()==null) {
                    var forward=sessions.findByInvitation(pair.id());
                    if (forward.isEmpty() || forward.get().localRole()!=RemoteAccessRole.ACCESSOR) continue;
                    pairs.transition(pair,pair.withForwardSession(forward.get().id()));
                    pair=pairs.findById(pair.id()).orElseThrow();
                }
                if (pair.forwardSessionId()!=null && pair.reverseSessionId()==null) resume(pair.forwardSessionId());
            } catch (RuntimeException unavailable) {
                // The persisted pair and protected token remain available for the next bounded attempt.
            }
        }
    }

    private RemoteAccessSession advance(RemoteAccessPair pair,RemoteAccessSession forward) {
        if (pair.reverseSessionId()!=null) {
            secrets.delete(pair.id());
            return forward;
        }
        if (forward.status()!=RemoteAccessSessionStatus.ACTIVE) return forward;
        var reverseInvitation=invitations.get(pair.reverseInvitationId());
        if (reverseInvitation.consumedAt()==null && !reverseInvitation.isUsable(clock.instant())) {
            management.revoke(forward.id());
            throw new ConflictException("REMOTE_ACCESS_PAIRING_EXPIRED","Reverse pairing invitation expired; create a new invitation");
        }
        var existingReverse=sessions.findByInvitation(pair.reverseInvitationId());
        if (existingReverse.isPresent() && (existingReverse.get().status()==RemoteAccessSessionStatus.REVOKING
                || existingReverse.get().status()==RemoteAccessSessionStatus.REVOKED)) {
            management.revoke(forward.id());
            throw new ConflictException("REMOTE_ACCESS_PAIRING_CONFLICT","Reverse pairing failed; create a new invitation");
        }
        // A lost response is safe to retry: the other side reuses the same invitation/session.
        UUID reverseId;
        var reverseToken=secrets.read(pair.id()).value();
        try { reverseId=exchange.exchange(forward,reverseToken); }
        catch (RuntimeException pending) { return forward; }
        var reverse=sessions.findById(reverseId).filter(value -> value.localRole()==RemoteAccessRole.GRANTOR
                && value.invitationId().equals(pair.reverseInvitationId())
                && value.grantorInstanceId().equals(identity.getOrCreate())
                && value.accessorInstanceId().equals(forward.grantorInstanceId())
                && value.status()==RemoteAccessSessionStatus.ACTIVE);
        if (reverse.isEmpty()) throw new ConflictException("REMOTE_ACCESS_PAIRING_CONFLICT",
                "Reverse SSH confirmation did not match the local invitation");
        var current=pairs.findById(pair.id()).orElseThrow();
        if (current.reverseSessionId()==null) {
            pairs.transition(current,current.withReverseSession(reverseId));
            current=pairs.findById(pair.id()).orElseThrow();
        }
        if (reverseId.equals(current.reverseSessionId())) {
            secrets.delete(pair.id());
        }
        return forward;
    }
}
