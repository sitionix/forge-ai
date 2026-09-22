package com.sitionix.forgeagent.application.remoteaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class RemoteAccessProvisioningServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");
    private final UUID local = UUID.randomUUID();
    @Mock private RemoteAccessInvitationRepository invitations;
    @Mock private RemoteAccessSessionRepository sessions;
    @Mock private ForgeInstanceIdentityRepository identity;
    @Mock private RemoteAccessCredentialStore credentials;
    @Mock private PlatformTransactionManager transactions;
    private RemoteAccessProvisioningService service;

    @BeforeEach
    void setUp() {
        service = new RemoteAccessProvisioningService(invitations, sessions, identity, credentials,
                Clock.fixed(NOW, ZoneOffset.UTC), transactions);
    }

    @Test
    void accessorIsPersistedProvisioningWithOnlyOpaqueKeyReference() {
        var candidate = accessor();
        try (var secret = new RemoteAccessPrivateKey("synthetic-private-material".getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            when(identity.getOrCreate()).thenReturn(local);
            when(credentials.store(candidate.id(), secret)).thenReturn(candidate.id());
            when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            assertThat(service.createAccessorSession(candidate, secret)).isEqualTo(candidate);
            assertThat(candidate.status()).isEqualTo(RemoteAccessSessionStatus.PROVISIONING);
            verify(sessions).insert(candidate);
            verify(credentials, never()).delete(any());
        }
    }

    @Test
    void commitFailureDeletesOnlyNewlyStoredKeyAndPreservesFailure() {
        var candidate = accessor();
        var failure = new IllegalStateException("database commit failed");
        try (var secret = new RemoteAccessPrivateKey(new byte[]{1,2,3})) {
            when(identity.getOrCreate()).thenReturn(local);
            when(credentials.store(candidate.id(), secret)).thenReturn(candidate.id());
            when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            doThrow(failure).when(transactions).commit(any());
            assertThatThrownBy(() -> service.createAccessorSession(candidate, secret)).isSameAs(failure);
            verify(credentials).delete(candidate.id());
        }
    }

    @Test
    void failedExclusiveKeyCreationDoesNotDeletePreexistingMaterialOrInsertSession() {
        var candidate = accessor();
        try (var secret = new RemoteAccessPrivateKey(new byte[]{1,2,3})) {
            when(identity.getOrCreate()).thenReturn(local);
            when(credentials.store(candidate.id(), secret)).thenThrow(new IllegalStateException("key already exists"));
            assertThatThrownBy(() -> service.createAccessorSession(candidate, secret))
                    .isInstanceOf(IllegalStateException.class).hasMessage("key already exists");
            verify(credentials, never()).delete(any());
            verifyNoInteractions(sessions, transactions);
        }
    }

    @Test
    void foreignAccessorIdentityIsRejectedBeforeWritingAnyKey() {
        var candidate = accessor();
        when(identity.getOrCreate()).thenReturn(UUID.randomUUID());
        try (var secret = new RemoteAccessPrivateKey(new byte[]{1,2,3})) {
            assertThatThrownBy(() -> service.createAccessorSession(candidate, secret)).isInstanceOf(IllegalArgumentException.class);
            verifyNoInteractions(credentials, sessions, transactions);
        }
    }

    private RemoteAccessSession accessor() {
        UUID id = UUID.randomUUID();
        return new RemoteAccessSession(id, UUID.randomUUID(), RemoteAccessRole.ACCESSOR, UUID.randomUUID(), local,
                "Grantor", new RemoteAccessEndpoint("localhost",2222,"forge"), "ssh-ed25519 public-host", "ssh-ed25519 public-session", "SHA256:public",
                id, RemoteAccessSessionStatus.PROVISIONING, NOW, NOW.plusSeconds(60), null,null,null,
                RemoteAccessConnectivity.UNKNOWN,null,null,null,null,0);
    }
}
