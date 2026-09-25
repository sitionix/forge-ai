package com.sitionix.forgeagent.application.remoteaccess;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RemoteAccessManagementTest {
    static final UUID LOCAL=UUID.randomUUID(), ID=UUID.randomUUID();
    static final Instant NOW=Instant.parse("2026-09-23T12:00:00Z");
    @Mock RemoteAccessSessionRepository sessions;
    @Mock ForgeInstanceIdentityRepository identity;
    @Mock RemoteAccessPairingTransport transport;
    @Mock RemoteAccessPeerExecution grantor;
    @Mock RemoteAccessAccessorExecution accessor;
    @Mock RemoteAccessPairRepository pairs;
    @Mock RemoteAccessInvitations invitations;
    RemoteAccessManagement sut;
    @BeforeEach void setup() {
        when(identity.getOrCreate()).thenReturn(LOCAL);
        sut=new RemoteAccessManagement(sessions,identity,transport,grantor,accessor,Clock.fixed(NOW,ZoneOffset.UTC),pairs,invitations);
    }
    @Test void readsNeverOpenSsh() {
        var s=session(RemoteAccessRole.ACCESSOR);
        when(sessions.findById(ID)).thenReturn(Optional.of(s));
        when(sessions.findLocal(LOCAL)).thenReturn(List.of(s));
        assertThat(sut.get(ID)).isEqualTo(s);
        assertThat(sut.list()).containsExactly(s);
        verifyNoInteractions(transport,grantor,accessor);
    }
    @Test void foreignSessionIsNotFound() {
        when(identity.getOrCreate()).thenReturn(UUID.randomUUID());
        when(sessions.findById(ID)).thenReturn(Optional.of(session(RemoteAccessRole.ACCESSOR)));
        assertThatThrownBy(() -> sut.get(ID)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(transport);
    }
    @Test void authenticatedCheckObservesButDoesNotAuthorize() {
        var s=session(RemoteAccessRole.ACCESSOR).withFailure("PENDING","Retain diagnostic");
        var observed=s.observe(RemoteAccessConnectivity.REACHABLE,NOW);
        when(sessions.findById(ID)).thenReturn(Optional.of(s),Optional.of(observed));
        when(transport.status(s)).thenReturn(RemoteAccessSessionStatus.REVOKED);
        assertThat(sut.check(ID)).isEqualTo(observed);
        assertThat(observed.status()).isEqualTo(s.status());
        assertThat(observed.failureCode()).isEqualTo("PENDING");
        assertThat(observed.lastSeenAt()).isEqualTo(NOW);
        verify(sessions).recordObservation(s,RemoteAccessConnectivity.REACHABLE,NOW);
        verify(sessions,never()).transition(any(),any());
    }
    @Test void unavailableCheckKeepsAuthorizationAndReloadsCasWinner() {
        var s=session(RemoteAccessRole.ACCESSOR);
        var winner=s.requestRevoke(NOW).withFailure("NEW","New writer");
        when(sessions.findById(ID)).thenReturn(Optional.of(s),Optional.of(winner));
        when(transport.status(s)).thenThrow(new IllegalStateException("synthetic-secret"));
        assertThat(sut.check(ID)).isEqualTo(winner);
        verify(sessions).recordObservation(s,RemoteAccessConnectivity.UNREACHABLE,NOW);
        verify(sessions,never()).recordFailure(any(),any(),any());
    }
    @Test void grantorCheckNeverIntroducesReverseAccess() {
        var s=session(RemoteAccessRole.GRANTOR);
        when(sessions.findById(ID)).thenReturn(Optional.of(s));
        sut.check(ID);
        verify(sessions).recordObservation(s,RemoteAccessConnectivity.UNKNOWN,NOW);
        verifyNoInteractions(transport);
    }
    @Test void revokeDelegatesByLocalRole() {
        var s=session(RemoteAccessRole.ACCESSOR);
        when(sessions.findById(ID)).thenReturn(Optional.of(s));
        when(accessor.revoke(ID)).thenReturn(s.requestRevoke(NOW));
        assertThat(sut.revoke(ID).status()).isEqualTo(RemoteAccessSessionStatus.REVOKING);
        verifyNoInteractions(grantor);
    }
    @Test void grantorRevokeReturnsConfirmedPersistedResult() {
        var s=session(RemoteAccessRole.GRANTOR);
        var revoked=s.requestRevoke(NOW).confirmRevokedAndClearFailure(NOW);
        when(sessions.findById(ID)).thenReturn(Optional.of(s),Optional.of(s),Optional.of(revoked));
        when(grantor.revoke(new RemoteAccessKeyBinding(LOCAL,ID,s.sessionFingerprint()))).thenReturn(RemoteAccessSessionStatus.REVOKED);
        assertThat(sut.revoke(ID)).isEqualTo(revoked);
        verifyNoInteractions(accessor);
    }
    @Test void disconnectFindsAndRevokesReverseGrantEvenWhenLinkReplyWasLost() {
        UUID invite=UUID.randomUUID(),reverseId=UUID.randomUUID();
        var forward=session(RemoteAccessRole.ACCESSOR);
        var reverse=new RemoteAccessSession(reverseId,invite,RemoteAccessRole.GRANTOR,LOCAL,UUID.randomUUID(),"peer",
                forward.endpoint(),"host","public",forward.sessionFingerprint(),null,RemoteAccessSessionStatus.ACTIVE,
                NOW.minusSeconds(60),NOW.plusSeconds(240),NOW.minusSeconds(30),null,null,
                RemoteAccessConnectivity.UNKNOWN,null,null,null,null,1);
        var pair=RemoteAccessPair.connector(UUID.randomUUID(),invite,NOW).withForwardSession(ID);
        when(pairs.findBySession(ID)).thenReturn(Optional.of(pair));
        when(sessions.findByInvitation(invite)).thenReturn(Optional.of(reverse));
        when(sessions.findById(ID)).thenReturn(Optional.of(forward));
        when(sessions.findById(reverseId)).thenReturn(Optional.of(reverse));
        when(accessor.revoke(ID)).thenReturn(forward.requestRevoke(NOW));
        sut.revoke(ID);
        verify(grantor).revoke(new RemoteAccessKeyBinding(LOCAL,reverseId,reverse.sessionFingerprint()));
        verify(invitations).cancel(invite);
    }
    @Test void disconnectFindsPairBeforeForwardSessionLinkWasPersisted() {
        UUID reverseInvite=UUID.randomUUID();
        var forward=session(RemoteAccessRole.ACCESSOR);
        var pair=RemoteAccessPair.connector(forward.invitationId(),reverseInvite,NOW);
        when(sessions.findById(ID)).thenReturn(Optional.of(forward));
        when(pairs.findById(forward.invitationId())).thenReturn(Optional.of(pair));
        when(accessor.revoke(ID)).thenReturn(forward.requestRevoke(NOW));

        sut.revoke(ID);

        verify(accessor).revoke(ID);
        verify(invitations).cancel(reverseInvite);
    }
    static RemoteAccessSession session(RemoteAccessRole role) {
        return new RemoteAccessSession(ID,UUID.randomUUID(),role,
            role==RemoteAccessRole.GRANTOR?LOCAL:UUID.randomUUID(),role==RemoteAccessRole.ACCESSOR?LOCAL:UUID.randomUUID(),
            "peer",new RemoteAccessEndpoint("127.0.0.1",2222,"forge-ssh"),"host","public","SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            role==RemoteAccessRole.ACCESSOR?UUID.randomUUID():null,RemoteAccessSessionStatus.ACTIVE,
            NOW.minusSeconds(60),NOW.plusSeconds(240),NOW.minusSeconds(30),null,null,
            RemoteAccessConnectivity.UNKNOWN,null,null,null,null,1);
    }
}
