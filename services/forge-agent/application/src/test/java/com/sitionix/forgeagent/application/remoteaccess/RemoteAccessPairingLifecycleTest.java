package com.sitionix.forgeagent.application.remoteaccess;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RemoteAccessPairingLifecycleTest {
    static final Instant NOW=Instant.parse("2026-09-23T12:00:00Z");
    static final UUID GRANTOR=UUID.randomUUID(), ACCESSOR=UUID.randomUUID(), INVITATION=UUID.randomUUID(), SESSION=UUID.randomUUID();
    static final String FP="SHA256:"+"A".repeat(43), PAIR_FP="SHA256:"+"B".repeat(43);
    @Mock RemoteAccessInvitationRepository invitations;
    @Mock RemoteAccessSessionRepository sessions;
    @Mock ForgeInstanceIdentityRepository identity;
    @Mock RemoteAccessPairingTokens tokens;
    @Mock RemoteAccessInvitationGrants invitationGrants;
    @Mock RemoteAccessSessionGrants sessionGrants;
    @Mock RemoteAccessProvisioningService provisioning;
    @Mock RemoteAccessPairingTransport transport;
    @Mock RemoteAccessWorkloads workloads;

    @Test void reverseInvitationRequiresTheExactActiveForwardSessionKey() {
        var pairs=mock(RemoteAccessPairRepository.class);
        var accessor=mock(RemoteAccessAccessorPairing.class);
        when(identity.getOrCreate()).thenReturn(GRANTOR);
        when(sessions.findById(SESSION)).thenReturn(Optional.of(session(RemoteAccessRole.GRANTOR).activate(NOW)));
        var sut=new RemoteAccessGrantorPairing(invitations,sessions,identity,tokens,invitationGrants,sessionGrants,
                provisioning,workloads,RemoteAccessTestSwitch.enabled(),Clock.fixed(NOW,ZoneOffset.UTC),pairs,accessor);
        assertThat(sut.reverse(new RemoteAccessKeyBinding(GRANTOR,SESSION,PAIR_FP),
                new RemoteAccessReverseRequest(INVITATION,"internal-secret"))).isEmpty();
        verifyNoInteractions(tokens,pairs,accessor);
    }

    @Test void reverseInvitationLinksOnlyTheConfirmedReciprocalSession() {
        var pairs=mock(RemoteAccessPairRepository.class);
        var accessor=mock(RemoteAccessAccessorPairing.class);
        var forward=session(RemoteAccessRole.GRANTOR).activate(NOW);
        var reverseId=UUID.randomUUID();
        var reverse=new RemoteAccessSession(reverseId,UUID.randomUUID(),RemoteAccessRole.ACCESSOR,ACCESSOR,GRANTOR,
                "Peer",forward.endpoint(),"host-public","reverse-public",FP,reverseId,
                RemoteAccessSessionStatus.ACTIVE,NOW,NOW.plusSeconds(300),NOW,null,null,
                RemoteAccessConnectivity.UNKNOWN,null,null,null,null,1);
        var pair=RemoteAccessPair.inviter(INVITATION,SESSION,NOW);
        when(identity.getOrCreate()).thenReturn(GRANTOR);
        when(sessions.findById(SESSION)).thenReturn(Optional.of(forward));
        when(tokens.decode("internal-secret",GRANTOR)).thenReturn(new RemoteAccessPairingDetails(
                reverse.invitationId(),ACCESSOR,"Peer",forward.endpoint(),"host-public",
                new RemoteAccessPrivateKey(new byte[]{1}),NOW.plusSeconds(300)));
        when(accessor.connect("internal-secret","Forge Remote Access")).thenReturn(reverse);
        when(pairs.findById(INVITATION)).thenReturn(Optional.empty(),Optional.of(pair.withReverseSession(reverseId)));
        var sut=new RemoteAccessGrantorPairing(invitations,sessions,identity,tokens,invitationGrants,sessionGrants,
                provisioning,workloads,RemoteAccessTestSwitch.enabled(),Clock.fixed(NOW,ZoneOffset.UTC),pairs,accessor);
        assertThat(sut.reverse(keyBinding(),new RemoteAccessReverseRequest(INVITATION,"internal-secret"))).contains(reverseId);
        verify(pairs).insert(pair);
        verify(pairs).transition(pair,pair.withReverseSession(reverseId));
    }

    @Test void grantorCommitsReservationBeforeInstallingKeyAndDoesNotActivate() {
        var invitation=invitation();
        when(identity.getOrCreate()).thenReturn(GRANTOR);
        when(invitations.findById(INVITATION)).thenReturn(Optional.of(invitation));
        when(tokens.fingerprint("session-public")).thenReturn(FP);
        when(invitationGrants.hostPublicKey()).thenReturn("host-public");
        when(provisioning.reserveGrantorSession(any())).thenAnswer(call -> call.getArgument(0));
        assertThat(grantor(NOW).redeem(invBinding(),new RemoteAccessPairingRequest(SESSION,ACCESSOR,"Accessor","session-public"))).isEqualTo(SESSION);
        var captor=org.mockito.ArgumentCaptor.forClass(RemoteAccessSession.class);
        var order=inOrder(provisioning,sessionGrants,invitationGrants);
        order.verify(provisioning).reserveGrantorSession(captor.capture());
        order.verify(sessionGrants).install(captor.getValue());
        assertThat(captor.getValue().status()).isEqualTo(RemoteAccessSessionStatus.PROVISIONING);
        assertThat(captor.getValue().localPrivateKeyReference()).isNull();
        assertThat(captor.getValue().accessorInstanceId()).isEqualTo(ACCESSOR);
    }

    @Test void correctNewKeyProofActivatesAndRepeatedConfirmationIsIdempotent() {
        var before=session(RemoteAccessRole.GRANTOR);
        when(identity.getOrCreate()).thenReturn(GRANTOR);
        when(sessions.findById(SESSION)).thenReturn(Optional.of(before),Optional.of(before.activate(NOW)));
        when(sessions.transition(before,before.activate(NOW))).thenReturn(true);
        assertThat(grantor(NOW).confirm(keyBinding())).contains(RemoteAccessSessionStatus.ACTIVE);
        assertThat(grantor(NOW).confirm(keyBinding())).contains(RemoteAccessSessionStatus.ACTIVE);
        verify(sessions,times(1)).transition(any(),any());
        verifyNoInteractions(sessionGrants);
        verify(workloads).prepare(SESSION);
    }

    @Test void missingIsolatedWorkspaceDoesNotActivateGrantorSession() {
        var before=session(RemoteAccessRole.GRANTOR);
        when(identity.getOrCreate()).thenReturn(GRANTOR);
        when(sessions.findById(SESSION)).thenReturn(Optional.of(before));
        doThrow(new IllegalStateException("workspace unavailable")).when(workloads).prepare(SESSION);

        assertThatThrownBy(() -> grantor(NOW).confirm(keyBinding()))
                .isInstanceOf(IllegalStateException.class);
        verify(sessions, never()).transition(any(), any());
    }

    @Test void wrongKeyAndExpiredSessionNeverActivate() {
        when(identity.getOrCreate()).thenReturn(GRANTOR);
        when(sessions.findById(SESSION)).thenReturn(Optional.of(session(RemoteAccessRole.GRANTOR)));
        assertThat(grantor(NOW).confirm(new RemoteAccessKeyBinding(GRANTOR,SESSION,PAIR_FP))).isEmpty();
        assertThat(grantor(NOW.plusSeconds(300)).confirm(keyBinding())).isEmpty();
        verify(sessions,never()).transition(any(),any());
    }

    @Test void expiredGrantorCleanupConfirmsRevokedOnlyAfterRemoval() {
        var before=session(RemoteAccessRole.GRANTOR);
        var revoking=before.requestRevoke(NOW.plusSeconds(300));
        when(identity.getOrCreate()).thenReturn(GRANTOR);
        when(sessions.findLocal(GRANTOR)).thenReturn(List.of(before));
        when(sessions.transition(before,revoking)).thenReturn(true);
        when(sessions.findById(SESSION)).thenReturn(Optional.of(revoking));
        when(sessions.transition(eq(revoking),any())).thenReturn(true);
        grantor(NOW.plusSeconds(300)).reconcile();
        var order=inOrder(sessions,sessionGrants);
        order.verify(sessions).transition(before,revoking);
        order.verify(sessionGrants).remove(revoking);
        order.verify(sessions).transition(revoking,revoking.confirmRevoked(NOW.plusSeconds(300)));
    }

    @Test void restartRestoresUnexpiredGrantAndFailedCleanupNeverConfirmsRevoked() {
        var before=session(RemoteAccessRole.GRANTOR);
        when(identity.getOrCreate()).thenReturn(GRANTOR);
        when(sessions.findLocal(GRANTOR)).thenReturn(List.of(before));
        grantor(NOW).reconcile();
        verify(sessionGrants).install(before);
        var revoking=before.requestRevoke(NOW);
        when(sessions.findLocal(GRANTOR)).thenReturn(List.of(revoking));
        doThrow(new IllegalStateException("synthetic failure")).when(sessionGrants).remove(revoking);
        grantor(NOW).reconcile();
        verify(sessions,never()).transition(eq(revoking),any());
        verify(sessions).recordFailure(eq(revoking),eq("REMOTE_ACCESS_CLEANUP_PENDING"),anyString());
    }

    @Test void disablingNeverRestoresAnUnexpiredGrantDuringReconciliation() {
        var before=session(RemoteAccessRole.GRANTOR);
        var repository=mock(RemoteAccessSwitchRepository.class);
        when(repository.get()).thenReturn(new RemoteAccessSwitchState(RemoteAccessSwitchStatus.DISABLING,1));
        when(identity.getOrCreate()).thenReturn(GRANTOR);
        when(sessions.findLocal(GRANTOR)).thenReturn(List.of(before));
        var sut=new RemoteAccessGrantorPairing(invitations,sessions,identity,tokens,invitationGrants,
                sessionGrants,provisioning,workloads,new RemoteAccessSwitch(repository),Clock.fixed(NOW,ZoneOffset.UTC),null,null);

        sut.reconcile();

        verifyNoInteractions(sessionGrants);
        verify(sessions,never()).transition(any(),any());
    }

    @Test void lostActivationAcknowledgementResumesWithSameSessionKeyOnly() {
        var before=session(RemoteAccessRole.ACCESSOR);
        when(identity.getOrCreate()).thenReturn(ACCESSOR);
        when(sessions.findById(SESSION)).thenReturn(Optional.of(before));
        when(transport.confirm(before)).thenThrow(new IllegalStateException("lost response"));
        assertThat(accessor(NOW).resume(SESSION).status()).isEqualTo(RemoteAccessSessionStatus.PROVISIONING);
        verify(sessions).recordFailure(eq(before),eq("REMOTE_ACCESS_CONFIRMATION_UNAVAILABLE"),anyString());
        doReturn(RemoteAccessSessionStatus.ACTIVE).when(transport).confirm(before);
        when(sessions.transition(before,before.activate(NOW))).thenReturn(true);
        when(sessions.findById(SESSION)).thenReturn(Optional.of(before),Optional.of(before),Optional.of(before.activate(NOW)));
        assertThat(accessor(NOW).resume(SESSION).status()).isEqualTo(RemoteAccessSessionStatus.ACTIVE);
        verify(transport,never()).redeem(any(),any(),anyString());
        verifyNoInteractions(tokens,provisioning);
    }

    @Test void expiredAccessorRetainsUnconfirmedStateAndDoesNotClaimRemoteCleanup() {
        var before=session(RemoteAccessRole.ACCESSOR);
        var revoking=before.requestRevoke(NOW.plusSeconds(300));
        when(identity.getOrCreate()).thenReturn(ACCESSOR);
        when(sessions.findById(SESSION)).thenReturn(Optional.of(before),Optional.of(revoking),Optional.of(revoking));
        when(sessions.transition(before,revoking)).thenReturn(true);
        var actual=accessor(NOW.plusSeconds(300)).resume(SESSION);
        assertThat(actual.status()).isEqualTo(RemoteAccessSessionStatus.REVOKING);
        assertThat(actual.localPrivateKeyReference()).isEqualTo(SESSION);
        verifyNoInteractions(transport);
    }

    @Test void expiredSnapshotCannotMarkAConcurrentlyActivatedSessionAsExpired() {
        var before = session(RemoteAccessRole.ACCESSOR);
        var activated = before.activate(NOW);
        when(identity.getOrCreate()).thenReturn(ACCESSOR);
        when(sessions.findById(SESSION)).thenReturn(Optional.of(before), Optional.of(activated));
        when(sessions.transition(eq(before), any())).thenReturn(false);
        var actual = accessor(NOW.plusSeconds(300)).resume(SESSION);
        assertThat(actual).isEqualTo(activated);
        verify(sessions, never()).recordFailure(any(), any(), any());
        verifyNoInteractions(transport);
    }

    @Test void successfulConfirmationRemainsActiveWhenDeadlinePassesDuringRpc() {
        var before = session(RemoteAccessRole.ACCESSOR);
        Instant admittedAt = before.provisioningExpiresAt().minusMillis(100);
        var now = new java.util.concurrent.atomic.AtomicReference<>(admittedAt);
        var clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(call -> now.get());
        var stored = new java.util.concurrent.atomic.AtomicReference<>(before);
        when(identity.getOrCreate()).thenReturn(ACCESSOR);
        when(sessions.findById(SESSION)).thenAnswer(call -> Optional.of(stored.get()));
        when(transport.confirm(before)).thenAnswer(call -> {
            now.set(before.provisioningExpiresAt().plusMillis(200));
            return RemoteAccessSessionStatus.ACTIVE;
        });
        when(sessions.transition(any(), any())).thenAnswer(call ->
                stored.compareAndSet(call.getArgument(0), call.getArgument(1)));

        var sut = new RemoteAccessAccessorPairing(sessions, identity, tokens, provisioning, transport, RemoteAccessTestSwitch.enabled(), clock);
        var actual = sut.resume(SESSION);

        assertThat(now.get()).isAfter(before.provisioningExpiresAt());
        assertThat(actual).isEqualTo(before.activate(admittedAt));
        assertThat(stored.get()).isEqualTo(actual);
        verify(sessions).transition(before, before.activate(admittedAt));
        verify(sessions, times(1)).transition(any(), any());
        verify(sessions, never()).recordFailure(any(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = RemoteAccessSessionStatus.class, names = {"ACTIVE", "REVOKING", "REVOKED"})
    void successfulConfirmationRespectsStateChangedDuringRpc(RemoteAccessSessionStatus status) {
        var before = session(RemoteAccessRole.ACCESSOR);
        var winner = confirmationWinner(before, status);
        when(identity.getOrCreate()).thenReturn(ACCESSOR);
        when(sessions.findById(SESSION)).thenReturn(Optional.of(before), Optional.of(winner));
        when(transport.confirm(before)).thenReturn(RemoteAccessSessionStatus.ACTIVE);

        assertThat(accessor(NOW).resume(SESSION)).isEqualTo(winner);
        verify(sessions, never()).transition(any(), any());
        verify(sessions, never()).recordFailure(any(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = RemoteAccessSessionStatus.class, names = {"ACTIVE", "REVOKING", "REVOKED"})
    void successfulConfirmationReloadsWinnerAfterLostActivationCas(RemoteAccessSessionStatus status) {
        var before = session(RemoteAccessRole.ACCESSOR);
        var winner = confirmationWinner(before, status);
        when(identity.getOrCreate()).thenReturn(ACCESSOR);
        when(sessions.findById(SESSION)).thenReturn(Optional.of(before), Optional.of(before), Optional.of(winner));
        when(transport.confirm(before)).thenReturn(RemoteAccessSessionStatus.ACTIVE);
        when(sessions.transition(before, before.activate(NOW))).thenReturn(false);

        assertThat(accessor(NOW).resume(SESSION)).isEqualTo(winner);
        verify(sessions).transition(before, before.activate(NOW));
        verify(sessions, times(1)).transition(any(), any());
        verify(sessions, never()).recordFailure(any(), any(), any());
    }

    private RemoteAccessSession confirmationWinner(RemoteAccessSession before, RemoteAccessSessionStatus status) {
        return switch (status) {
            case ACTIVE -> before.activate(NOW);
            case REVOKING -> before.requestRevoke(NOW);
            case REVOKED -> before.requestRevoke(NOW).confirmRevoked(NOW);
            default -> throw new IllegalArgumentException("Expected a concurrent lifecycle winner");
        };
    }

    @Test void accessorPersistsDedicatedAttemptBeforeSendingAnyPairingMaterial() {
        when(identity.getOrCreate()).thenReturn(ACCESSOR);
        var invitationKey = new RemoteAccessPrivateKey(new byte[]{1, 2, 3});
        var sessionKey = new RemoteAccessPrivateKey(new byte[]{4, 5, 6});
        var details = new RemoteAccessPairingDetails(INVITATION, GRANTOR, "Grantor", invitation().endpoint(),
                "host-public", invitationKey, NOW.plusSeconds(300));
        when(tokens.decode("synthetic-token", ACCESSOR)).thenReturn(details);
        when(sessions.findByInvitation(INVITATION)).thenReturn(Optional.empty());
        when(tokens.generate()).thenReturn(new RemoteAccessPairingKeys("session-public", FP, sessionKey));
        var persisted = new java.util.concurrent.atomic.AtomicReference<RemoteAccessSession>();
        when(provisioning.createAccessorSession(any(), same(sessionKey))).thenAnswer(call -> {
            RemoteAccessSession candidate = call.getArgument(0);
            persisted.set(candidate);
            return candidate;
        });
        doAnswer(call -> {
            assertThat(persisted.get()).isEqualTo(call.getArgument(0));
            assertThat(persisted.get().status()).isEqualTo(RemoteAccessSessionStatus.PROVISIONING);
            assertThat(persisted.get().localPrivateKeyReference()).isEqualTo(persisted.get().id());
            assertThat(persisted.get().accessorInstanceId()).isEqualTo(ACCESSOR);
            return null;
        }).when(transport).redeem(any(), same(invitationKey), eq("Accessor"));
        when(sessions.findById(any())).thenAnswer(call -> Optional.of(persisted.get()));
        when(transport.confirm(any())).thenReturn(RemoteAccessSessionStatus.ACTIVE);
        when(sessions.transition(any(), any())).thenAnswer(call -> {
            persisted.set(call.getArgument(1));
            return true;
        });
        assertThat(accessor(NOW).connect("synthetic-token", "Accessor").status()).isEqualTo(RemoteAccessSessionStatus.ACTIVE);
        var order = inOrder(provisioning, transport);
        order.verify(provisioning).createAccessorSession(any(), same(sessionKey));
        order.verify(transport).redeem(any(), same(invitationKey), eq("Accessor"));
        order.verify(transport).confirm(any());
    }

    @Test void reconnectReusesPersistedAttemptWithoutGeneratingKeyOrRedeemingTokenAgain() {
        var before = session(RemoteAccessRole.ACCESSOR);
        when(identity.getOrCreate()).thenReturn(ACCESSOR);
        var details = new RemoteAccessPairingDetails(INVITATION, GRANTOR, "Peer", before.endpoint(),
                before.pinnedHostPublicKey(), new RemoteAccessPrivateKey(new byte[]{1}), NOW.plusSeconds(300));
        when(tokens.decode("synthetic-token", ACCESSOR)).thenReturn(details);
        when(sessions.findByInvitation(INVITATION)).thenReturn(Optional.of(before));
        when(sessions.findById(SESSION)).thenReturn(Optional.of(before));
        when(transport.confirm(before)).thenThrow(new IllegalStateException("offline"));
        assertThat(accessor(NOW).connect("synthetic-token", "Accessor").id()).isEqualTo(SESSION);
        verify(tokens, never()).generate();
        verify(transport, never()).redeem(any(), any(), anyString());
        verifyNoInteractions(provisioning);
    }

    private RemoteAccessGrantorPairing grantor(Instant now) {
        return new RemoteAccessGrantorPairing(invitations,sessions,identity,tokens,invitationGrants,sessionGrants,provisioning,workloads,RemoteAccessTestSwitch.enabled(),Clock.fixed(now,ZoneOffset.UTC),null,null);
    }
    private RemoteAccessAccessorPairing accessor(Instant now) {
        return new RemoteAccessAccessorPairing(sessions,identity,tokens,provisioning,transport,RemoteAccessTestSwitch.enabled(),Clock.fixed(now,ZoneOffset.UTC));
    }
    private RemoteAccessInvitationBinding invBinding() {return new RemoteAccessInvitationBinding(GRANTOR,INVITATION,PAIR_FP);}
    private RemoteAccessKeyBinding keyBinding() {return new RemoteAccessKeyBinding(GRANTOR,SESSION,FP);}
    private RemoteAccessInvitation invitation() {
        return new RemoteAccessInvitation(INVITATION,GRANTOR,new RemoteAccessEndpoint("192.0.2.20",2222,"forge-ssh"),"pair-public",PAIR_FP,NOW,NOW.plusSeconds(300),null,null,null);
    }
    private RemoteAccessSession session(RemoteAccessRole role) {
        return new RemoteAccessSession(SESSION,INVITATION,role,GRANTOR,ACCESSOR,"Peer",invitation().endpoint(),"host-public","session-public",FP,
                role==RemoteAccessRole.ACCESSOR?SESSION:null,RemoteAccessSessionStatus.PROVISIONING,NOW,NOW.plusSeconds(300),null,null,null,
                RemoteAccessConnectivity.UNKNOWN,null,null,null,null,0);
    }
}
