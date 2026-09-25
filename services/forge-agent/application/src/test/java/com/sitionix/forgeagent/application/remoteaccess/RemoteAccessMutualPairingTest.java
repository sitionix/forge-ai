package com.sitionix.forgeagent.application.remoteaccess;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RemoteAccessMutualPairingTest {
    private static final UUID LOCAL=UUID.randomUUID(), REMOTE=UUID.randomUUID(), HUMAN_INVITE=UUID.randomUUID(),
            REVERSE_INVITE=UUID.randomUUID(), FORWARD=UUID.randomUUID(), REVERSE=UUID.randomUUID();
    private static final Instant NOW=Instant.parse("2026-09-24T12:00:00Z");
    private static final RemoteAccessEndpoint ENDPOINT=new RemoteAccessEndpoint("10.0.0.2",22,"forge-ssh");

    @Test void oneHumanTokenRequiresBothAuthenticatedDirectionsAndRetriesTheSameInternalInvitation() {
        var accessor=mock(RemoteAccessAccessorPairing.class);
        var invitations=mock(RemoteAccessInvitations.class);
        var setup=mock(RemoteAccessSetup.class);
        var tokens=mock(RemoteAccessPairingTokens.class);
        var identity=mock(ForgeInstanceIdentityRepository.class);
        var sessions=mock(RemoteAccessSessionRepository.class);
        var secrets=mock(RemoteAccessReverseInvitationStore.class);
        var exchange=mock(RemoteAccessReverseExchange.class);
        var management=mock(RemoteAccessManagement.class);
        var pairs=new MemoryPairs();
        var sut=new RemoteAccessMutualPairing(accessor,invitations,setup,tokens,identity,pairs,sessions,secrets,exchange,Clock.fixed(NOW,ZoneOffset.UTC),management);
        var forward=session(FORWARD,HUMAN_INVITE,RemoteAccessRole.ACCESSOR,REMOTE,LOCAL);
        var reverse=session(REVERSE,REVERSE_INVITE,RemoteAccessRole.GRANTOR,LOCAL,REMOTE);
        var reverseInvitation=new RemoteAccessInvitation(REVERSE_INVITE,LOCAL,ENDPOINT,"key","fingerprint",NOW,NOW.plusSeconds(300),null,null,null);
        when(identity.getOrCreate()).thenReturn(LOCAL);
        when(tokens.decode("one-human-token",LOCAL)).thenAnswer(ignored -> new RemoteAccessPairingDetails(
                HUMAN_INVITE,REMOTE,"Remote",ENDPOINT,"host",new RemoteAccessPrivateKey(new byte[]{1}),NOW.plusSeconds(300)));
        when(setup.advertisedEndpointForPeer(ENDPOINT)).thenReturn(ENDPOINT);
        when(setup.displayName()).thenReturn("Local");
        when(invitations.create(ENDPOINT,"Local")).thenReturn(new RemoteAccessInvitationCreated(reverseInvitation,new RemoteAccessPairingToken("internal-secret")));
        when(invitations.get(REVERSE_INVITE)).thenReturn(reverseInvitation);
        when(accessor.connect("one-human-token","Local")).thenReturn(forward);
        when(accessor.resume(FORWARD)).thenReturn(forward);
        when(secrets.read(HUMAN_INVITE)).thenReturn(new RemoteAccessPairingToken("internal-secret"));
        when(exchange.exchange(forward,"internal-secret")).thenThrow(new IllegalStateException("lost reply")).thenReturn(REVERSE);
        when(sessions.findById(FORWARD)).thenReturn(Optional.of(forward));
        when(sessions.findById(REVERSE)).thenReturn(Optional.of(reverse));

        assertThat(sut.connect("one-human-token","Local").status()).isEqualTo(RemoteAccessSessionStatus.ACTIVE);
        assertThat(sut.connected(FORWARD)).isFalse();
        assertThat(pairs.findById(HUMAN_INVITE).orElseThrow().reverseInvitationId()).isEqualTo(REVERSE_INVITE);
        verify(secrets,never()).delete(HUMAN_INVITE);

        sut.reconcile();
        assertThat(sut.connected(FORWARD)).isTrue();
        assertThat(sut.connected(REVERSE)).isTrue();
        verify(invitations,times(1)).create(ENDPOINT,"Local");
        verify(secrets,times(1)).delete(HUMAN_INVITE);
        verify(exchange,times(2)).exchange(forward,"internal-secret");
    }

    private static RemoteAccessSession session(UUID id,UUID invitation,RemoteAccessRole role,UUID grantor,UUID accessor) {
        return new RemoteAccessSession(id,invitation,role,grantor,accessor,"peer",ENDPOINT,"host","public","fingerprint",
                role==RemoteAccessRole.ACCESSOR?id:null,RemoteAccessSessionStatus.ACTIVE,NOW.minusSeconds(30),NOW.plusSeconds(270),
                NOW,null,null,RemoteAccessConnectivity.UNKNOWN,null,null,null,null,1);
    }

    private static final class MemoryPairs implements RemoteAccessPairRepository {
        private final Map<UUID,RemoteAccessPair> values=new HashMap<>();
        public void insert(RemoteAccessPair pair) { if (values.putIfAbsent(pair.id(),pair)!=null) throw new IllegalStateException(); }
        public Optional<RemoteAccessPair> findById(UUID id) { return Optional.ofNullable(values.get(id)); }
        public Optional<RemoteAccessPair> findBySession(UUID id) {
            return values.values().stream().filter(value -> id.equals(value.forwardSessionId()) || id.equals(value.reverseSessionId())).findFirst();
        }
        public boolean isReverseInvitation(UUID id) { return values.values().stream().anyMatch(value -> id.equals(value.reverseInvitationId())); }
        public List<RemoteAccessPair> findConnectorPairs() { return values.values().stream().filter(value -> value.localForwardRole()==RemoteAccessRole.ACCESSOR).toList(); }
        public boolean transition(RemoteAccessPair before,RemoteAccessPair after) { return values.replace(before.id(),before,after); }
    }
}
