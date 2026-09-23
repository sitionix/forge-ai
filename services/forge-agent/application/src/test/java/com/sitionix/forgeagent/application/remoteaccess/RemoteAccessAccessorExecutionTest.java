package com.sitionix.forgeagent.application.remoteaccess;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RemoteAccessAccessorExecutionTest {
    static final UUID LOCAL=UUID.randomUUID(), ID=UUID.randomUUID(), KEY=UUID.randomUUID();
    static final Instant NOW=Instant.parse("2026-09-23T12:00:00Z");
    @Mock RemoteAccessSessionRepository sessions;
    @Mock ForgeInstanceIdentityRepository identity;
    @Mock RemoteAccessRevokeTransport transport;
    @Mock RemoteAccessCredentialStore credentials;
    @Mock RemoteAccessCommandTransport commands;
    AtomicReference<RemoteAccessSession> row;
    RemoteAccessAccessorExecution sut;
    @BeforeEach void setup() {
        row=new AtomicReference<>(new RemoteAccessSession(ID,UUID.randomUUID(),RemoteAccessRole.ACCESSOR,
            UUID.randomUUID(),LOCAL,"grantor",new RemoteAccessEndpoint("127.0.0.1",2222,"forge-ssh"),"host","key","fp",KEY,
            RemoteAccessSessionStatus.ACTIVE,NOW.minusSeconds(60),NOW.plusSeconds(240),NOW.minusSeconds(30),null,null,
            RemoteAccessConnectivity.UNKNOWN,null,null,null,null,1));
        when(identity.getOrCreate()).thenReturn(LOCAL);
        when(sessions.findById(ID)).thenAnswer(call -> Optional.of(row.get()));
        lenient().when(sessions.transition(any(),any())).thenAnswer(call -> row.compareAndSet(call.getArgument(0),call.getArgument(1)));
        sut=new RemoteAccessAccessorExecution(sessions,identity,transport,credentials,commands,Clock.fixed(NOW,ZoneOffset.UTC));
    }
    @Test void localRevokeIntentDeniesNewExecutionBeforeCallingTransport() {
        row.set(row.get().requestRevoke(NOW));
        assertThatThrownBy(() -> sut.start(ID,new RemoteAccessCommand(List.of("/bin/true"),"/workspace",1)))
            .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(commands);
    }
    @Test void offlineRevokeRetainsIntentAndCredential() {
        when(transport.revoke(any())).thenThrow(new IllegalStateException("connection denied"));
        assertThat(sut.revoke(ID).status()).isEqualTo(RemoteAccessSessionStatus.REVOKING);
        assertThat(row.get().localPrivateKeyReference()).isEqualTo(KEY);
        verifyNoInteractions(credentials);
    }
    @Test void remoteConfirmationIsPersistedBeforeKeyRemovalAndReferenceClearedAfterwards() {
        when(transport.revoke(any())).thenAnswer(call -> {
            assertThat(row.get().status()).isEqualTo(RemoteAccessSessionStatus.REVOKING);
            return RemoteAccessSessionStatus.REVOKED;
        });
        doAnswer(call -> {
            assertThat(row.get().status()).isEqualTo(RemoteAccessSessionStatus.REVOKED);
            assertThat(row.get().localPrivateKeyReference()).isEqualTo(KEY);
            return null;
        }).when(credentials).delete(KEY);
        assertThat(sut.revoke(ID).status()).isEqualTo(RemoteAccessSessionStatus.REVOKED);
        assertThat(row.get().localPrivateKeyReference()).isNull();
    }
    @Test void credentialFailureRetainsReferenceAndRestartRetriesWithoutRemoteGrant() {
        when(transport.revoke(any())).thenReturn(RemoteAccessSessionStatus.REVOKED);
        doThrow(new IllegalStateException("filesystem unavailable")).doNothing().when(credentials).delete(KEY);
        var result=sut.revoke(ID);
        assertThat(result.status()).isEqualTo(RemoteAccessSessionStatus.REVOKED);
        assertThat(result.localPrivateKeyReference()).isEqualTo(KEY);
        var restarted=new RemoteAccessAccessorExecution(sessions,identity,transport,credentials,commands,Clock.fixed(NOW,ZoneOffset.UTC));
        assertThat(restarted.revoke(ID).localPrivateKeyReference()).isNull();
        verify(transport,times(1)).revoke(any());
        verify(credentials,times(2)).delete(KEY);
    }
}
