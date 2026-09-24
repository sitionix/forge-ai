package com.sitionix.forgeagent.application.remoteaccess;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.time.Clock;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class RemoteAccessGrantorPairing implements RemoteAccessPeerPairing {
    private final RemoteAccessInvitationRepository invitations;
    private final RemoteAccessSessionRepository sessions;
    private final ForgeInstanceIdentityRepository identity;
    private final RemoteAccessPairingTokens tokens;
    private final RemoteAccessInvitationGrants invitationGrants;
    private final RemoteAccessSessionGrants sessionGrants;
    private final RemoteAccessProvisioningService provisioning;
    private final RemoteAccessWorkloads workloads;
    private final RemoteAccessSwitch access;
    private final Clock clock;

    @Override public UUID redeem(RemoteAccessInvitationBinding binding, RemoteAccessPairingRequest request) {
        return access.admit(() -> redeemEnabled(binding,request));
    }

    private UUID redeemEnabled(RemoteAccessInvitationBinding binding, RemoteAccessPairingRequest request) {
        UUID local=identity.getOrCreate();
        if (!local.equals(binding.grantorInstanceId()) || request==null || request.sessionId()==null
                || request.accessorInstanceId()==null || local.equals(request.accessorInstanceId())) throw denied();
        requireDisplayName(request.accessorDisplayName());
        var invitation=invitations.findById(binding.invitationId()).filter(value -> value.grantorInstanceId().equals(local)
                && value.pairingFingerprint().equals(binding.fingerprint()) && value.isUsable(clock.instant())).orElseThrow(RemoteAccessGrantorPairing::denied);
        String fingerprint=tokens.fingerprint(request.sessionPublicKey());
        if (fingerprint.equals(invitation.pairingFingerprint())) throw denied();
        var now=clock.instant();
        var candidate=new RemoteAccessSession(request.sessionId(),invitation.id(),RemoteAccessRole.GRANTOR,local,
                request.accessorInstanceId(),request.accessorDisplayName(),invitation.endpoint(),invitationGrants.hostPublicKey(),
                request.sessionPublicKey(),fingerprint,null,RemoteAccessSessionStatus.PROVISIONING,now,now.plusSeconds(300),
                null,null,null,RemoteAccessConnectivity.UNKNOWN,null,null,null,null,0);
        var saved=provisioning.reserveGrantorSession(candidate);
        // Reservation + consume commit before OS work. Failure remains recoverable by persisted session key binding.
        sessionGrants.install(saved);
        try { invitationGrants.remove(invitation); }
        catch (RuntimeException incomplete) { log.warn("Consumed invitation grant cleanup pending"); }
        return saved.id();
    }

    @Override public Optional<RemoteAccessSessionStatus> confirm(RemoteAccessKeyBinding binding) {
        return access.admit(() -> confirmEnabled(binding));
    }

    private Optional<RemoteAccessSessionStatus> confirmEnabled(RemoteAccessKeyBinding binding) {
        UUID local=identity.getOrCreate();
        if (!local.equals(binding.grantorInstanceId())) return Optional.empty();
        var candidate=sessions.findById(binding.sessionId()).filter(value -> value.localRole()==RemoteAccessRole.GRANTOR
                && value.grantorInstanceId().equals(local) && value.sessionFingerprint().equals(binding.fingerprint()));
        if (candidate.isEmpty()) return Optional.empty();
        var session=candidate.get();
        if (session.status()==RemoteAccessSessionStatus.ACTIVE) return Optional.of(RemoteAccessSessionStatus.ACTIVE);
        var now=clock.instant();
        if (session.status()!=RemoteAccessSessionStatus.PROVISIONING || !now.isBefore(session.provisioningExpiresAt())) return Optional.empty();
        workloads.prepare(session.id());
        if (sessions.transition(session,session.activate(now))) return Optional.of(RemoteAccessSessionStatus.ACTIVE);
        return sessions.findById(session.id()).filter(value -> value.status()==RemoteAccessSessionStatus.ACTIVE)
                .map(RemoteAccessSession::status);
    }

    public void reconcile() {
        UUID local=identity.getOrCreate();
        for (var candidate:sessions.findLocal(local)) {
            if (candidate.localRole()!=RemoteAccessRole.GRANTOR) continue;
            var session=candidate;
            try {
                if (session.status()==RemoteAccessSessionStatus.PROVISIONING) {
                    if (clock.instant().isBefore(session.provisioningExpiresAt())) {
                        if (access.status().status()!=RemoteAccessSwitchStatus.ENABLED) continue;
                        var pending=session;
                        try { access.admit(() -> sessionGrants.install(pending)); }
                        catch (ConflictException disabled) { /* Disable owns cleanup; never restore a grant. */ }
                        continue;
                    }
                    if (!sessions.transition(session,session.requestRevoke(clock.instant()))) continue;
                    session=sessions.findById(session.id()).orElseThrow();
                }
                // Stage 4 creates no workloads; only expired handshake grants are cleaned here.
                if (session.status()==RemoteAccessSessionStatus.REVOKING && session.activatedAt()==null) {
                    sessionGrants.remove(session);
                    sessions.transition(session,session.confirmRevoked(clock.instant()));
                }
            } catch (RuntimeException incomplete) {
                sessions.recordFailure(session,"REMOTE_ACCESS_CLEANUP_PENDING","Pairing authorization reconciliation incomplete");
            }
        }
    }

    static void requireDisplayName(String value) {
        if (value==null || value.isBlank() || value.length()>128 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid peer display name");
        }
    }
    private static ConflictException denied() { return new ConflictException("REMOTE_ACCESS_PAIRING_DENIED","Invitation cannot be redeemed"); }
}
