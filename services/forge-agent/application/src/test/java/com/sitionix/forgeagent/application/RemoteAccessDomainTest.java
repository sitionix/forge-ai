package com.sitionix.forgeagent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sitionix.forgeagent.domain.model.RemoteAccessConnectivity;
import com.sitionix.forgeagent.domain.model.RemoteAccessEndpoint;
import com.sitionix.forgeagent.domain.model.RemoteAccessInvitation;
import com.sitionix.forgeagent.domain.model.RemoteAccessRole;
import com.sitionix.forgeagent.domain.model.RemoteAccessSession;
import com.sitionix.forgeagent.domain.model.RemoteAccessSessionStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RemoteAccessDomainTest {
    private static final Instant CREATED = Instant.parse("2026-09-22T10:00:00Z");
    private static final Instant DEADLINE = CREATED.plusSeconds(300);
    private static final UUID GRANTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACCESSOR = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID KEY_REFERENCE = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void endpointRejectsMissingMetadataAndInvalidPorts() {
        assertThatThrownBy(() -> new RemoteAccessEndpoint(" ", 2222, "forge-ssh"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RemoteAccessEndpoint("grantor.local", 0, "forge-ssh"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RemoteAccessEndpoint("grantor.local", 65536, "forge-ssh"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RemoteAccessEndpoint("grantor.local", 2222, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invitationUsesExclusiveExpiryDeadlineAndOnlyRedeemsOnce() {
        var invitation = invitation();
        assertThat(invitation.isUsable(DEADLINE.minusNanos(1))).isTrue();
        assertThat(invitation.isUsable(DEADLINE)).isFalse();

        var sessionId = UUID.randomUUID();
        var redeemed = invitation.redeem(sessionId, DEADLINE.minusSeconds(1));
        assertThat(redeemed.consumedAt()).isEqualTo(DEADLINE.minusSeconds(1));
        assertThat(redeemed.redeemedSessionId()).isEqualTo(sessionId);
        assertThat(redeemed.isUsable(DEADLINE.minusSeconds(2))).isFalse();
        assertThatThrownBy(() -> redeemed.redeem(UUID.randomUUID(), DEADLINE.minusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invitationCancellationIsTerminalAndCannotFollowRedemption() {
        var cancelled = invitation().cancel(CREATED.plusSeconds(10));
        assertThat(cancelled.cancelledAt()).isEqualTo(CREATED.plusSeconds(10));
        assertThat(cancelled.isUsable(CREATED.plusSeconds(11))).isFalse();
        assertThat(cancelled.cancel(CREATED.plusSeconds(12))).isSameAs(cancelled);
        assertThat(invitation().cancel(DEADLINE.plusSeconds(1)).cancelledAt())
                .isEqualTo(DEADLINE.plusSeconds(1));
        assertThatThrownBy(() -> invitation().redeem(UUID.randomUUID(), CREATED.plusSeconds(1))
                .cancel(CREATED.plusSeconds(2))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invitationRejectsInvalidMetadataAndTimeOrdering() {
        assertThatThrownBy(() -> new RemoteAccessInvitation(UUID.randomUUID(), GRANTOR, endpoint(), " ",
                "pair-fingerprint", CREATED, DEADLINE, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RemoteAccessInvitation(UUID.randomUUID(), GRANTOR, endpoint(), "pair-key",
                "pair-fingerprint", CREATED, CREATED, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RemoteAccessInvitation(UUID.randomUUID(), GRANTOR, endpoint(), "pair-key",
                "pair-fingerprint", CREATED, DEADLINE, CREATED.plusSeconds(1), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> invitation().redeem(UUID.randomUUID(), CREATED.minusNanos(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sessionDoesNotImplicitlyBecomeActiveAndActivationIncrementsVersion() {
        var session = accessorSession(RemoteAccessSessionStatus.PROVISIONING, null, null, null, KEY_REFERENCE, 7);
        assertThat(session.status()).isEqualTo(RemoteAccessSessionStatus.PROVISIONING);
        assertThat(session.activatedAt()).isNull();

        var active = session.activate(CREATED.plusSeconds(30));
        assertThat(active.status()).isEqualTo(RemoteAccessSessionStatus.ACTIVE);
        assertThat(active.activatedAt()).isEqualTo(CREATED.plusSeconds(30));
        assertThat(active.version()).isEqualTo(8);
        assertThatThrownBy(() -> active.activate(CREATED.plusSeconds(31)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sessionCannotActivateAtProvisioningDeadlineOrAfterRevocationStarted() {
        assertThatThrownBy(() -> accessorSession(RemoteAccessSessionStatus.PROVISIONING, null, null, null,
                KEY_REFERENCE, 0).activate(DEADLINE)).isInstanceOf(IllegalStateException.class);
        var revoking = accessorSession(RemoteAccessSessionStatus.PROVISIONING, null, null, null,
                KEY_REFERENCE, 0).requestRevoke(CREATED.plusSeconds(1));
        assertThatThrownBy(() -> revoking.activate(CREATED.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void revokeTransitionsAreVersionedIdempotentAndClearAccessorKeyReferenceOnConfirmation() {
        var session = accessorSession(RemoteAccessSessionStatus.ACTIVE, CREATED.plusSeconds(1), null, null,
                KEY_REFERENCE, 11);
        var revoking = session.requestRevoke(CREATED.plusSeconds(2));
        assertThat(revoking.status()).isEqualTo(RemoteAccessSessionStatus.REVOKING);
        assertThat(revoking.revokeRequestedAt()).isEqualTo(CREATED.plusSeconds(2));
        assertThat(revoking.version()).isEqualTo(12);
        assertThat(revoking.requestRevoke(CREATED.plusSeconds(3))).isSameAs(revoking);

        var revoked = revoking.confirmRevoked(CREATED.plusSeconds(4));
        assertThat(revoked.status()).isEqualTo(RemoteAccessSessionStatus.REVOKED);
        assertThat(revoked.revokedAt()).isEqualTo(CREATED.plusSeconds(4));
        assertThat(revoked.localPrivateKeyReference()).isNull();
        assertThat(revoked.version()).isEqualTo(13);
        assertThat(revoked.confirmRevoked(CREATED.plusSeconds(5))).isSameAs(revoked);
        assertThatThrownBy(() -> revoked.activate(CREATED.plusSeconds(6)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void grantorNeverHasLocalPrivateKeyAndAccessorNeedsOneUntilRevoked() {
        assertThatThrownBy(() -> session(RemoteAccessRole.GRANTOR, RemoteAccessSessionStatus.PROVISIONING,
                null, null, null, KEY_REFERENCE, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> session(RemoteAccessRole.ACCESSOR, RemoteAccessSessionStatus.ACTIVE,
                CREATED.plusSeconds(1), null, null, null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(session(RemoteAccessRole.ACCESSOR, RemoteAccessSessionStatus.REVOKED,
                CREATED.plusSeconds(1), CREATED.plusSeconds(2), CREATED.plusSeconds(3), null, 4)
                .localPrivateKeyReference()).isNull();
    }

    @Test
    void sessionRejectsContradictoryLifecycleMetadataAndInvalidObservations() {
        assertThatThrownBy(() -> new RemoteAccessSession(UUID.randomUUID(), UUID.randomUUID(),
                RemoteAccessRole.GRANTOR, GRANTOR, GRANTOR, "peer", endpoint(), "host-key", "session-key",
                "fingerprint", null, RemoteAccessSessionStatus.PROVISIONING, CREATED, DEADLINE,
                null, null, null, RemoteAccessConnectivity.UNKNOWN, null, null, null, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> session(RemoteAccessRole.ACCESSOR, RemoteAccessSessionStatus.PROVISIONING,
                CREATED.plusSeconds(1), null, null, KEY_REFERENCE, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> session(RemoteAccessRole.ACCESSOR, RemoteAccessSessionStatus.REVOKED,
                CREATED.plusSeconds(1), CREATED.plusSeconds(3), CREATED.plusSeconds(2), null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RemoteAccessSession(UUID.randomUUID(), UUID.randomUUID(),
                RemoteAccessRole.ACCESSOR, GRANTOR, ACCESSOR, "peer", endpoint(), "host-key", "session-key",
                "fingerprint", KEY_REFERENCE, RemoteAccessSessionStatus.PROVISIONING, CREATED, DEADLINE,
                null, null, null, RemoteAccessConnectivity.REACHABLE, CREATED.plusSeconds(2),
                CREATED.plusSeconds(1), null, null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RemoteAccessSession(UUID.randomUUID(), UUID.randomUUID(),
                RemoteAccessRole.ACCESSOR, GRANTOR, ACCESSOR, " ", endpoint(), "host-key", "session-key",
                "fingerprint", KEY_REFERENCE, RemoteAccessSessionStatus.PROVISIONING, CREATED, DEADLINE,
                null, null, null, RemoteAccessConnectivity.UNKNOWN, null, null, null, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static RemoteAccessInvitation invitation() {
        return new RemoteAccessInvitation(UUID.randomUUID(), GRANTOR, endpoint(), "pair-key", "pair-fingerprint",
                CREATED, DEADLINE, null, null, null);
    }

    private static RemoteAccessSession accessorSession(RemoteAccessSessionStatus status, Instant activatedAt,
            Instant revokeRequestedAt, Instant revokedAt, UUID keyReference, long version) {
        return session(RemoteAccessRole.ACCESSOR, status, activatedAt, revokeRequestedAt, revokedAt,
                keyReference, version);
    }

    private static RemoteAccessSession session(RemoteAccessRole role, RemoteAccessSessionStatus status,
            Instant activatedAt, Instant revokeRequestedAt, Instant revokedAt, UUID keyReference, long version) {
        return new RemoteAccessSession(UUID.randomUUID(), UUID.randomUUID(), role, GRANTOR, ACCESSOR, "peer",
                endpoint(), "host-key", "session-key", "fingerprint", keyReference, status, CREATED, DEADLINE,
                activatedAt, revokeRequestedAt, revokedAt, RemoteAccessConnectivity.UNKNOWN, null, null,
                null, null, version);
    }

    private static RemoteAccessEndpoint endpoint() {
        return new RemoteAccessEndpoint("grantor.local", 2222, "forge-ssh");
    }
}
