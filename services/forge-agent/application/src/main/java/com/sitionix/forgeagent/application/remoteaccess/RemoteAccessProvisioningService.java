package com.sitionix.forgeagent.application.remoteaccess;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Stage 1 persistence only. These operations install no SSH authorization and perform no remote calls. */
@Service
public class RemoteAccessProvisioningService {
    private final RemoteAccessInvitationRepository invitations;
    private final RemoteAccessSessionRepository sessions;
    private final ForgeInstanceIdentityRepository identity;
    private final RemoteAccessCredentialStore credentials;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public RemoteAccessProvisioningService(RemoteAccessInvitationRepository invitations,
            RemoteAccessSessionRepository sessions, ForgeInstanceIdentityRepository identity,
            RemoteAccessCredentialStore credentials, Clock clock, PlatformTransactionManager transactionManager) {
        this.invitations = invitations;
        this.sessions = sessions;
        this.identity = identity;
        this.credentials = credentials;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public RemoteAccessSession reserveGrantorSession(RemoteAccessSession candidate) {
        requireNewLocalSession(candidate, RemoteAccessRole.GRANTOR);
        return transactions.execute(transaction -> {
            Instant now = clock.instant();
            RemoteAccessInvitation invitation = invitations.findById(candidate.invitationId())
                    .orElseThrow(() -> new NotFoundException("REMOTE_ACCESS_INVITATION_NOT_FOUND", "Invitation not found"));
            if (!invitation.grantorInstanceId().equals(candidate.grantorInstanceId())
                    || !invitation.endpoint().equals(candidate.endpoint()) || !invitation.isUsable(now)) {
                throw unavailableInvitation();
            }
            if (!invitations.reserve(invitation.id(), candidate.id(), now)) {
                throw unavailableInvitation();
            }
            // The deferred redemption FK and session INSERT commit together.
            sessions.insert(candidate);
            return candidate;
        });
    }

    public RemoteAccessSession createAccessorSession(RemoteAccessSession candidate, RemoteAccessPrivateKey privateKey) {
        requireNewLocalSession(candidate, RemoteAccessRole.ACCESSOR);
        if (!candidate.id().equals(candidate.localPrivateKeyReference())) {
            throw new IllegalArgumentException("Accessor credential reference must belong to this session");
        }
        Objects.requireNonNull(privateKey, "privateKey");
        // Exclusive store must succeed before we own anything eligible for compensation.
        UUID reference = credentials.store(candidate.id(), privateKey);
        try {
            if (!candidate.localPrivateKeyReference().equals(reference)) {
                throw new IllegalStateException("Credential store returned a foreign reference");
            }
            return transactions.execute(transaction -> {
                sessions.insert(candidate);
                return candidate;
            });
        } catch (RuntimeException failure) {
            try {
                credentials.delete(candidate.id());
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private void requireNewLocalSession(RemoteAccessSession session, RemoteAccessRole role) {
        // A joined transaction would commit after this method's key compensation boundary.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Remote access provisioning must own its transaction boundary");
        }
        Objects.requireNonNull(session, "session");
        Instant now = clock.instant();
        if (session.localRole() != role || session.status() != RemoteAccessSessionStatus.PROVISIONING
                || session.version() != 0 || session.createdAt().isAfter(now)
                || !session.provisioningExpiresAt().isAfter(now)) {
            throw new IllegalArgumentException("A new, unexpired provisioning session is required");
        }
        UUID local = identity.getOrCreate();
        if (!(role == RemoteAccessRole.GRANTOR ? session.grantorInstanceId() : session.accessorInstanceId()).equals(local)) {
            throw new IllegalArgumentException("Session does not belong to this Forge instance");
        }
    }

    private static ConflictException unavailableInvitation() {
        return new ConflictException("REMOTE_ACCESS_INVITATION_UNAVAILABLE", "Invitation cannot be redeemed");
    }
}
