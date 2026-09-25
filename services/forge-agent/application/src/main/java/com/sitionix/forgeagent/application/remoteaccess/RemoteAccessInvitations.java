package com.sitionix.forgeagent.application.remoteaccess;

import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Local application operations; no HTTP management exposure or session activation. */
@Service
public class RemoteAccessInvitations {
    private final RemoteAccessInvitationRepository invitations;
    private final ForgeInstanceIdentityRepository identity;
    private final RemoteAccessPairingTokens tokens;
    private final RemoteAccessInvitationGrants grants;
    private final RemoteAccessSwitch access;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public RemoteAccessInvitations(RemoteAccessInvitationRepository invitations, ForgeInstanceIdentityRepository identity,
            RemoteAccessPairingTokens tokens, RemoteAccessInvitationGrants grants, RemoteAccessSwitch access,
            Clock clock, PlatformTransactionManager manager) {
        this.invitations=invitations; this.identity=identity; this.tokens=tokens; this.grants=grants; this.access=access; this.clock=clock;
        this.transactions=new TransactionTemplate(manager);
    }

    public RemoteAccessInvitationCreated create(RemoteAccessEndpoint advertisedEndpoint, String displayName) {
        return access.admit(() -> createEnabled(advertisedEndpoint, displayName));
    }

    private RemoteAccessInvitationCreated createEnabled(RemoteAccessEndpoint advertisedEndpoint, String displayName) {
        requireOwnTransaction();
        // Endpoint must be explicitly supplied/configured; never guess from network interfaces.
        tokens.validateEndpoint(advertisedEndpoint);
        String hostPublicKey=grants.hostPublicKey();
        try (var keys=tokens.generate()) {
            var now=clock.instant();
            var invitation=new RemoteAccessInvitation(UUID.randomUUID(),identity.getOrCreate(),advertisedEndpoint,
                    keys.publicKey(),keys.fingerprint(),now,now.plusSeconds(300),null,null,null);
            transactions.executeWithoutResult(status -> invitations.insert(invitation));
            try {
                grants.install(invitation);
                var token=tokens.encode(invitation,displayName,hostPublicKey,keys.privateKey());
                return new RemoteAccessInvitationCreated(invitation,token);
            } catch (RuntimeException failure) {
                // DB and filesystem are not atomic. Deny first and independently attempt owned cleanup.
                boolean cleanupFailed=false;
                try { invitations.cancel(invitation.id(),clock.instant()); }
                catch (RuntimeException unavailable) { cleanupFailed=true; }
                try { grants.remove(invitation); }
                catch (RuntimeException unavailable) { cleanupFailed=true; }
                throw new IllegalStateException(cleanupFailed ? "Invitation provisioning failed; cleanup requires reconciliation"
                        : "Invitation provisioning failed");
            }
        }
    }

    public RemoteAccessInvitation get(UUID id) {
        UUID local=identity.getOrCreate();
        return invitations.findById(id).filter(invitation -> invitation.grantorInstanceId().equals(local))
                .orElseThrow(() -> new NotFoundException("REMOTE_ACCESS_INVITATION_NOT_FOUND","Invitation not found"));
    }

    public List<RemoteAccessInvitation> list() {
        return invitations.findAll(identity.getOrCreate());
    }

    public void cancel(UUID id) {
        requireOwnTransaction();
        var invitation=get(id);
        if (invitation.consumedAt()==null && invitation.cancelledAt()==null) {
            invitations.cancel(id,clock.instant());
            invitation=get(id);
            if (invitation.consumedAt()==null && invitation.cancelledAt()==null) {
                throw new IllegalStateException("Invitation cancellation was not confirmed");
            }
        }
        // Consumed invitation cleanup removes only its pairing key, never session authorization.
        grants.remove(invitation);
    }

    public void cleanupUnavailable() {
        boolean failed=false;
        for (var invitation:list()) {
            if (!invitation.isUsable(clock.instant())) {
                try { grants.remove(invitation); }
                catch (RuntimeException unavailable) { failed=true; }
            }
        }
        if (failed) throw new IllegalStateException("Invitation authorization cleanup incomplete");
    }

    private static void requireOwnTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Invitation operations must own their transaction boundary");
        }
    }
}
