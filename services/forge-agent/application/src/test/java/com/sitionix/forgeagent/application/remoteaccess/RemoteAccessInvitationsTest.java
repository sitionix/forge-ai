package com.sitionix.forgeagent.application.remoteaccess;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class RemoteAccessInvitationsTest {
    static final Instant NOW = Instant.parse("2026-09-23T10:00:00Z");
    static final UUID LOCAL = UUID.randomUUID();
    final RemoteAccessEndpoint endpoint = new RemoteAccessEndpoint("192.0.2.10",2222,"forge-ssh");
    @Mock RemoteAccessInvitationRepository invitations;
    @Mock ForgeInstanceIdentityRepository identity;
    @Mock RemoteAccessPairingTokens tokens;
    @Mock RemoteAccessInvitationGrants grants;
    @Mock PlatformTransactionManager transactions;

    @Test void emitsTokenOnlyAfterPersistAndGrantAndDestroysEphemeralKey() {
        var keys = setupCreation();
        when(tokens.encode(any(),eq("Grantor"),eq("host-public"),eq(keys.privateKey())))
                .thenReturn(new RemoteAccessPairingToken("synthetic-bearer"));
        var result = service().create(endpoint,"Grantor");
        assertThat(result.invitation().expiresAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(result.invitation().grantorInstanceId()).isEqualTo(LOCAL);
        assertThat(result.token().value()).isEqualTo("synthetic-bearer");
        assertThat(result.toString()).doesNotContain("synthetic-bearer");
        assertThatThrownBy(keys.privateKey()::copyBytes).isInstanceOf(IllegalStateException.class);
        var order = inOrder(invitations,transactions,grants,tokens);
        order.verify(invitations).insert(result.invitation());
        order.verify(transactions).commit(any());
        order.verify(grants).install(result.invitation());
        order.verify(tokens).encode(result.invitation(),"Grantor","host-public",keys.privateKey());
    }

    @Test void partialProvisioningFailureCancelsAndRemovesGrantWithoutReturningToken() {
        var keys = setupCreation();
        doThrow(new IllegalStateException("synthetic failure")).when(grants).install(any());
        when(invitations.cancel(any(),eq(NOW))).thenReturn(true);
        assertThatThrownBy(() -> service().create(endpoint,"Grantor")).isInstanceOf(IllegalStateException.class)
                .hasMessage("Invitation provisioning failed");
        verify(invitations).cancel(any(),eq(NOW));
        verify(grants).remove(any());
        verify(tokens,never()).encode(any(),any(),any(),any());
        assertThatThrownBy(keys.privateKey()::copyBytes).isInstanceOf(IllegalStateException.class);
    }

    @Test void readsExposeOnlyMetadataAndConsumedCancelDoesNotChangeSessionOrInvitation() {
        var invitation = invitation().redeem(UUID.randomUUID(), NOW);
        when(identity.getOrCreate()).thenReturn(LOCAL);
        when(invitations.findById(invitation.id())).thenReturn(Optional.of(invitation));
        when(invitations.findAll(LOCAL)).thenReturn(List.of(invitation));
        assertThat(service().get(invitation.id())).isEqualTo(invitation);
        assertThat(service().list()).containsExactly(invitation);
        service().cancel(invitation.id());
        verify(invitations,never()).cancel(any(),any());
        verify(grants).remove(invitation);
        verifyNoInteractions(tokens);
    }

    @Test void cancelPersistsDenialBeforeRemovingGrantAndCleanupFailureIsNotSuccess() {
        var invitation = invitation();
        when(identity.getOrCreate()).thenReturn(LOCAL);
        when(invitations.findById(invitation.id())).thenReturn(Optional.of(invitation), Optional.of(invitation.cancel(NOW)));
        when(invitations.cancel(invitation.id(),NOW)).thenReturn(true);
        doThrow(new IllegalStateException("cleanup failed")).when(grants).remove(any());
        assertThatThrownBy(() -> service().cancel(invitation.id())).isInstanceOf(IllegalStateException.class);
        var order = inOrder(invitations,grants);
        order.verify(invitations).cancel(invitation.id(),NOW);
        order.verify(grants).remove(any());
    }

    @Test void cleanupRemovesOnlyUnavailableLocalInvitationsAndContinuesAfterFailure() {
        var expired = invitation();
        var cancelled = invitation().cancel(NOW);
        when(identity.getOrCreate()).thenReturn(LOCAL);
        when(invitations.findAll(LOCAL)).thenReturn(List.of(expired,cancelled));
        doThrow(new IllegalStateException("cleanup failed")).when(grants).remove(expired);
        var service = new RemoteAccessInvitations(invitations,identity,tokens,grants,
                Clock.fixed(NOW.plusSeconds(301),ZoneOffset.UTC),transactions);
        assertThatThrownBy(service::cleanupUnavailable).isInstanceOf(IllegalStateException.class);
        verify(grants).remove(cancelled);
    }

    private RemoteAccessPairingKeys setupCreation() {
        when(identity.getOrCreate()).thenReturn(LOCAL);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(grants.hostPublicKey()).thenReturn("host-public");
        var keys = new RemoteAccessPairingKeys("public","fingerprint",new RemoteAccessPrivateKey("synthetic-secret".getBytes()));
        when(tokens.generate()).thenReturn(keys);
        return keys;
    }
    private RemoteAccessInvitations service() {
        return new RemoteAccessInvitations(invitations,identity,tokens,grants,Clock.fixed(NOW,ZoneOffset.UTC),transactions);
    }
    private RemoteAccessInvitation invitation() {
        return new RemoteAccessInvitation(UUID.randomUUID(),LOCAL,endpoint,"public","fingerprint",NOW,NOW.plusSeconds(300),null,null,null);
    }
}
